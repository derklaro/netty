/*
 * Copyright 2021 The Netty Project
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

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.pkitesting.CertificateBuilder;
import io.netty5.pkitesting.X509Bundle;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static io.netty5.buffer.BufferUtil.writeAscii;
import static io.netty5.buffer.DefaultBufferAllocators.offHeapAllocator;
import static io.netty5.handler.codec.ByteToMessageDecoder.MERGE_CUMULATOR;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class CloseNotifyTest {

    private static final BufferAllocator ALLOC = offHeapAllocator();
    private static final Object INACTIVE = new Object() {
        @Override
        public String toString() {
            return "INACTIVE";
        }
    };

    static Collection<Object[]> data() {
        return asList(new Object[][] {
                { SslProvider.JDK, SslProtocols.TLS_v1_2 },
                { SslProvider.JDK, SslProtocols.TLS_v1_3 },
                { SslProvider.OPENSSL, SslProtocols.TLS_v1_2 },
                { SslProvider.OPENSSL, SslProtocols.TLS_v1_3 },
        });
    }

    @ParameterizedTest(name = "{index}: provider={0}, protocol={1}")
    @Timeout(30)
    @MethodSource("data")
    public void eventsOrder(SslProvider provider, String protocol) throws Exception {
        assumeTrue(provider != SslProvider.OPENSSL || OpenSsl.isAvailable(), "OpenSSL is not available");

        if (SslProtocols.TLS_v1_3.equals(protocol)) {
            // Ensure we support TLSv1.3
            assumeTrue(SslProvider.isTlsv13Supported(provider));
        }
        BlockingQueue<Object> clientEventQueue = new LinkedBlockingQueue<Object>();
        BlockingQueue<Object> serverEventQueue = new LinkedBlockingQueue<Object>();

        EmbeddedChannel clientChannel = initChannel(provider, protocol, true, clientEventQueue);
        EmbeddedChannel serverChannel = initChannel(provider, protocol, false, serverEventQueue);

        try {
            // handshake:
            forwardData(clientChannel, serverChannel);
            forwardData(serverChannel, clientChannel);
            forwardData(clientChannel, serverChannel);
            forwardData(serverChannel, clientChannel);
            assertThat(clientEventQueue.poll(), instanceOf(SslHandshakeCompletionEvent.class));
            assertThat(serverEventQueue.poll(), instanceOf(SslHandshakeCompletionEvent.class));
            assertThat(handshakenProtocol(clientChannel), equalTo(protocol));

            // send data:
            clientChannel.writeOutbound(writeAscii(ALLOC, "request_msg"));
            forwardData(clientChannel, serverChannel);
            assertThat(serverEventQueue.poll(), equalTo("request_msg"));

            // respond with data and close_notify:
            serverChannel.writeOutbound(writeAscii(ALLOC, "response_msg"));
            assertThat(serverChannel.finish(), is(true));
            assertThat(serverEventQueue.poll(), instanceOf(SslCloseCompletionEvent.class));
            assertThat(clientEventQueue, empty());

            // consume server response with close_notify:
            forwardAllWithCloseNotify(serverChannel, clientChannel);
            assertThat(clientEventQueue.poll(), equalTo("response_msg"));
            assertThat(clientEventQueue.poll(), instanceOf(SslCloseCompletionEvent.class));

            // make sure client automatically responds with close_notify:
            if (!jdkTls13(provider, protocol)) {
                // JDK impl of TLSv1.3 does not automatically generate "close_notify" in response to the received
                // "close_notify" alert. This is a legit behavior according to the spec:
                // https://tools.ietf.org/html/rfc8446#section-6.1. Handle it differently:
                assertCloseNotify(clientChannel.readOutbound());
            }
        } finally {
            try {
                clientChannel.finish();
            } finally {
                serverChannel.finish();
            }
        }

        if (jdkTls13(provider, protocol)) {
            assertCloseNotify(clientChannel.readOutbound());
        } else {
            discardEmptyOutboundBuffers(clientChannel);
        }

        assertThat(clientEventQueue.poll(), is(INACTIVE));
        assertThat(clientEventQueue, empty());
        assertThat(serverEventQueue.poll(), is(INACTIVE));
        assertThat(serverEventQueue, empty());

        assertThat(clientChannel.releaseInbound(), is(false));
        assertThat(clientChannel.releaseOutbound(), is(false));
        assertThat(serverChannel.releaseInbound(), is(false));
        assertThat(serverChannel.releaseOutbound(), is(false));
    }

    private static boolean jdkTls13(SslProvider provider, String protocol) {
        return provider == SslProvider.JDK && SslProtocols.TLS_v1_3.equals(protocol);
    }

    private static EmbeddedChannel initChannel(SslProvider provider, String protocol, final boolean useClientMode,
            final BlockingQueue<Object> eventQueue) throws Exception {

        X509Bundle cert = new CertificateBuilder()
                .subject("cn=localhost")
                .setIsCertificateAuthority(true)
                .buildSelfSigned();
        final SslContext sslContext = (useClientMode
                ? SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                : SslContextBuilder.forServer(cert.getKeyPair().getPrivate(), cert.getCertificatePath()))
                 .sslProvider(provider)
                 .protocols(protocol)
                .build();
        SimpleChannelInboundHandler<Buffer> eventHandler = new SimpleChannelInboundHandler<>() {
            @Override
            protected void messageReceived(ChannelHandlerContext ctx, Buffer msg) {
                eventQueue.add(msg.toString(US_ASCII));
            }

            @Override
            public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
                eventQueue.add(evt);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                eventQueue.add(INACTIVE);
                super.channelInactive(ctx);
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel();
        // use sslContext.newHandler(ALLOC) instead of new SslHandler(sslContext.newEngine(ALLOC)) to create
        // non-JDK compatible OpenSSL engine that can process partial packets:
        channel.pipeline().addLast(sslContext.newHandler(ALLOC));
        channel.pipeline().addLast(eventHandler);
        return channel;
    }

    private static void forwardData(EmbeddedChannel from, EmbeddedChannel to) {
        Buffer in;
        while ((in = from.readOutbound()) != null) {
            to.writeInbound(in);
        }
    }

    private static void forwardAllWithCloseNotify(EmbeddedChannel from, EmbeddedChannel to) {
        Buffer cumulation = ALLOC.allocate(0);
        Buffer in, closeNotify = null;
        while ((in = from.readOutbound()) != null) {
            if (closeNotify != null) {
                closeNotify.close();
            }
            closeNotify = in.copy();
            cumulation = MERGE_CUMULATOR.cumulate(ALLOC, cumulation, in);
        }
        assertCloseNotify(closeNotify);
        to.writeInbound(cumulation);
    }

    private static String handshakenProtocol(EmbeddedChannel channel) {
        SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
        SSLSession session = sslHandler.engine().getSession();
        return session.getProtocol();
    }

    private static void discardEmptyOutboundBuffers(EmbeddedChannel channel) {
        Queue<Object> outbound = channel.outboundMessages();
        while (outbound.peek() instanceof Buffer) {
            Buffer buf = (Buffer) outbound.peek();
            if (buf.readableBytes() == 0) {
                buf.close();
                outbound.poll();
            } else {
                break;
            }
        }
    }

    static void assertCloseNotify(@Nullable Buffer closeNotify) {
        assertThat(closeNotify, notNullValue());
        try (closeNotify) {
            assertThat("Doesn't match expected length of close_notify alert",
                    closeNotify.readableBytes(), greaterThanOrEqualTo(7));
        }
    }
}
