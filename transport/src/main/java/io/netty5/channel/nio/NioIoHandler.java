/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel.nio;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelException;
import io.netty5.channel.DefaultSelectStrategyFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.IoExecutionContext;
import io.netty5.channel.IoHandle;
import io.netty5.channel.IoHandler;
import io.netty5.channel.IoHandlerFactory;
import io.netty5.channel.IoOps;
import io.netty5.channel.SelectStrategy;
import io.netty5.channel.SelectStrategyFactory;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.ReflectionUtil;
import io.netty5.util.internal.StringUtil;
import io.netty5.util.internal.SystemPropertyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import static java.util.Objects.requireNonNull;

/**
 * {@link IoHandler} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioIoHandler implements IoHandler {

    private static final Logger logger = LoggerFactory.getLogger(NioIoHandler.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty5.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = () -> {
        try {
            return selectNow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    };

    static {
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty5.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty5.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty5.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    private Selector unwrappedSelector;
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    private int cancelledKeys;
    private boolean needsToSelectAgain;

    private NioIoHandler() {
        this(SelectorProvider.provider(), DefaultSelectStrategyFactory.INSTANCE.newSelectStrategy());
    }

    private NioIoHandler(SelectorProvider selectorProvider, SelectStrategy strategy) {
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link NioIoHandler} instances.
     */
    public static IoHandlerFactory newFactory() {
        return NioIoHandler::new;
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link NioIoHandler} instances.
     */
    public static IoHandlerFactory newFactory(final SelectorProvider selectorProvider,
                                              final SelectStrategyFactory selectStrategyFactory) {
        requireNonNull(selectorProvider, "selectorProvider");
        requireNonNull(selectStrategyFactory, "selectStrategyFactory");
        return () -> new NioIoHandler(selectorProvider, selectStrategyFactory.newSelectStrategy());
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass;
        try {
            maybeSelectorImplClass = Class.forName(
                    "sun.nio.ch.SelectorImpl",
                    false,
                    ClassLoader.getSystemClassLoader());
        } catch (Throwable cause) {
            maybeSelectorImplClass = cause;
        }

        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = null;
        boolean useReflectionFallback = true;
        try {
            Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
            Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

            if (PlatformDependent.hasUnsafe()) {
                // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                // This allows us to also do this in Java9+ without any extra flags.
                long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                long publicSelectedKeysFieldOffset =
                        PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                    PlatformDependent.putObject(
                            unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                    PlatformDependent.putObject(
                            unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                    useReflectionFallback = false;
                }
            }

            if (useReflectionFallback) {
                // We could not retrieve the offset, lets try reflection as last-resort.
                maybeException = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                if (maybeException == null) {
                    maybeException = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                }

                if (maybeException == null) {
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            maybeException = e;
        }

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioIoHandler} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key : oldSelector.keys()) {
            DefaultNioRegistration handle = (DefaultNioRegistration) key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                handle.register(newSelectorTuple.unwrappedSelector);
                nChannels++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a NioHandle to the new Selector.", e);
                handle.cancel();
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    private static NioIoHandle nioHandle(IoHandle handle) {
        if (handle instanceof NioIoHandle) {
            return (NioIoHandle) handle;
        }
        throw new IllegalArgumentException("IoHandle of type " + StringUtil.simpleClassName(handle) + " not supported");
    }

    private static NioIoOps cast(IoOps ops) {
        if (ops instanceof NioIoOps) {
            return (NioIoOps) ops;
        }
        throw new IllegalArgumentException("IoOps of type " + StringUtil.simpleClassName(ops) + " not supported");
    }

    final class DefaultNioRegistration implements NioIoRegistration {
        private final NioIoHandle handle;
        private volatile SelectionKey key;

        DefaultNioRegistration(NioIoHandle handle, NioIoOps initialOps, Selector selector) throws IOException {
            this.handle = handle;
            key = handle.selectableChannel().register(selector, initialOps.value, this);
        }

        NioIoHandle handle() {
            return handle;
        }

        void register(Selector selector) throws IOException {
            SelectionKey newKey = handle.selectableChannel().register(selector, key.interestOps(), this);
            key.cancel();
            key = newKey;
        }

        @Override
        public SelectionKey selectionKey() {
            return key;
        }

        @Override
        public boolean isValid() {
            return key.isValid();
        }

        @Override
        public long submit(IoOps ops) {
            int v = cast(ops).value;
            key.interestOps(v);
            return v;
        }

        @Override
        public void cancel() {
            key.cancel();
            cancelledKeys++;
            if (cancelledKeys >= CLEANUP_INTERVAL) {
                cancelledKeys = 0;
                needsToSelectAgain = true;
            }
        }

        void close() {
            cancel();
            try {
                handle.close();
            } catch (Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }

        void handle(int ready) {
            handle.handle(this, NioIoOps.eventOf(ready));
        }

        @Override
        public NioIoHandler ioHandler() {
            return NioIoHandler.this;
        }
    }

    @Override
    public NioIoRegistration register(EventLoop eventLoop, IoHandle handle)
            throws Exception {
        NioIoHandle nioHandle = nioHandle(handle);
        NioIoOps ops = NioIoOps.NONE;
        boolean selected = false;
        for (;;) {
            try {
                return new DefaultNioRegistration(nioHandle, ops, unwrappedSelector());
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    public int run(IoExecutionContext runner) {
        int handled = 0;
        try {
            try {
                switch (selectStrategy.calculateStrategy(selectNowSupplier, !runner.canBlock())) {
                    case SelectStrategy.CONTINUE:
                        return 0;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        select(runner, wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).

                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }
            } catch (IOException e) {
                // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                // the selector and retry. https://github.com/netty/netty/issues/8566
                rebuildSelector0();
                handleLoopException(e);
                return 0;
            }

            cancelledKeys = 0;
            needsToSelectAgain = false;
            handled = processSelectedKeys();
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            handleLoopException(t);
        }
        return handled;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private int processSelectedKeys() {
        if (selectedKeys != null) {
            return processSelectedKeysOptimized();
        } else {
            return processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    public void destroy() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    private int processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return 0;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        int handled = 0;
        for (;;) {
            final SelectionKey k = i.next();
            i.remove();

            processSelectedKey(k);
            ++handled;

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
        return handled;
    }

    private int processSelectedKeysOptimized() {
        int handled = 0;
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            processSelectedKey(k);
            ++handled;

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
        return handled;
    }

    private void processSelectedKey(SelectionKey k) {
        final DefaultNioRegistration registration = (DefaultNioRegistration) k.attachment();
        if (!registration.isValid()) {
            try {
                registration.handle.close();
            } catch (Exception e) {
                logger.debug("Exception during closing " + registration.handle, e);
            }
            return;
        }
        registration.handle(k.readyOps());
    }

    @Override
    public void prepareToDestroy() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<DefaultNioRegistration> registrations = new ArrayList<>(keys.size());
        for (SelectionKey k: keys) {
            DefaultNioRegistration handle = (DefaultNioRegistration) k.attachment();
            registrations.add(handle);
        }

        for (DefaultNioRegistration reg: registrations) {
            reg.close();
        }
    }

    @Override
    public void wakeup(EventLoop eventLoop) {
        if (!eventLoop.inEventLoop() && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return NioIoHandle.class.isAssignableFrom(handleType);
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    private int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    private void select(IoExecutionContext runner, boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            long selectDeadLineNanos = currentTimeNanos + runner.delayNanos(currentTimeNanos);

            for (;;) {
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                if (!runner.canBlock() && wakenUp.compareAndSet(false, true)) {
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || !runner.canBlock()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioHandler.shutdownGracefully() to shutdown the NioHandler.");
                    }
                    selectCnt = 1;
                    break;
                }

                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector0();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
