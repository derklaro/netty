/*
 * Copyright 2014 The Netty Project
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
package io.netty5.handler.ssl;

import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.channel.local.LocalAddress;
import io.netty5.channel.local.LocalChannel;
import io.netty5.channel.local.LocalIoHandler;
import io.netty5.channel.local.LocalServerChannel;
import io.netty5.channel.nio.NioIoHandler;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import io.netty5.handler.codec.DecoderException;
import io.netty5.handler.codec.TooLongFrameException;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.pkitesting.CertificateBuilder;
import io.netty5.pkitesting.X509Bundle;
import io.netty5.util.DomainNameMapping;
import io.netty5.util.DomainNameMappingBuilder;
import io.netty5.util.Mapping;
import io.netty5.util.ReferenceCounted;
import io.netty5.util.Resource;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.ResourcesUtil;
import io.netty5.util.internal.SilentDispose;
import io.netty5.util.internal.StringUtil;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty5.buffer.DefaultBufferAllocators.offHeapAllocator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

public class SniHandlerTest {
    private static final Logger logger = LoggerFactory.getLogger(SniHandlerTest.class);
    private static final X509Bundle CERT;

    static {
        try {
            CERT = new CertificateBuilder()
                    .subject("cn=localhost")
                    .setIsCertificateAuthority(true)
                    .buildSelfSigned();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static ApplicationProtocolConfig newAlpnConfig() {
        return new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                "myprotocol");
    }

    private static void assumeAlpnSupported(SslProvider provider) {
        switch (provider) {
            case OPENSSL:
            case OPENSSL_REFCNT:
                assumeTrue(OpenSsl.isAlpnSupported());
                break;
            case JDK:
                assumeTrue(JdkAlpnSslUtils.supportsAlpn());
                break;
            default:
                throw new Error();
        }
    }

    private static SslContext makeSslContext(SslProvider provider, boolean alpn) throws Exception {
        if (alpn) {
            assumeAlpnSupported(provider);
        }

        File keyFile = ResourcesUtil.getFile(SniHandlerTest.class, "test_encrypted.pem");
        File crtFile = ResourcesUtil.getFile(SniHandlerTest.class, "test.crt");

        SslContextBuilder sslCtxBuilder = SslContextBuilder.forServer(crtFile, keyFile, "12345")
                .sslProvider(provider);
        if (alpn) {
            sslCtxBuilder.applicationProtocolConfig(newAlpnConfig());
        }
        return sslCtxBuilder.build();
    }

    private static SslContext makeSslClientContext(SslProvider provider, boolean alpn) throws Exception {
        if (alpn) {
            assumeAlpnSupported(provider);
        }

        File crtFile = ResourcesUtil.getFile(SniHandlerTest.class, "test.crt");

        SslContextBuilder sslCtxBuilder = SslContextBuilder.forClient()
                .trustManager(crtFile)
                .endpointIdentificationAlgorithm(null)
                .sslProvider(provider);
        if (alpn) {
            sslCtxBuilder.applicationProtocolConfig(newAlpnConfig());
        }
        return sslCtxBuilder.build();
    }

    static Iterable<?> data() {
        List<SslProvider> params = new ArrayList<>(3);
        if (OpenSsl.isAvailable()) {
            params.add(SslProvider.OPENSSL);
            params.add(SslProvider.OPENSSL_REFCNT);
        }
        params.add(SslProvider.JDK);
        return params;
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testNonSslRecord(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, false);
        try {
            final AtomicReference<SslHandshakeCompletionEvent> evtRef = new AtomicReference<>();
            SniHandler handler = new SniHandler(new DomainNameMappingBuilder<>(nettyContext).build());
            final EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
                @Override
                public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof SslHandshakeCompletionEvent) {
                        assertTrue(evtRef.compareAndSet(null, (SslHandshakeCompletionEvent) evt));
                    }
                }
            });

            try {
                final byte[] bytes = new byte[1024];
                bytes[0] = SslUtils.SSL_CONTENT_TYPE_ALERT;

                DecoderException e = assertThrows(DecoderException.class, () -> {
                    ch.writeInbound(ch.bufferAllocator().copyOf(bytes));
                });
                assertThat(e.getCause(), CoreMatchers.instanceOf(NotSslRecordException.class));
                assertFalse(ch.finish());
            } finally {
                ch.finishAndReleaseAll();
            }
            assertThat(evtRef.get().cause(), CoreMatchers.instanceOf(NotSslRecordException.class));
        } finally {
            releaseAll(nettyContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testServerNameParsing(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, false);
        SslContext leanContext = makeSslContext(provider, false);
        SslContext leanContext2 = makeSslContext(provider, false);

        try {
            DomainNameMapping<SslContext> mapping = new DomainNameMappingBuilder<>(nettyContext)
                    .add("*.netty.io", nettyContext)
                    // input with custom cases
                    .add("*.LEANCLOUD.CN", leanContext)
                    // a hostname conflict with previous one, since we are using order-sensitive config,
                    // the engine won't be used with the handler.
                    .add("chat4.leancloud.cn", leanContext2)
                    .build();

            final AtomicReference<SniCompletionEvent> evtRef = new AtomicReference<>();
            SniHandler handler = new SniHandler(mapping);
            EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
                @Override
                public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof SniCompletionEvent) {
                        assertTrue(evtRef.compareAndSet(null, (SniCompletionEvent) evt));
                    } else {
                        ctx.fireChannelInboundEvent(evt);
                    }
                }
            });

            try {
                // hex dump of a client hello packet, which contains hostname "CHAT4.LEANCLOUD.CN"
                String tlsHandshakeMessageHex1 = "16030100";
                // part 2
                String tlsHandshakeMessageHex = "c6010000c20303bb0855d66532c05a0ef784f7c384feeafa68b3" +
                        "b655ac7288650d5eed4aa3fb52000038c02cc030009fcca9cca8ccaac02b" +
                        "c02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d" +
                        "009c003d003c0035002f00ff010000610000001700150000124348415434" +
                        "2e4c45414e434c4f55442e434e000b000403000102000a000a0008001d00" +
                        "170019001800230000000d0020001e060106020603050105020503040104" +
                        "0204030301030203030201020202030016000000170000";

                ch.writeInbound(ch.bufferAllocator().copyOf(StringUtil.decodeHexDump(tlsHandshakeMessageHex1)));
                ch.writeInbound(ch.bufferAllocator().copyOf(StringUtil.decodeHexDump(tlsHandshakeMessageHex)));

                // This should produce an alert
                assertTrue(ch.finish());

                assertThat(handler.hostname(), is("chat4.leancloud.cn"));
                assertThat(handler.sslContext(), is(leanContext));

                SniCompletionEvent evt = evtRef.get();
                assertNotNull(evt);
                assertEquals("chat4.leancloud.cn", evt.hostname());
                assertTrue(evt.isSuccess());
                assertNull(evt.cause());
            } finally {
                ch.finishAndReleaseAll();
            }
        } finally {
            releaseAll(leanContext, leanContext2, nettyContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testNonAsciiServerNameParsing(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, false);
        SslContext leanContext = makeSslContext(provider, false);
        SslContext leanContext2 = makeSslContext(provider, false);

        try {
            DomainNameMapping<SslContext> mapping = new DomainNameMappingBuilder<>(nettyContext)
                    .add("*.netty.io", nettyContext)
                    // input with custom cases
                    .add("*.LEANCLOUD.CN", leanContext)
                    // a hostname conflict with previous one, since we are using order-sensitive config,
                    // the engine won't be used with the handler.
                    .add("chat4.leancloud.cn", leanContext2)
                    .build();

            SniHandler handler = new SniHandler(mapping);
            final EmbeddedChannel ch = new EmbeddedChannel(handler);

            try {
                // hex dump of a client hello packet, which contains an invalid hostname "CHAT4。LEANCLOUD。CN"
                String tlsHandshakeMessageHex1 = "16030100";
                // part 2
                final String tlsHandshakeMessageHex = "bd010000b90303a74225676d1814ba57faff3b366" +
                        "3656ed05ee9dbb2a4dbb1bb1c32d2ea5fc39e0000000100008c0000001700150000164348" +
                        "415434E380824C45414E434C4F5544E38082434E000b000403000102000a00340032000e0" +
                        "00d0019000b000c00180009000a0016001700080006000700140015000400050012001300" +
                        "0100020003000f0010001100230000000d0020001e0601060206030501050205030401040" +
                        "20403030103020303020102020203000f00010133740000";

                // Push the handshake message.
                // Decode should fail because of the badly encoded "HostName" string in the SNI extension
                // that isn't ASCII as per RFC 6066 - https://tools.ietf.org/html/rfc6066#page-6
                ch.writeInbound(ch.bufferAllocator().copyOf(StringUtil.decodeHexDump(tlsHandshakeMessageHex1)));

                assertThrows(DecoderException.class, () -> {
                    ch.writeInbound(ch.bufferAllocator().copyOf(StringUtil.decodeHexDump(tlsHandshakeMessageHex)));
                });
            } finally {
                ch.finishAndReleaseAll();
            }
        } finally {
            releaseAll(leanContext, leanContext2, nettyContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testFallbackToDefaultContext(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, false);
        SslContext leanContext = makeSslContext(provider, false);
        SslContext leanContext2 = makeSslContext(provider, false);

        try {
            DomainNameMapping<SslContext> mapping = new DomainNameMappingBuilder<>(nettyContext)
                    .add("*.netty.io", nettyContext)
                    // input with custom cases
                    .add("*.LEANCLOUD.CN", leanContext)
                    // a hostname conflict with previous one, since we are using order-sensitive config,
                    // the engine won't be used with the handler.
                    .add("chat4.leancloud.cn", leanContext2)
                    .build();

            SniHandler handler = new SniHandler(mapping);
            EmbeddedChannel ch = new EmbeddedChannel(handler);

            // invalid
            byte[] message = {22, 3, 1, 0, 0};
            try {
                // Push the handshake message.
                ch.writeInbound(ch.bufferAllocator().copyOf(message));
                // TODO(scott): This should fail because the engine should reject zero length records during handshake.
                // See https://github.com/netty/netty/issues/6348.
                // fail();
            } catch (Exception e) {
                // expected
            }

            ch.close();

            // When the channel is closed the SslHandler will write an empty buffer to the channel.
            Buffer buf = ch.readOutbound();
            // TODO(scott): if the engine is shutdown correctly then this buffer shouldn't be null!
            // See https://github.com/netty/netty/issues/6348.
            if (buf != null) {
                assertEquals(0, buf.readableBytes());
                buf.close();
            }

            assertThat(ch.finish(), is(false));
            assertThat(handler.hostname(), nullValue());
            assertThat(handler.sslContext(), is(nettyContext));
        } finally {
            releaseAll(leanContext, leanContext2, nettyContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
    public void testMajorVersionNot3(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, false);

        try {
            DomainNameMapping<SslContext> mapping = new DomainNameMappingBuilder<SslContext>(nettyContext).build();

            SniHandler handler = new SniHandler(mapping);
            EmbeddedChannel ch = new EmbeddedChannel(handler);

            // invalid
            byte[] message = {22, 2, 0, 0, 0};
            try {
                // Push the handshake message.
                ch.writeInbound(ch.bufferAllocator().copyOf(message));
                // TODO(scott): This should fail because the engine should reject zero length records during handshake.
                // See https://github.com/netty/netty/issues/6348.
                // fail();
            } catch (Exception e) {
                // expected
            }

            ch.close();

            // Consume all the outbound data that may be produced by the SSLEngine.
            for (;;) {
                Buffer buf = ch.readOutbound();
                if (buf == null) {
                    break;
                }
                buf.close();
            }

            assertThat(ch.finish(), is(false));
            assertThat(handler.hostname(), nullValue());
            assertThat(handler.sslContext(), is(nettyContext));
        } finally {
            releaseAll(nettyContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testSniWithAlpnHandler(SslProvider provider) throws Exception {
        SslContext nettyContext = makeSslContext(provider, true);
        SslContext sniContext = makeSslContext(provider, true);
        final SslContext clientContext = makeSslClientContext(provider, true);
        try {
            final AtomicBoolean serverAlpnCtx = new AtomicBoolean(false);
            final AtomicBoolean clientAlpnCtx = new AtomicBoolean(false);
            final CountDownLatch serverAlpnDoneLatch = new CountDownLatch(1);
            final CountDownLatch clientAlpnDoneLatch = new CountDownLatch(1);

            final DomainNameMapping<SslContext> mapping = new DomainNameMappingBuilder<>(nettyContext)
                    .add("*.netty.io", nettyContext)
                    .add("sni.fake.site", sniContext).build();
            final SniHandler handler = new SniHandler(mapping);
            EventLoopGroup group = new MultithreadEventLoopGroup(2, NioIoHandler.newFactory());
            Channel serverChannel = null;
            Channel clientChannel = null;
            try {
                ServerBootstrap sb = new ServerBootstrap();
                sb.group(group);
                sb.channel(NioServerSocketChannel.class);
                sb.childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        // Server side SNI.
                        p.addLast(handler);
                        // Catch the notification event that ALPN has completed successfully.
                        p.addLast(new ApplicationProtocolNegotiationHandler("foo") {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                // addresses issue #9131
                                serverAlpnCtx.set(ctx.pipeline().context(this) != null);
                                serverAlpnDoneLatch.countDown();
                            }
                        });
                    }
                });

                Bootstrap cb = new Bootstrap();
                cb.group(group);
                cb.channel(NioSocketChannel.class);
                cb.handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(new SslHandler(clientContext.newEngine(
                                ch.bufferAllocator(), "sni.fake.site", -1)));
                        // Catch the notification event that ALPN has completed successfully.
                        ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler("foo") {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                                // addresses issue #9131
                                clientAlpnCtx.set(ctx.pipeline().context(this) != null);
                                clientAlpnDoneLatch.countDown();
                            }
                        });
                    }
                });

                serverChannel = sb.bind(new InetSocketAddress(0)).asStage().get();
                clientChannel = cb.connect(serverChannel.localAddress()).asStage().get();

                assertTrue(serverAlpnDoneLatch.await(5, TimeUnit.SECONDS));
                assertTrue(clientAlpnDoneLatch.await(5, TimeUnit.SECONDS));
                assertTrue(serverAlpnCtx.get());
                assertTrue(clientAlpnCtx.get());
                assertThat(handler.hostname(), is("sni.fake.site"));
                assertThat(handler.sslContext(), is(sniContext));
            } finally {
                if (serverChannel != null) {
                    serverChannel.close().asStage().sync();
                }
                if (clientChannel != null) {
                    clientChannel.close().asStage().sync();
                }
                group.shutdownGracefully(0, 0, TimeUnit.MICROSECONDS);
            }
        } finally {
            releaseAll(clientContext, nettyContext, sniContext);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS)
    public void testReplaceHandler(SslProvider provider) throws Exception {
        switch (provider) {
            case OPENSSL:
            case OPENSSL_REFCNT:
                final String sniHost = "sni.netty.io";
                LocalAddress address = new LocalAddress(getClass());
                EventLoopGroup group = new MultithreadEventLoopGroup(1, LocalIoHandler.newFactory());
                Channel sc = null;
                Channel cc = null;
                SslContext sslContext = null;

                try {
                    final SslContext sslServerContext = SslContextBuilder
                            .forServer(CERT.getKeyPair().getPrivate(), CERT.getCertificatePath())
                            .sslProvider(provider)
                            .build();

                    final Mapping<String, SslContext> mapping = input -> sslServerContext;

                    final Promise<Void> releasePromise = group.next().newPromise();

                    final SniHandler handler = new SniHandler(mapping) {
                        @Override
                        protected void replaceHandler(ChannelHandlerContext ctx,
                                                      String hostname, final SslContext sslContext)
                                throws Exception {

                            boolean success = false;
                            try {
                                assertEquals(1, ((ReferenceCountedOpenSslContext) sslContext).refCnt());
                                // The SniHandler's replaceHandler() method allows us to implement custom behavior.
                                // As an example, we want to release() the SslContext upon channelInactive() or rather
                                // when the SslHandler closes it's SslEngine. If you take a close look at SslHandler
                                // you'll see that it's doing it in the #handlerRemoved0() method.

                                SSLEngine sslEngine = sslContext.newEngine(ctx.bufferAllocator());
                                try {
                                    assertEquals(2, ((ReferenceCountedOpenSslContext) sslContext).refCnt());
                                    SslHandler customSslHandler = new CustomSslHandler(sslContext, sslEngine) {
                                        @Override
                                        public void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
                                            try {
                                                super.handlerRemoved0(ctx);
                                            } finally {
                                                releasePromise.trySuccess(null);
                                            }
                                        }
                                    };
                                    ctx.pipeline().replace(this, CustomSslHandler.class.getName(), customSslHandler);
                                    success = true;
                                } finally {
                                    if (!success) {
                                        SilentDispose.dispose(sslEngine, logger);
                                    }
                                }
                            } finally {
                                if (!success) {
                                    SilentDispose.dispose(sslContext, logger);
                                    releasePromise.cancel();
                                }
                            }
                        }
                    };

                    ServerBootstrap sb = new ServerBootstrap();
                    sc = sb.group(group).channel(LocalServerChannel.class)
                           .childHandler(new ChannelInitializer<Channel>() {
                               @Override
                               protected void initChannel(Channel ch) throws Exception {
                                   ch.pipeline().addFirst(handler);
                               }
                           }).bind(address).asStage().get();

                    sslContext = SslContextBuilder.forClient().sslProvider(provider)
                            .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

                    Bootstrap cb = new Bootstrap();
                    cc = cb.group(group).channel(LocalChannel.class)
                           .handler(new SslHandler(
                                   sslContext.newEngine(offHeapAllocator(), sniHost, -1)))
                           .connect(address).asStage().get();

                    cc.writeAndFlush(cc.bufferAllocator().copyOf("Hello, World!", UTF_8)).asStage().sync();

                    // Notice how the server's SslContext refCnt is 2 as it is incremented when the SSLEngine is created
                    // and only decremented once it is destroyed.
                    assertEquals(2, ((ReferenceCounted) sslServerContext).refCnt());

                    // The client disconnects
                    cc.close().asStage().sync();
                    if (!releasePromise.asFuture().asStage().await(10L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("It doesn't seem #replaceHandler() got called.");
                    }

                    // We should have successfully release() the SslContext
                    assertEquals(0, ((ReferenceCounted) sslServerContext).refCnt());
                } finally {
                    if (cc != null) {
                        cc.close().asStage().sync();
                    }
                    if (sc != null) {
                        sc.close().asStage().sync();
                    }
                    if (sslContext != null) {
                        Resource.dispose(sslContext);
                    }
                    group.shutdownGracefully();
                }
            case JDK:
                return;
            default:
                throw new Error();
        }
    }

    /**
     * This is a {@link SslHandler} that will call {@code release()} on the {@link SslContext} when
     * the client disconnects.
     *
     * @see SniHandlerTest#testReplaceHandler(SslProvider)
     */
    private static class CustomSslHandler extends SslHandler {
        private final SslContext sslContext;

        CustomSslHandler(SslContext sslContext, SSLEngine sslEngine) {
            super(sslEngine);
            this.sslContext = requireNonNull(sslContext, "sslContext");
        }

        @Override
        public void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
            super.handlerRemoved0(ctx);
            Resource.dispose(sslContext);
        }
    }

    private static void releaseAll(SslContext... contexts) {
        for (SslContext ctx: contexts) {
            Resource.dispose(ctx);
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testNonFragmented(SslProvider provider) throws Exception {
        testWithFragmentSize(provider, Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testFragmented(SslProvider provider) throws Exception {
        testWithFragmentSize(provider, 50);
    }

    private static void testWithFragmentSize(SslProvider provider, final int maxFragmentSize) throws Exception {
        final String sni = "netty.io";
        final SslContext context = SslContextBuilder.forServer(CERT.getKeyPair().getPrivate(),
                        CERT.getCertificatePath())
                .sslProvider(provider)
                .build();
        try {
            @SuppressWarnings("unchecked") final EmbeddedChannel server = new EmbeddedChannel(
                    new SniHandler(mock(DomainNameMapping.class)) {
                @Override
                protected Future<SslContext> lookup(final ChannelHandlerContext ctx, final String hostname) {
                    assertEquals(sni, hostname);
                    return ctx.executor().newSucceededFuture(context);
                }
            });

            final List<Buffer> buffers = clientHelloInMultipleFragments(provider, sni, maxFragmentSize);
            for (Buffer buffer : buffers) {
                server.writeInbound(buffer);
            }
            assertTrue(server.finishAndReleaseAll());
        } finally {
            releaseAll(context);
        }
    }

    private static List<Buffer> clientHelloInMultipleFragments(
            SslProvider provider, String hostname, int maxTlsPlaintextSize) throws SSLException {
        final EmbeddedChannel client = new EmbeddedChannel();
        final SslContext ctx = SslContextBuilder.forClient()
                .sslProvider(provider)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        try {
            final SslHandler sslHandler = ctx.newHandler(client.bufferAllocator(), hostname, -1);
            client.pipeline().addLast(sslHandler);
            try (Buffer clientHello = client.readOutbound()) {
                List<Buffer> buffers = split(client.bufferAllocator(), clientHello, maxTlsPlaintextSize);
                assertTrue(client.finishAndReleaseAll());
                return buffers;
            }
        } finally {
            releaseAll(ctx);
        }
    }

    private static List<Buffer> split(BufferAllocator alloc, Buffer clientHello, int maxSize) {
        final int type = clientHello.readUnsignedByte();
        final int version = clientHello.readUnsignedShort();
        final int length = clientHello.readUnsignedShort();
        assertEquals(length, clientHello.readableBytes());

        final List<Buffer> result = new ArrayList<>();
        while (clientHello.readableBytes() > 0) {
            final int toRead = Math.min(maxSize, clientHello.readableBytes());
            final Buffer bb = alloc.allocate(SslUtils.SSL_RECORD_HEADER_LENGTH + toRead);
            bb.writeUnsignedByte(type);
            bb.writeUnsignedShort(version);
            bb.writeUnsignedShort(toRead);
            try (Buffer chunk = clientHello.readSplit(toRead)) {
                bb.writeBytes(chunk);
            }
            result.add(bb);
        }
        return result;
    }

    @Test
    public void testSniHandlerFailsOnTooBigClientHello() throws Exception {
        SniHandler handler = new SniHandler(new Mapping<String, SslContext>() {
            @Override
            public SslContext map(String input) {
                throw new UnsupportedOperationException("Should not be called");
            }
        }, 10, 0);

        final AtomicReference<SniCompletionEvent> completionEventRef =
                new AtomicReference<SniCompletionEvent>();
        final EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
            @Override
            public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof SniCompletionEvent) {
                    completionEventRef.set((SniCompletionEvent) evt);
                }
            }
        });
        final Buffer buffer = ch.bufferAllocator().allocate(128);
        buffer.writeByte((byte) 0x16);      // Content Type: Handshake
        buffer.writeShort((short) 0x0303); // TLS 1.2
        buffer.writeShort((short) 0x0006); // Packet length

        // 16_777_215
        buffer.writeByte((byte) 0x01); // Client Hello
        buffer.writeMedium(0xFFFFFF); // Length
        buffer.writeShort((short) 0x0303); // TLS 1.2

        assertThrows(TooLongFrameException.class, () -> ch.writeInbound(buffer));
        try {
            while (completionEventRef.get() == null) {
                Thread.sleep(100);
                // We need to run all pending tasks as the handshake timeout is scheduled on the EventLoop.
                ch.runPendingTasks();
            }
            SniCompletionEvent completionEvent = completionEventRef.get();
            assertNotNull(completionEvent);
            assertNotNull(completionEvent.cause());
            assertEquals(TooLongFrameException.class, completionEvent.cause().getClass());
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    public void testSniHandlerFiresHandshakeTimeout() throws Exception {
        SniHandler handler = new SniHandler(new Mapping<String, SslContext>() {
            @Override
            public SslContext map(String input) {
                throw new UnsupportedOperationException("Should not be called");
            }
        }, 0, 10);

        final AtomicReference<SniCompletionEvent> completionEventRef =
            new AtomicReference<SniCompletionEvent>();
        EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
            @Override
            public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof SniCompletionEvent) {
                    completionEventRef.set((SniCompletionEvent) evt);
                }
            }
        });
        try {
            while (completionEventRef.get() == null) {
                Thread.sleep(100);
                // We need to run all pending tasks as the handshake timeout is scheduled on the EventLoop.
                ch.runPendingTasks();
            }
            SniCompletionEvent completionEvent = completionEventRef.get();
            assertNotNull(completionEvent);
            assertNotNull(completionEvent.cause());
            assertEquals(SslHandshakeTimeoutException.class, completionEvent.cause().getClass());
        } finally {
            ch.finishAndReleaseAll();
        }
    }

    @ParameterizedTest(name = "{index}: sslProvider={0}")
    @MethodSource("data")
    public void testSslHandlerFiresHandshakeTimeout(SslProvider provider) throws Exception {
        final SslContext context = makeSslContext(provider, false);
        SniHandler handler = new SniHandler(new Mapping<String, SslContext>() {
            @Override
            public SslContext map(String input) {
                return context;
            }
        }, 0, 100);

        final AtomicReference<SniCompletionEvent> sniCompletionEventRef =
            new AtomicReference<SniCompletionEvent>();
        final AtomicReference<SslHandshakeCompletionEvent> handshakeCompletionEventRef =
            new AtomicReference<SslHandshakeCompletionEvent>();
        EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelHandler() {
            @Override
            public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof SniCompletionEvent) {
                    sniCompletionEventRef.set((SniCompletionEvent) evt);
                } else if (evt instanceof SslHandshakeCompletionEvent) {
                    handshakeCompletionEventRef.set((SslHandshakeCompletionEvent) evt);
                }
            }
        });
        try {
            // Send enough data to add the SslHandler and let the handshake incomplete
            // Client Hello with "host1" server name
            ch.writeInbound(ch.bufferAllocator().copyOf(StringUtil.decodeHexDump(
                "16030301800100017c0303478ae7e536aa7a9debad1f873121862d2d3d3173e0ef42975c31007faeb2" +
                "52522047f55f81fc84fe58951e2af14026147d6178498fde551fcbafc636462c016ec9005a13011302" +
                "c02cc02bc030009dc02ec032009f00a3c02f009cc02dc031009e00a2c024c028003dc026c02a006b00" +
                "6ac00ac0140035c005c00f00390038c023c027003cc025c02900670040c009c013002fc004c00e0033" +
                "003200ff010000d90000000a0008000005686f737431000500050100000000000a00160014001d0017" +
                "00180019001e01000101010201030104000b00020100000d0028002604030503060308040805080608" +
                "09080a080b040105010601040203030301030202030201020200320028002604030503060308040805" +
                "08060809080a080b040105010601040203030301030202030201020200110009000702000400000000" +
                "00170000002b00050403040303002d00020101003300260024001d00200bbc37375e214c1e4e7cb90f" +
                "869e131dc983a21f8205ba24456177f340904935")));

            while (handshakeCompletionEventRef.get() == null) {
                Thread.sleep(10);
                // We need to run all pending tasks as the handshake timeout is scheduled on the EventLoop.
                ch.runPendingTasks();
            }
            SniCompletionEvent sniCompletionEvent = sniCompletionEventRef.get();
            assertNotNull(sniCompletionEvent);
            assertEquals("host1", sniCompletionEvent.hostname());
            SslCompletionEvent handshakeCompletionEvent = handshakeCompletionEventRef.get();
            assertNotNull(handshakeCompletionEvent);
            assertNotNull(handshakeCompletionEvent.cause());
            assertEquals(SslHandshakeTimeoutException.class, handshakeCompletionEvent.cause().getClass());
        } finally {
            ch.finishAndReleaseAll();
            releaseAll(context);
        }
    }
}
