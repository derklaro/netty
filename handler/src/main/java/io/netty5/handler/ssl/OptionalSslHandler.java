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
package io.netty5.handler.ssl;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.util.internal.SilentDispose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static java.util.Objects.requireNonNull;

/**
 * {@link OptionalSslHandler} is a utility decoder to support both SSL and non-SSL handlers
 * based on the first message received.
 */
public class OptionalSslHandler extends ByteToMessageDecoder {
    private static final Logger logger = LoggerFactory.getLogger(OptionalSslHandler.class);

    private final SslContext sslContext;

    public OptionalSslHandler(SslContext sslContext) {
        this.sslContext = requireNonNull(sslContext, "sslContext");
    }

    @Override
    protected void decode(ChannelHandlerContext context, Buffer in) throws Exception {
        if (in.readableBytes() < SslUtils.SSL_RECORD_HEADER_LENGTH) {
            return;
        }
        if (SslHandler.isEncrypted(in, false)) {
            handleSsl(context);
        } else {
            handleNonSsl(context);
        }
    }

    private void handleSsl(ChannelHandlerContext context) {
        SslHandler sslHandler = null;
        try {
            sslHandler = newSslHandler(context, sslContext);
            context.pipeline().replace(this, newSslHandlerName(), sslHandler);
            sslHandler = null;
        } finally {
            // Since the SslHandler was not inserted into the pipeline the ownership of the SSLEngine was not
            // transferred to the SslHandler.
            if (sslHandler != null) {
                SilentDispose.dispose(sslHandler.engine(), logger);
            }
        }
    }

    private void handleNonSsl(ChannelHandlerContext context) {
        ChannelHandler handler = newNonSslHandler(context);
        if (handler != null) {
            context.pipeline().replace(this, newNonSslHandlerName(), handler);
        } else {
            context.pipeline().remove(this);
        }
    }

    /**
     * Optionally specify the SSL handler name, this method may return {@code null}.
     * @return the name of the SSL handler.
     */
    protected String newSslHandlerName() {
        return null;
    }

    /**
     * Override to configure the SslHandler eg. {@link SSLParameters#setEndpointIdentificationAlgorithm(String)}.
     * The hostname and port is not known by this method so servers may want to override this method and use the
     * {@link SslContext#newHandler(BufferAllocator, String, int)} variant.
     *
     * @param context the {@link ChannelHandlerContext} to use.
     * @param sslContext the {@link SSLContext} to use.
     * @return the {@link SslHandler} which will replace the {@link OptionalSslHandler} in the pipeline if the
     * traffic is SSL.
     */
    protected SslHandler newSslHandler(ChannelHandlerContext context, SslContext sslContext) {
        return sslContext.newHandler(context.bufferAllocator());
    }

    /**
     * Optionally specify the non-SSL handler name, this method may return {@code null}.
     * @return the name of the non-SSL handler.
     */
    protected String newNonSslHandlerName() {
        return null;
    }

    /**
     * Override to configure the ChannelHandler.
     * @param context the {@link ChannelHandlerContext} to use.
     * @return the {@link ChannelHandler} which will replace the {@link OptionalSslHandler} in the pipeline
     * or {@code null} to simply remove the {@link OptionalSslHandler} if the traffic is non-SSL.
     */
    protected ChannelHandler newNonSslHandler(ChannelHandlerContext context) {
        return null;
    }
}
