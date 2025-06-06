/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty5.handler.codec.http2;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.base64.Base64;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpServerUpgradeHandler;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.util.internal.UnstableApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static io.netty5.handler.codec.base64.Base64Dialect.URL_SAFE;
import static io.netty5.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty5.handler.codec.http2.Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER;
import static io.netty5.handler.codec.http2.Http2CodecUtil.writeFrameHeader;
import static io.netty5.handler.codec.http2.Http2FrameTypes.SETTINGS;

/**
 * Server-side codec for performing a cleartext upgrade from HTTP/1.x to HTTP/2.
 */
@UnstableApi
public class Http2ServerUpgradeCodec implements HttpServerUpgradeHandler.UpgradeCodec {

    private static final Logger logger = LoggerFactory.getLogger(Http2ServerUpgradeCodec.class);
    private static final List<CharSequence> REQUIRED_UPGRADE_HEADERS =
            Collections.singletonList(HTTP_UPGRADE_SETTINGS_HEADER);
    private static final ChannelHandler[] EMPTY_HANDLERS = new ChannelHandler[0];

    private final String handlerName;
    private final Http2ConnectionHandler connectionHandler;
    private final ChannelHandler[] handlers;
    private final Http2FrameReader frameReader;

    private Http2Settings settings;

    /**
     * Creates the codec using a default name for the connection handler when adding to the
     * pipeline.
     *
     * @param connectionHandler the HTTP/2 connection handler
     */
    public Http2ServerUpgradeCodec(Http2ConnectionHandler connectionHandler) {
        this(null, connectionHandler, EMPTY_HANDLERS);
    }

    /**
     * Creates the codec providing an upgrade to the given handler for HTTP/2.
     *
     * @param handlerName the name of the HTTP/2 connection handler to be used in the pipeline,
     *                    or {@code null} to auto-generate the name
     * @param connectionHandler the HTTP/2 connection handler
     */
    public Http2ServerUpgradeCodec(String handlerName, Http2ConnectionHandler connectionHandler) {
        this(handlerName, connectionHandler, EMPTY_HANDLERS);
    }

    /**
     * Creates the codec using a default name for the connection handler when adding to the
     * pipeline.
     *
     * @param http2Codec the HTTP/2 frame handler.
     * @param handlers the handlers that will handle the {@link Http2Frame}s.
     */
    public Http2ServerUpgradeCodec(Http2FrameCodec http2Codec, ChannelHandler... handlers) {
        this(null, http2Codec, handlers);
    }

    private Http2ServerUpgradeCodec(String handlerName, Http2ConnectionHandler connectionHandler,
            ChannelHandler... handlers) {
        this.handlerName = handlerName;
        this.connectionHandler = connectionHandler;
        this.handlers = handlers;
        frameReader = new DefaultHttp2FrameReader();
    }

    @Override
    public Collection<CharSequence> requiredUpgradeHeaders() {
        return REQUIRED_UPGRADE_HEADERS;
    }

    @Override
    public boolean prepareUpgradeResponse(ChannelHandlerContext ctx, FullHttpRequest upgradeRequest,
                                          HttpHeaders headers) {
        try {
            // Decode the HTTP2-Settings header and set the settings on the handler to make
            // sure everything is fine with the request.
            Iterator<CharSequence> upgradeHeaders = upgradeRequest.headers().valuesIterator(
                    HTTP_UPGRADE_SETTINGS_HEADER);
            if (upgradeHeaders.hasNext()) {
                CharSequence settingHeader = upgradeHeaders.next();
                if (!upgradeHeaders.hasNext()) {
                    // Everything looks good.
                    settings = decodeSettingsHeader(ctx, settingHeader);
                    return true;
                }
            }
            throw new IllegalArgumentException("There must be 1 and only 1 "
                                               + HTTP_UPGRADE_SETTINGS_HEADER + " header.");
        } catch (Throwable cause) {
            logger.info("{} Error during upgrade to HTTP/2", ctx.channel(), cause);
            return false;
        }
    }

    @Override
    public void upgradeTo(final ChannelHandlerContext ctx, FullHttpRequest upgradeRequest) {
        try {
            // Add the HTTP/2 connection handler to the pipeline immediately following the current handler.
            ctx.pipeline().addAfter(ctx.name(), handlerName, connectionHandler);

            // Add also all extra handlers as these may handle events / messages produced by the connectionHandler.
            // See https://github.com/netty/netty/issues/9314
            if (handlers != null) {
                final String name = ctx.pipeline().context(connectionHandler).name();
                for (int i = handlers.length - 1; i >= 0; i--) {
                    ctx.pipeline().addAfter(name, null, handlers[i]);
                }
            }
            connectionHandler.onHttpServerUpgrade(settings);
        } catch (Http2Exception e) {
            ctx.fireChannelExceptionCaught(e);
            ctx.close();
        }
    }

    /**
     * Decodes the settings header and returns a {@link Http2Settings} object.
     */
    private Http2Settings decodeSettingsHeader(ChannelHandlerContext ctx, CharSequence settingsHeader)
            throws Http2Exception {
        try (Buffer header = ctx.bufferAllocator().allocate(settingsHeader.length())) {
            header.writeCharSequence(settingsHeader, StandardCharsets.UTF_8);
            // Decode the SETTINGS payload.
            try (Buffer payload = Base64.decode(header, URL_SAFE)) {
                // Create an HTTP/2 frame for the settings.
                Buffer frame = createSettingsFrame(ctx, payload);

                // Decode the SETTINGS frame and return the settings object.
                return decodeSettings(ctx, frame);
            }
        }
    }

    /**
     * Decodes the settings frame and returns the settings.
     */
    private Http2Settings decodeSettings(ChannelHandlerContext ctx, Buffer frame) throws Http2Exception {
        try (frame) {
            final Http2Settings decodedSettings = new Http2Settings();
            frameReader.readFrame(ctx, frame, new Http2FrameAdapter() {
                @Override
                public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
                    decodedSettings.copyFrom(settings);
                }
            });
            return decodedSettings;
        }
    }

    /**
     * Creates an HTTP2-Settings header with the given payload. The payload buffer is released.
     */
    private static Buffer createSettingsFrame(ChannelHandlerContext ctx, Buffer payload) {
        Buffer frame = ctx.bufferAllocator().allocate(FRAME_HEADER_LENGTH + payload.readableBytes());
        writeFrameHeader(frame, payload.readableBytes(), SETTINGS, new Http2Flags(), 0);
        frame.writeBytes(payload);
        return frame;
    }
}
