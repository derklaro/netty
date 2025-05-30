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

package io.netty5.testsuite.http2;

import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.handler.codec.http.HttpMessage;
import io.netty5.handler.codec.http.HttpObjectAggregator;
import io.netty5.handler.codec.http.HttpServerCodec;
import io.netty5.handler.codec.http.HttpServerUpgradeHandler;
import io.netty5.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;
import io.netty5.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty5.handler.codec.http2.Http2CodecUtil;
import io.netty5.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty5.util.AsciiString;
import io.netty5.util.ReferenceCountUtil;

import static io.netty5.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * Sets up the Netty pipeline for the example server. Depending on the endpoint config, sets up the
 * pipeline for cleartext HTTP upgrade to HTTP/2.
 */
public class Http2ServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final UpgradeCodecFactory upgradeCodecFactory = protocol -> {
        if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
            return new Http2ServerUpgradeCodec(new HelloWorldHttp2HandlerBuilder().build());
        } else {
            return null;
        }
    };

    private final int maxHttpContentLength;

    Http2ServerInitializer() {
        this(16 * 1024);
    }

    Http2ServerInitializer(int maxHttpContentLength) {
        checkPositiveOrZero(maxHttpContentLength, "maxHttpContentLength");
        this.maxHttpContentLength = maxHttpContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        configureClearText(ch);
    }

    /**
     * Configure the pipeline for a cleartext upgrade from HTTP to HTTP/2.0
     */
    private void configureClearText(SocketChannel ch) {
        final ChannelPipeline p = ch.pipeline();
        final HttpServerCodec sourceCodec = new HttpServerCodec();
        final HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory);
        final CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler =
                new CleartextHttp2ServerUpgradeHandler(sourceCodec, upgradeHandler,
                                                       new HelloWorldHttp2HandlerBuilder().build());

        p.addLast(cleartextHttp2ServerUpgradeHandler);
        p.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
            @Override
            protected void messageReceived(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
                // If this handler is hit then no upgrade has been attempted and the client is just talking HTTP.
                System.err.println("Directly talking: " + msg.protocolVersion() + " (no upgrade was attempted)");
                ChannelPipeline pipeline = ctx.pipeline();
                ChannelHandlerContext thisCtx = pipeline.context(this);
                pipeline.addAfter(thisCtx.name(), null, new HelloWorldHttp1Handler("Direct. No Upgrade Attempted."));
                pipeline.addAfter(thisCtx.name(), null, new HttpObjectAggregator(maxHttpContentLength));
                ctx.fireChannelRead(ReferenceCountUtil.retain(msg));
                pipeline.remove(this);
            }
        });

        p.addLast(new UserEventLogger());
    }

    /**
     * Class that logs any User Events triggered on this channel.
     */
    private static class UserEventLogger implements ChannelHandler {
        @Override
        public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
            System.out.println("User Event Triggered: " + evt);
            ctx.fireChannelInboundEvent(evt);
        }
    }
}
