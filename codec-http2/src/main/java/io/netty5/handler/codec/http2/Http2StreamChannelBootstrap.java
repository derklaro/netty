/*
 * Copyright 2017 The Netty Project
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
package io.netty5.handler.codec.http2;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelPipeline;
import io.netty5.util.AttributeKey;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.StringUtil;
import io.netty5.util.internal.UnstableApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

@UnstableApi
public final class Http2StreamChannelBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(Http2StreamChannelBootstrap.class);
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    // The order in which ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<>();
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<>();
    private final Channel channel;
    private volatile ChannelHandler handler;

    // Cache the ChannelHandlerContext to speed up open(...) operations.
    private volatile ChannelHandlerContext multiplexCtx;

    public Http2StreamChannelBootstrap(Channel channel) {
        this.channel = requireNonNull(channel, "channel");
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Http2StreamChannel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> Http2StreamChannelBootstrap option(ChannelOption<T> option, T value) {
        requireNonNull(option, "option");

        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return this;
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Http2StreamChannel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> Http2StreamChannelBootstrap attr(AttributeKey<T> key, T value) {
        requireNonNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return this;
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    public Http2StreamChannelBootstrap handler(ChannelHandler handler) {
        this.handler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Open a new {@link Http2StreamChannel} to use.
     * @return the {@link Future} that will be notified once the channel was opened successfully or it failed.
     */
    public Future<Http2StreamChannel> open() {
        return open(channel.executor().newPromise());
    }

    /**
     * Open a new {@link Http2StreamChannel} to use and notifies the given {@link Promise}.
     * @return the {@link Future} that will be notified once the channel was opened successfully or it failed.
     */
    public Future<Http2StreamChannel> open(final Promise<Http2StreamChannel> promise) {
        try {
            ChannelHandlerContext ctx = findCtx();
            EventExecutor executor = ctx.executor();
            if (executor.inEventLoop()) {
                open0(ctx, promise);
            } else {
                final ChannelHandlerContext finalCtx = ctx;
                executor.execute(() -> {
                    if (channel.isActive()) {
                        open0(finalCtx, promise);
                    } else {
                        promise.setFailure(new ClosedChannelException());
                    }
                });
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise.asFuture();
    }

    @SuppressWarnings("deprecation")
    private ChannelHandlerContext findCtx() throws ClosedChannelException {
        // First try to use cached context and if this not work lets try to lookup the context.
        ChannelHandlerContext ctx = multiplexCtx;
        if (ctx != null && !ctx.isRemoved()) {
            return ctx;
        }
        ChannelPipeline pipeline = channel.pipeline();
        ctx = pipeline.context(Http2MultiplexHandler.class);
        if (ctx == null) {
            if (channel.isActive()) {
                throw new IllegalStateException(StringUtil.simpleClassName(Http2MultiplexHandler.class)
                        + " must be in the ChannelPipeline of Channel " + channel);
            } else {
                throw new ClosedChannelException();
            }
        }
        multiplexCtx = ctx;
        return ctx;
    }

    /**
     * @deprecated should not be used directly. Use {@link #open()} or {@link #open(Promise)}
     */
    @Deprecated
    public void open0(ChannelHandlerContext ctx, final Promise<Http2StreamChannel> promise) {
        assert ctx.executor().inEventLoop();
        if (!promise.setUncancellable()) {
            return;
        }
        final Http2StreamChannel streamChannel;
        try {
            streamChannel = ((Http2MultiplexHandler) ctx.handler()).newOutboundStream();
        } catch (Exception e) {
            promise.setFailure(e);
            return;
        }
        try {
            init(streamChannel);
        } catch (Exception e) {
            streamChannel.close();
            promise.setFailure(e);
            return;
        }

        Future<Void> future = streamChannel.register();
        future.addListener(future1 -> {
            if (future1.isSuccess()) {
                promise.setSuccess(streamChannel);
            } else if (future1.isCancelled()) {
                promise.cancel();
            } else {
                streamChannel.close();

                promise.setFailure(future1.cause());
            }
        });
    }

    private void init(Channel channel) {
        ChannelPipeline p = channel.pipeline();
        ChannelHandler handler = this.handler;
        if (handler != null) {
            p.addLast(handler);
        }
        final Map.Entry<ChannelOption<?>, Object> [] optionArray;
        synchronized (options) {
            optionArray = options.entrySet().toArray(EMPTY_OPTION_ARRAY);
        }

        setChannelOptions(channel, optionArray);
        setAttributes(channel, attrs.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY));
    }

    private static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue());
        }
    }

    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value) {
        try {
            @SuppressWarnings("unchecked")
            ChannelOption<Object> opt = (ChannelOption<Object>) option;
            if (!channel.isOptionSupported(option)) {
                logger.warn("{} Unknown channel option '{}'", channel, option);
            } else {
                channel.setOption(opt, value);
            }
        } catch (Throwable t) {
            logger.warn("{} Failed to set channel option '{}' with value '{}'", channel, option, value, t);
        }
    }

    private static void setAttributes(
            Channel channel, Map.Entry<AttributeKey<?>, Object>[] options) {
        for (Map.Entry<AttributeKey<?>, Object> e: options) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }
}
