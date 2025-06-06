/*
 * Copyright 2019 The Netty Project
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
// (BSD License: https://www.opensource.org/licenses/bsd-license)
//
// Copyright (c) 2011, Joe Walnes and contributors
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or
// without modification, are permitted provided that the
// following conditions are met:
//
// * Redistributions of source code must retain the above
// copyright notice, this list of conditions and the
// following disclaimer.
//
// * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the
// following disclaimer in the documentation and/or other
// materials provided with the distribution.
//
// * Neither the name of the Webbit nor the names of
// its contributors may be used to endorse or promote products
// derived from this software without specific prior written
// permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
// CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
// GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
// OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package io.netty5.handler.codec.http.websocketx;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.TooLongFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Decodes a web socket frame from wire protocol version 13 format. V13 is essentially the same as V8.
 */
public class WebSocket13FrameDecoder extends ByteToMessageDecoder implements WebSocketFrameDecoder {

    private static final Logger logger = LoggerFactory.getLogger(WebSocket13FrameDecoder.class);
    private static final byte OPCODE_CONT = 0x0;
    private static final byte OPCODE_TEXT = 0x1;
    private static final byte OPCODE_BINARY = 0x2;
    private static final byte OPCODE_CLOSE = 0x8;
    private static final byte OPCODE_PING = 0x9;
    private static final byte OPCODE_PONG = 0xA;
    private final WebSocketDecoderConfig config;
    private int fragmentedFramesCount;
    private boolean frameFinalFlag;
    private boolean frameMasked;
    private int frameRsv;
    private int frameOpcode;
    private long framePayloadLength;
    private int mask;
    private int framePayloadLen1;
    private boolean receivedClosingHandshake;
    private State state = State.READING_FIRST;

    /**
     * Constructor
     *
     * @param expectMaskedFrames
     *            Web socket servers must set this to true processed incoming masked payload. Client implementations
     *            must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload. Setting this to an appropriate value for you application
     *            helps check for denial of services attacks.
     */
    public WebSocket13FrameDecoder(boolean expectMaskedFrames, boolean allowExtensions, int maxFramePayloadLength) {
        this(expectMaskedFrames, allowExtensions, maxFramePayloadLength, false);
    }

    /**
     * Constructor
     *
     * @param expectMaskedFrames
     *            Web socket servers must set this to true processed incoming masked payload. Client implementations
     *            must set this to false.
     * @param allowExtensions
     *            Flag to allow reserved extension bits to be used or not
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload. Setting this to an appropriate value for you application
     *            helps check for denial of services attacks.
     * @param allowMaskMismatch
     *            When set to true, frames which are not masked properly according to the standard will still be
     *            accepted.
     */
    public WebSocket13FrameDecoder(boolean expectMaskedFrames, boolean allowExtensions, int maxFramePayloadLength,
                                   boolean allowMaskMismatch) {
        this(WebSocketDecoderConfig.newBuilder()
            .expectMaskedFrames(expectMaskedFrames)
            .allowExtensions(allowExtensions)
            .maxFramePayloadLength(maxFramePayloadLength)
            .allowMaskMismatch(allowMaskMismatch)
            .build());
    }

    /**
     * Constructor
     *
     * @param decoderConfig
     *            Frames decoder configuration.
     */
    public WebSocket13FrameDecoder(WebSocketDecoderConfig decoderConfig) {
        config = Objects.requireNonNull(decoderConfig, "decoderConfig");
    }

    private static int toFrameLength(long length) {
        if (length > Integer.MAX_VALUE) {
            throw new TooLongFrameException("frame length exceeds " + Integer.MAX_VALUE + ": " + length);
        } else {
            return (int) length;
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Buffer in) throws Exception {
        // Discard all data received if closing handshake was received before.
        if (receivedClosingHandshake) {
            in.skipReadableBytes(actualReadableBytes());
            return;
        }

        switch (state) {
        case READING_FIRST: {
            if (in.readableBytes() == 0) {
                return;
            }

            framePayloadLength = 0;

            // FIN, RSV, OPCODE
            byte b = in.readByte();
            frameFinalFlag = (b & 0x80) != 0;
            frameRsv = (b & 0x70) >> 4;
            frameOpcode = b & 0x0F;

            if (logger.isTraceEnabled()) {
                logger.trace("Decoding WebSocket Frame opCode={}", frameOpcode);
            }

            state = State.READING_SECOND;
        }
        case READING_SECOND: {
            if (in.readableBytes() == 0) {
                return;
            }
            // MASK, PAYLOAD LEN 1
            byte b = in.readByte();
            frameMasked = (b & 0x80) != 0;
            framePayloadLen1 = b & 0x7F;

            if (frameRsv != 0 && !config.allowExtensions()) {
                protocolViolation(ctx, in, "RSV != 0 and no extension negotiated, RSV:" + frameRsv);
                return;
            }

            if (!config.allowMaskMismatch() && config.expectMaskedFrames() != frameMasked) {
                protocolViolation(ctx, in, "received a frame that is not masked as expected");
                return;
            }

            if (frameOpcode > 7) { // control frame (have MSB in opcode set)

                // control frames MUST NOT be fragmented
                if (!frameFinalFlag) {
                    protocolViolation(ctx, in, "fragmented control frame");
                    return;
                }

                // control frames MUST have payload 125 octets or less
                if (framePayloadLen1 > 125) {
                    protocolViolation(ctx, in, "control frame with payload length > 125 octets");
                    return;
                }

                // check for reserved control frame opcodes
                if (!(frameOpcode == OPCODE_CLOSE || frameOpcode == OPCODE_PING
                      || frameOpcode == OPCODE_PONG)) {
                    protocolViolation(ctx, in, "control frame using reserved opcode " + frameOpcode);
                    return;
                }

                // close frame : if there is a body, the first two bytes of the
                // body MUST be a 2-byte unsigned integer (in network byte
                // order) representing a getStatus code
                if (frameOpcode == 8 && framePayloadLen1 == 1) {
                    protocolViolation(ctx, in, "received close control frame with payload len 1");
                    return;
                }
            } else { // data frame
                // check for reserved data frame opcodes
                if (!(frameOpcode == OPCODE_CONT || frameOpcode == OPCODE_TEXT
                      || frameOpcode == OPCODE_BINARY)) {
                    protocolViolation(ctx, in, "data frame using reserved opcode " + frameOpcode);
                    return;
                }

                // check opcode vs message fragmentation state 1/2
                if (fragmentedFramesCount == 0 && frameOpcode == OPCODE_CONT) {
                    protocolViolation(ctx, in, "received continuation data frame outside fragmented message");
                    return;
                }

                // check opcode vs message fragmentation state 2/2
                if (fragmentedFramesCount != 0 && frameOpcode != OPCODE_CONT) {
                    protocolViolation(ctx, in,
                                      "received non-continuation data frame while inside fragmented message");
                    return;
                }
            }

            state = State.READING_SIZE;
        }
        case READING_SIZE: {
            // Read frame payload length
            if (framePayloadLen1 == 126) {
                if (in.readableBytes() < 2) {
                    return;
                }
                framePayloadLength = in.readUnsignedShort();
                if (framePayloadLength < 126) {
                    protocolViolation(ctx, in, "invalid data frame length (not using minimal length encoding)");
                    return;
                }
            } else if (framePayloadLen1 == 127) {
                if (in.readableBytes() < 8) {
                    return;
                }
                framePayloadLength = in.readLong();
                // TODO: check if it's bigger than 0x7FFFFFFFFFFFFFFF, Maybe
                // just check if it's negative?

                if (framePayloadLength < 65536) {
                    protocolViolation(ctx, in, "invalid data frame length (not using minimal length encoding)");
                    return;
                }
            } else {
                framePayloadLength = framePayloadLen1;
            }

            if (framePayloadLength > config.maxFramePayloadLength()) {
                protocolViolation(ctx, in, WebSocketCloseStatus.MESSAGE_TOO_BIG,
                                  "Max frame length of " + config.maxFramePayloadLength() + " has been exceeded.");
                return;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("Decoding WebSocket Frame length={}", framePayloadLength);
            }

            state = State.MASKING_KEY;
        }
        case MASKING_KEY: {
            if (frameMasked) {
                if (in.readableBytes() < 4) {
                    return;
                }
                mask = in.readInt();
            }
            state = State.PAYLOAD;
        }
        case PAYLOAD: {
            if (in.readableBytes() < framePayloadLength) {
                return;
            }

            Buffer payloadBuffer = null;
            try {
                payloadBuffer = in.readSplit(toFrameLength(framePayloadLength));

                // Now we have all the data, the next checkpoint must be the next
                // frame
                state = State.READING_FIRST;

                // Unmask data if needed
                if (frameMasked) {
                    unmask(payloadBuffer);
                }

                // Processing ping/pong/close frames because they cannot be
                // fragmented
                if (frameOpcode == OPCODE_PING) {
                    WebSocketFrame frame = new PingWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                }
                if (frameOpcode == OPCODE_PONG) {
                    WebSocketFrame frame = new PongWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                }
                if (frameOpcode == OPCODE_CLOSE) {
                    receivedClosingHandshake = true;
                    checkCloseFrameBody(ctx, payloadBuffer);
                    WebSocketFrame frame = new CloseWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                }

                // Processing for possible fragmented messages for text and binary
                // frames
                if (frameFinalFlag) {
                    // Final frame of the sequence. Apparently ping frames are
                    // allowed in the middle of a fragmented message
                    fragmentedFramesCount = 0;
                } else {
                    // Increment counter
                    fragmentedFramesCount++;
                }

                // Return the frame
                if (frameOpcode == OPCODE_TEXT) {
                    WebSocketFrame frame = new TextWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                } else if (frameOpcode == OPCODE_BINARY) {
                    WebSocketFrame frame = new BinaryWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                } else if (frameOpcode == OPCODE_CONT) {
                    WebSocketFrame frame = new ContinuationWebSocketFrame(frameFinalFlag, frameRsv, payloadBuffer);
                    payloadBuffer = null;
                    ctx.fireChannelRead(frame);
                    return;
                } else {
                    throw new UnsupportedOperationException("Cannot decode web socket frame with opcode: "
                                                            + frameOpcode);
                }
            } finally {
                if (payloadBuffer != null) {
                    payloadBuffer.close();
                }
            }
        }
        case CORRUPT: {
            if (in.readableBytes() > 0) {
                // If we don't keep reading Netty will throw an exception saying
                // we can't return null if no bytes read and state not changed.
                in.readByte();
            }
            return;
        }
        default:
            throw new Error("Shouldn't reach here.");
        }
    }

    private void unmask(Buffer frame) {
        int base = frame.readerOffset();
        int len = frame.readableBytes();
        int index = 0;

        int intMask = mask;
        for (; index + 3 < len; index += Integer.BYTES) {
            int off = base + index;
            frame.setInt(off, frame.getInt(off) ^ intMask);
        }
        int maskOffset = 0;
        for (; index < len; index++) {
            int off = base + index;
            frame.setByte(off, (byte) (frame.getByte(off) ^ WebSocketUtil.byteAtIndex(intMask, maskOffset++ & 3)));
        }
    }

    private void protocolViolation(ChannelHandlerContext ctx, Buffer in, String reason) {
        protocolViolation(ctx, in, WebSocketCloseStatus.PROTOCOL_ERROR, reason);
    }

    private void protocolViolation(ChannelHandlerContext ctx, Buffer in, WebSocketCloseStatus status, String reason) {
        protocolViolation(ctx, in, new CorruptedWebSocketFrameException(status, reason));
    }

    private void protocolViolation(ChannelHandlerContext ctx, Buffer in, CorruptedWebSocketFrameException ex) {
        state = State.CORRUPT;
        int readableBytes = in.readableBytes();
        if (readableBytes > 0) {
            // Fix for memory leak, caused by ByteToMessageDecoder#channelRead:
            // buffer 'cumulation' is released ONLY when no more readable bytes available.
            in.skipReadableBytes(readableBytes);
        }
        if (ctx.channel().isActive() && config.closeOnProtocolViolation()) {
            Object closeMessage;
            if (receivedClosingHandshake) {
                closeMessage = ctx.bufferAllocator().allocate(0);
            } else {
                WebSocketCloseStatus closeStatus = ex.closeStatus();
                String reasonText = ex.getMessage();
                if (reasonText == null) {
                    reasonText = closeStatus.reasonText();
                }
                closeMessage = new CloseWebSocketFrame(ctx.bufferAllocator(), closeStatus, reasonText);
            }
            ctx.writeAndFlush(closeMessage).addListener(ctx, ChannelFutureListeners.CLOSE);
        }
        throw ex;
    }

    /** */
    protected void checkCloseFrameBody(ChannelHandlerContext ctx, Buffer buffer) {
        if (buffer == null || buffer.readableBytes() <= 0) {
            return;
        }
        if (buffer.readableBytes() == 1) {
            protocolViolation(ctx, buffer, WebSocketCloseStatus.INVALID_PAYLOAD_DATA, "Invalid close frame body");
        }

        // Save reader offset.
        int offset = buffer.readerOffset();
        try {
            // Must have 2 byte integer within the valid range.
            int statusCode = buffer.readShort();
            if (!WebSocketCloseStatus.isValidStatusCode(statusCode)) {
                protocolViolation(ctx, buffer, "Invalid close frame getStatus code: " + statusCode);
            }

            // May have UTF-8 message.
            if (buffer.readableBytes() > 0) {
                try {
                    new Utf8Validator().check(buffer);
                } catch (CorruptedWebSocketFrameException ex) {
                    protocolViolation(ctx, buffer, ex);
                }
            }
        } finally {
            // Restore reader offset.
            buffer.readerOffset(offset);
        }
    }

    enum State {
        READING_FIRST,
        READING_SECOND,
        READING_SIZE,
        MASKING_KEY,
        PAYLOAD,
        CORRUPT
    }
}
