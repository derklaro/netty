/*
 * Copyright 2016 The Netty Project
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
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.WriteBufferWaterMark;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.UnsupportedMessageTypeException;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpScheme;
import io.netty5.handler.codec.http2.Http2Exception.StreamException;
import io.netty5.handler.codec.http2.headers.Http2Headers;
import io.netty5.util.AsciiString;
import io.netty5.util.AttributeKey;
import io.netty5.util.Resource;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.ImmediateEventExecutor;
import io.netty5.util.concurrent.Promise;
import org.hamcrest.CoreMatchers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.netty5.buffer.DefaultBufferAllocators.onHeapAllocator;
import static io.netty5.handler.codec.http2.Http2TestUtil.anyHttp2Settings;
import static io.netty5.handler.codec.http2.Http2TestUtil.assertEqualsAndRelease;
import static io.netty5.handler.codec.http2.Http2TestUtil.bb;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Http2MultiplexTest {
    private final Http2Headers request = Http2Headers.newHeaders()
            .method(HttpMethod.GET.asciiName()).scheme(HttpScheme.HTTPS.name())
            .authority(new AsciiString("example.org")).path(new AsciiString("/foo"));

    private EmbeddedChannel parentChannel;
    private Http2FrameWriter frameWriter;
    private Http2FrameInboundWriter frameInboundWriter;
    private TestChannelInitializer childChannelInitializer;
    private Http2FrameCodec codec;

    private static final int initialRemoteStreamWindow = 1024;

    private Http2FrameCodec newCodec(Http2FrameWriter frameWriter) {
        return new Http2FrameCodecBuilder(true).frameWriter(frameWriter).build();
    }

    private ChannelHandler newMultiplexer(TestChannelInitializer childChannelInitializer) {
        return new Http2MultiplexHandler(childChannelInitializer, null);
    }

    @BeforeEach
    public void setUp() throws Exception {
        childChannelInitializer = new TestChannelInitializer();
        parentChannel = new EmbeddedChannel();
        frameInboundWriter = new Http2FrameInboundWriter(parentChannel);
        parentChannel.connect(new InetSocketAddress(0));
        frameWriter = Http2TestUtil.mockedFrameWriter();
        codec = newCodec(frameWriter);
        parentChannel.pipeline().addLast(codec);
        ChannelHandler multiplexer = newMultiplexer(childChannelInitializer);
        parentChannel.pipeline().addLast(multiplexer);

        parentChannel.pipeline().fireChannelActive();

        parentChannel.writeInbound(Http2CodecUtil.connectionPrefaceBuffer());

        Http2Settings settings = new Http2Settings().initialWindowSize(initialRemoteStreamWindow);
        frameInboundWriter.writeInboundSettings(settings);

        verify(frameWriter).writeSettingsAck(any(ChannelHandlerContext.class));

        frameInboundWriter.writeInboundSettingsAck();

        Http2SettingsFrame settingsFrame = parentChannel.readInbound();
        assertNotNull(settingsFrame);
        Http2SettingsAckFrame settingsAckFrame = parentChannel.readInbound();
        assertNotNull(settingsAckFrame);

        // Handshake
        verify(frameWriter).writeSettings(any(ChannelHandlerContext.class),
                anyHttp2Settings());
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (childChannelInitializer.handler instanceof LastInboundHandler) {
            ((LastInboundHandler) childChannelInitializer.handler).finishAndReleaseAll();
        }
        frameInboundWriter.close();
        parentChannel.finishAndReleaseAll();
        codec = null;
    }

    // TODO(buchgr): Flush from child channel
    // TODO(buchgr): ChildChannel.childReadComplete()
    // TODO(buchgr): GOAWAY Logic

    @Test
    public void writeUnknownFrame() throws Exception {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
                ctx.writeAndFlush(new DefaultHttp2UnknownFrame((byte) 99, new Http2Flags()));
                ctx.fireChannelActive();
            }
        });
        assertTrue(childChannel.isActive());

        verify(frameWriter).writeFrame(any(ChannelHandlerContext.class), eq((byte) 99), eqStreamId(childChannel),
                any(Http2Flags.class), any(Buffer.class));
    }

    private Http2StreamChannel newInboundStream(int streamId, boolean endStream, final ChannelHandler childHandler)
            throws Exception {
        return newInboundStream(streamId, endStream, null, childHandler);
    }

    private Http2StreamChannel newInboundStream(
            int streamId, boolean endStream, AtomicInteger maxReads, final ChannelHandler childHandler)
            throws Exception {
        final AtomicReference<Http2StreamChannel> streamChannelRef = new AtomicReference<Http2StreamChannel>();
        childChannelInitializer.maxReads = maxReads;
        childChannelInitializer.handler = new ChannelHandler() {
            @Override
            public void channelRegistered(ChannelHandlerContext ctx) {
                assertNull(streamChannelRef.get());
                streamChannelRef.set((Http2StreamChannel) ctx.channel());
                ctx.pipeline().addLast(childHandler);
                ctx.fireChannelRegistered();
            }
        };

        frameInboundWriter.writeInboundHeaders(streamId, request, 0, endStream);
        Http2StreamChannel channel = streamChannelRef.get();
        assertEquals(streamId, channel.stream().id());
        return channel;
    }

    @Test
    public void readUnkownFrame() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, true, handler);
        frameInboundWriter.writeInboundFrame((byte) 99, channel.stream().id(), new Http2Flags(),
                                             onHeapAllocator().allocate(0));

        // header frame and unknown frame
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 2);

        Channel childChannel = newOutboundStream(new ChannelHandler() { });
        assertTrue(childChannel.isActive());
    }

    @Test
    public void shutdownDone() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, false, handler);
        assertFalse(channel.isShutdown(ChannelShutdownDirection.Inbound));
        assertFalse(handler.isInboundShutdown());
        assertFalse(channel.isShutdown(ChannelShutdownDirection.Outbound));
        assertFalse(handler.isOutboundShutdown());

        frameInboundWriter.writeInboundData(channel.stream().id(), onHeapAllocator().allocate(0), 0, true);
        assertTrue(channel.isShutdown(ChannelShutdownDirection.Inbound));
        assertTrue(handler.isInboundShutdown());

        assertFalse(channel.isShutdown(ChannelShutdownDirection.Outbound));
        assertFalse(handler.isOutboundShutdown());

        // header frame and data frame
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 2);

        channel.write(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
        assertFalse(channel.isShutdown(ChannelShutdownDirection.Outbound));
        assertFalse(handler.isOutboundShutdown());

        channel.write(new DefaultHttp2DataFrame(onHeapAllocator().allocate(0).send(), true));

        assertTrue(channel.isShutdown(ChannelShutdownDirection.Outbound));
        assertTrue(handler.isOutboundShutdown());
    }

    @Test
    public void headerAndDataFramesShouldBeDelivered() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).stream(channel.stream());
        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("hello").send()).stream(channel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("world").send()).stream(channel.stream());

        assertTrue(inboundHandler.isChannelActive());
        frameInboundWriter.writeInboundData(channel.stream().id(), bb("hello"), 0, false);
        frameInboundWriter.writeInboundData(channel.stream().id(), bb("world"), 0, false);

        assertEquals(headersFrame, inboundHandler.readInbound());

        assertEqualsAndRelease(dataFrame1, inboundHandler.readInbound());
        assertEqualsAndRelease(dataFrame2, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());
    }

     enum RstFrameTestMode {
        HEADERS_END_STREAM,
        DATA_END_STREAM,
        TRAILERS_END_STREAM;
    }

    @ParameterizedTest
    @EnumSource(RstFrameTestMode.class)
    void noRstFrameSentOnCloseViaListener(final RstFrameTestMode mode) throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler() {
            private boolean headersReceived;
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    final boolean endStream;
                    if (msg instanceof Http2HeadersFrame) {
                        endStream = ((Http2HeadersFrame) msg).isEndStream();
                        switch (mode) {
                            case HEADERS_END_STREAM:
                                assertFalse(headersReceived);
                                assertTrue(endStream);
                                break;
                            case TRAILERS_END_STREAM:
                                if (headersReceived) {
                                    assertTrue(endStream);
                                } else {
                                    assertFalse(endStream);
                                }
                                break;
                            case DATA_END_STREAM:
                                assertFalse(endStream);
                                break;
                            default:
                                fail();
                        }
                        headersReceived = true;
                    } else if (msg instanceof Http2DataFrame) {
                        endStream = ((Http2DataFrame) msg).isEndStream();
                        switch (mode) {
                            case HEADERS_END_STREAM:
                                fail();
                                break;
                            case TRAILERS_END_STREAM:
                                assertFalse(endStream);
                                break;
                            case DATA_END_STREAM:
                                assertTrue(endStream);
                                break;
                            default:
                                fail();
                        }
                    } else {
                        throw new UnsupportedMessageTypeException(msg);
                    }
                    if (endStream) {
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders(), true, 0))
                                .addListener(ctx, ChannelFutureListeners.CLOSE);
                    }
                } finally {
                    Resource.dispose(msg);
                }
            }
        };

        Http2StreamChannel channel = newInboundStream(3, mode == RstFrameTestMode.HEADERS_END_STREAM, inboundHandler);
        if (mode != RstFrameTestMode.HEADERS_END_STREAM) {
            frameInboundWriter.writeInboundData(
                    channel.stream().id(), bb("something"), 0, mode == RstFrameTestMode.DATA_END_STREAM);
            if (mode != RstFrameTestMode.DATA_END_STREAM) {
                frameInboundWriter.writeInboundHeaders(channel.stream().id(), Http2Headers.newHeaders(), 0, true);
            }
        }
        channel.closeFuture().asStage().sync();

        // We should never produce a RST frame in this case as we received the endOfStream before we write a frame
        // with the endOfStream flag.
        verify(frameWriter, never()).writeRstStream(any(ChannelHandlerContext.class),
                eqStreamId(channel), anyLong());
        inboundHandler.checkException();
    }

    @Test
    public void headerMultipleContentLengthValidationShouldPropagate() throws Exception {
        headerMultipleContentLengthValidationShouldPropagate(false);
    }

    @Test
    public void headerMultipleContentLengthValidationShouldPropagateWithEndStream() throws Exception {
        headerMultipleContentLengthValidationShouldPropagate(true);
    }

    private void headerMultipleContentLengthValidationShouldPropagate(boolean endStream) throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.add(HttpHeaderNames.CONTENT_LENGTH, "0");
        request.add(HttpHeaderNames.CONTENT_LENGTH, "1");
        Http2StreamChannel channel = newInboundStream(3, endStream, inboundHandler);

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void headerPlusSignContentLengthValidationShouldPropagate() throws Exception {
        headerSignContentLengthValidationShouldPropagateWithEndStream(false, false);
    }

    @Test
    public void headerPlusSignContentLengthValidationShouldPropagateWithEndStream() throws Exception {
        headerSignContentLengthValidationShouldPropagateWithEndStream(false, true);
    }

    @Test
    public void headerMinusSignContentLengthValidationShouldPropagate() throws Exception {
        headerSignContentLengthValidationShouldPropagateWithEndStream(true, false);
    }

    @Test
    public void headerMinusSignContentLengthValidationShouldPropagateWithEndStream() throws Exception {
        headerSignContentLengthValidationShouldPropagateWithEndStream(true, true);
    }

    private void headerSignContentLengthValidationShouldPropagateWithEndStream(boolean minus, boolean endStream)
            throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.add(HttpHeaderNames.CONTENT_LENGTH, (minus ? "-" : "+") + 1);
        Http2StreamChannel channel = newInboundStream(3, endStream, inboundHandler);
        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });

        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagate() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(false, false, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStream() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(false, true, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateCloseLocal() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(true, false, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamCloseLocal() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(true, true, false);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateTrailers() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(false, false, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamTrailers() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(false, true, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateCloseLocalTrailers() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(true, false, true);
    }

    @Test
    public void headerContentLengthNotMatchValidationShouldPropagateWithEndStreamCloseLocalTrailers() throws Exception {
        headerContentLengthNotMatchValidationShouldPropagate(true, true, true);
    }

    private void headerContentLengthNotMatchValidationShouldPropagate(
            boolean closeLocal, boolean endStream, boolean trailer) throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.add(HttpHeaderNames.CONTENT_LENGTH, "1");
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());

        if (closeLocal) {
            channel.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders(), true)).asStage().sync();
            assertEquals(Http2Stream.State.HALF_CLOSED_LOCAL, channel.stream().state());
        } else {
            assertEquals(Http2Stream.State.OPEN, channel.stream().state());
        }

        if (trailer) {
            frameInboundWriter.writeInboundHeaders(channel.stream().id(), Http2Headers.newHeaders(), 0, endStream);
        } else {
            frameInboundWriter.writeInboundData(channel.stream().id(), bb("foo"), 0, endStream);
        }

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });

        Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(request).stream(channel.stream());
        assertEquals(headersFrame, inboundHandler.readInbound());
        assertNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
    }

    @Test
    public void streamExceptionCauseRstStreamWithProtocolError() throws Exception {
        request.add(HttpHeaderNames.CONTENT_LENGTH, "10");
        Http2StreamChannel channel = newInboundStream(3, false, new ChannelHandlerAdapter() { });
        channel.pipeline().fireChannelExceptionCaught(new Http2FrameStreamException(channel.stream(),
                Http2Error.PROTOCOL_ERROR, new IllegalArgumentException()));
        assertFalse(channel.isActive());
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class), eq(3),
                eq(Http2Error.PROTOCOL_ERROR.code()));
    }

    @Test
    public void contentLengthNotMatchRstStreamWithProtocolError() throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        request.add(HttpHeaderNames.CONTENT_LENGTH, "10");
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        frameInboundWriter.writeInboundData(3, bb(8), 0, true);
        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
        assertNotNull(inboundHandler.readInbound());
        assertFalse(channel.isActive());
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class), eq(3),
                eq(Http2Error.PROTOCOL_ERROR.code()));
    }

    @Test
    public void framesShouldBeMultiplexed() throws Exception {
        LastInboundHandler handler1 = new LastInboundHandler();
        Http2StreamChannel channel1 = newInboundStream(3, false, handler1);
        LastInboundHandler handler2 = new LastInboundHandler();
        Http2StreamChannel channel2 = newInboundStream(5, false, handler2);
        LastInboundHandler handler3 = new LastInboundHandler();
        Http2StreamChannel channel3 = newInboundStream(11, false, handler3);

        verifyFramesMultiplexedToCorrectChannel(channel1, handler1, 1);
        verifyFramesMultiplexedToCorrectChannel(channel2, handler2, 1);
        verifyFramesMultiplexedToCorrectChannel(channel3, handler3, 1);

        frameInboundWriter.writeInboundData(channel2.stream().id(), bb("hello"), 0, false);
        frameInboundWriter.writeInboundData(channel1.stream().id(), bb("foo"), 0, true);
        frameInboundWriter.writeInboundData(channel2.stream().id(), bb("world"), 0, true);
        frameInboundWriter.writeInboundData(channel3.stream().id(), bb("bar"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(channel1, handler1, 1);
        verifyFramesMultiplexedToCorrectChannel(channel2, handler2, 2);
        verifyFramesMultiplexedToCorrectChannel(channel3, handler3, 1);
    }

    @Test
    public void inboundDataFrameShouldUpdateLocalFlowController() throws Exception {
        Http2LocalFlowController flowController = Mockito.mock(Http2LocalFlowController.class);
        codec.connection().local().flowController(flowController);

        LastInboundHandler handler = new LastInboundHandler();
        final Http2StreamChannel channel = newInboundStream(3, false, handler);

        Buffer tenBytes = bb("0123456789");

        frameInboundWriter.writeInboundData(channel.stream().id(), tenBytes, 0, true);

        // Verify we marked the bytes as consumed
        verify(flowController).consumeBytes(argThat(http2Stream -> http2Stream.id() == channel.stream().id()), eq(10));

        // headers and data frame
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 2);
    }

    @Test
    public void unhandledHttp2FramesShouldBePropagated() throws Exception {
        Http2PingFrame pingFrame = new DefaultHttp2PingFrame(0);
        frameInboundWriter.writeInboundPing(false, 0);
        assertEquals(parentChannel.readInbound(), pingFrame);

        DefaultHttp2GoAwayFrame goAwayFrame = new DefaultHttp2GoAwayFrame(1,
                parentChannel.bufferAllocator().allocate(8).writeLong(8).send());
        frameInboundWriter.writeInboundGoAway(0, goAwayFrame.errorCode(), goAwayFrame.content().copy());

        Http2GoAwayFrame frame = parentChannel.readInbound();
        assertEqualsAndRelease(frame, goAwayFrame);
    }

    @Test
    public void channelReadShouldRespectAutoRead() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.setOption(ChannelOption.AUTO_READ, false);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        Resource.dispose(dataFrame0);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        assertNull(inboundHandler.readInbound());

        childChannel.setOption(ChannelOption.AUTO_READ, true);
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 2);
    }

    @Test
    public void noAutoReadWithReentrantReadDoesNotSOOE() throws Exception {
        final AtomicBoolean shouldRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = new Consumer<ChannelHandlerContext>() {
            @Override
            public void accept(ChannelHandlerContext obj) {
                if (shouldRead.get()) {
                    obj.read();
                }
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        AtomicInteger maxReads = new AtomicInteger(1);
        Http2StreamChannel childChannel = newInboundStream(3, false, maxReads, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.setOption(ChannelOption.AUTO_READ, false);

        final int maxWrites = 10000; // enough writes to generated SOOE.
        for (int i = 0; i < maxWrites; ++i) {
            frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(String.valueOf(i)), 0, false);
        }
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(String.valueOf(maxWrites)), 0, true);
        shouldRead.set(true);
        childChannel.read();

        for (int i = 0; i < maxWrites; ++i) {
            Http2DataFrame dataFrame0 = inboundHandler.readInbound();
            assertNotNull(dataFrame0);
            Resource.dispose(dataFrame0);
        }
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertTrue(dataFrame0.isEndStream());
        Resource.dispose(dataFrame0);

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 0);
    }

    @Test
    public void readNotRequiredToEndStream() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        AtomicInteger maxReads = new AtomicInteger(1);
        Http2StreamChannel childChannel = newInboundStream(3, false, maxReads, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));

        childChannel.setOption(ChannelOption.AUTO_READ, false);

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        assertNull(inboundHandler.readInbound());

        frameInboundWriter.writeInboundRstStream(childChannel.stream().id(), Http2Error.NO_ERROR.code());

        assertFalse(inboundHandler.isChannelActive());
        childChannel.closeFuture().asStage().sync();

        Http2ResetFrame resetFrame = useUserEventForResetFrame() ? inboundHandler.<Http2ResetFrame>readUserEvent() :
                inboundHandler.<Http2ResetFrame>readInbound();

        assertEquals(childChannel.stream(), resetFrame.stream());
        assertEquals(Http2Error.NO_ERROR.code(), resetFrame.errorCode());

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 0);
    }

    @Test
    public void channelReadShouldRespectAutoReadAndNotProduceNPE() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        childChannel.setOption(ChannelOption.AUTO_READ, false);
        childChannel.pipeline().addFirst(new ChannelHandler() {
            private int count;
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                // Close channel after 2 reads so there is still something in the inboundBuffer when the close happens.
                if (++count == 2) {
                    ctx.close();
                }
            }
        });
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        Http2DataFrame dataFrame0 = inboundHandler.readInbound();
        assertNotNull(dataFrame0);
        Resource.dispose(dataFrame0);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        assertNull(inboundHandler.readInbound());

        childChannel.setOption(ChannelOption.AUTO_READ, true);
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);
        inboundHandler.checkException();
    }

    @Test
    public void readInChannelReadWithoutAutoRead() throws Exception {
        useReadWithoutAutoRead(false);
    }

    @Test
    public void readInChannelReadCompleteWithoutAutoRead() throws Exception {
        useReadWithoutAutoRead(true);
    }

    private void useReadWithoutAutoRead(final boolean readComplete) throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        // Add a handler which will request reads.
        childChannel.pipeline().addFirst(new ChannelHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                if (!readComplete) {
                    ctx.read();
                }
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                ctx.fireChannelReadComplete();
                if (readComplete) {
                    ctx.read();
                }
            }
        });

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 6);
    }

    private Http2StreamChannel newOutboundStream(ChannelHandler handler) throws Exception {
        Future<Http2StreamChannel> future = new Http2StreamChannelBootstrap(parentChannel).handler(handler).open();
        return future.asStage().get();
    }
    @Test
    public void allQueuedFramesDeliveredAfterParentIsClosed() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, new AtomicInteger(1), inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("baz"), 0, true);
        assertNull(inboundHandler.readInbound());

        parentChannel.close();
        assertTrue(childChannel.isActive());
        childChannel.read();
        inboundHandler.checkException();
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 2);
        assertFalse(childChannel.isActive());
    }

    /**
     * A child channel for an HTTP/2 stream in IDLE state (that is no headers sent or received),
     * should not emit a RST_STREAM frame on close, as this is a connection error of type protocol error.
     */
    @Test
    public void idleOutboundStreamShouldNotWriteResetFrameOnClose() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();

        Channel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        childChannel.close();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertNull(parentChannel.readOutbound());
    }

    @Test
    public void outboundStreamShouldWriteResetFrameOnClose_headersSent() throws Exception {
        ChannelHandler handler = new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
                ctx.fireChannelActive();
            }
        };

        Http2StreamChannel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        childChannel.close();
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class),
                eqStreamId(childChannel), eq(Http2Error.CANCEL.code()));
    }

    @Test
    public void outboundStreamShouldNotWriteResetFrameOnClose_IfStreamDidntExist() throws Exception {
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                any(Http2Headers.class), anyInt(), anyBoolean())).thenAnswer(new Answer<Future<Void>>() {

            private boolean headersWritten;
            @Override
            public Future<Void> answer(InvocationOnMock invocationOnMock) {
                // We want to fail to write the first headers frame. This is what happens if the connection
                // refuses to allocate a new stream due to having received a GOAWAY.
                if (!headersWritten) {
                    headersWritten = true;
                    return ImmediateEventExecutor.INSTANCE.newFailedFuture(new Exception("boom"));
                }
                return ImmediateEventExecutor.INSTANCE.newSucceededFuture(null);
            }
        });

        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
                ctx.fireChannelActive();
            }
        });

        assertFalse(childChannel.isActive());

        childChannel.close();
        // The channel was never active so we should not generate a RST frame.
        verify(frameWriter, never()).writeRstStream(any(ChannelHandlerContext.class),
                eqStreamId(childChannel), anyLong());

        assertTrue(parentChannel.outboundMessages().isEmpty());
    }

    @Test
    public void inboundRstStreamFireChannelInactive() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(inboundHandler.isChannelActive());
        frameInboundWriter.writeInboundRstStream(channel.stream().id(), Http2Error.INTERNAL_ERROR.code());

        assertFalse(inboundHandler.isChannelActive());

        // A RST_STREAM frame should NOT be emitted, as we received a RST_STREAM.
        verify(frameWriter, never()).writeRstStream(any(ChannelHandlerContext.class), eqStreamId(channel),
                anyLong());
    }

    @Test
    public void streamExceptionTriggersChildChannelExceptionAndClose() throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());
        StreamException cause = new StreamException(channel.stream().id(), Http2Error.PROTOCOL_ERROR, "baaam!");
        parentChannel.pipeline().fireChannelExceptionCaught(cause);

        assertFalse(channel.isActive());

        assertThrows(StreamException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
    }

    @Test
    public void streamClosedErrorTranslatedToClosedChannelExceptionOnWrites() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();

        final Http2StreamChannel childChannel = newOutboundStream(inboundHandler);
        assertTrue(childChannel.isActive());

        Http2Headers headers = Http2Headers.newHeaders();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock ->
            ImmediateEventExecutor.INSTANCE.newFailedFuture(
                        new StreamException(childChannel.stream().id(), Http2Error.STREAM_CLOSED, "Stream Closed")));
        final Future<Void> future = childChannel.writeAndFlush(
                new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));

        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        inboundHandler.checkException();

        CompletionException e = assertThrows(CompletionException.class, new Executable() {
            @Override
            public void execute() throws Exception {
                future.asStage().sync();
            }
        });
        assertThat(e.getCause(), CoreMatchers.instanceOf(ClosedChannelException.class));
    }

    @Test
    public void creatingWritingReadingAndClosingOutboundStreamShouldWork() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newOutboundStream(inboundHandler);
        assertTrue(childChannel.isActive());
        assertTrue(inboundHandler.isChannelActive());

        // Write to the child channel
        Http2Headers headers = Http2Headers.newHeaders().scheme("https").method("GET").path("/foo.txt");
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));

        // Read from the child channel
        frameInboundWriter.writeInboundHeaders(childChannel.stream().id(), headers, 0, false);

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);
        assertEquals(headers, headersFrame.headers());

        // Close the child channel.
        childChannel.close();

        // An active outbound stream should emit a RST_STREAM frame.
        verify(frameWriter).writeRstStream(any(ChannelHandlerContext.class), eqStreamId(childChannel),
                anyLong());

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
        assertFalse(inboundHandler.isChannelActive());
    }

    // Test failing the promise of the first headers frame of an outbound stream. In practice this error case would most
    // likely happen due to the max concurrent streams limit being hit or the channel running out of stream identifiers.
    //
    @Test
    public void failedOutboundStreamCreationThrowsAndClosesChannel() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();
        Http2StreamChannel childChannel = newOutboundStream(handler);
        assertTrue(childChannel.isActive());

        Http2Headers headers = Http2Headers.newHeaders();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
               eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock ->
               ImmediateEventExecutor.INSTANCE.newFailedFuture(new Http2NoMoreStreamIdsException()));

        final Future<Void> future = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
        parentChannel.flush();

        assertFalse(childChannel.isActive());
        assertFalse(childChannel.isOpen());

        handler.checkException();

        CompletionException e = assertThrows(CompletionException.class, new Executable() {
            @Override
            public void execute() throws Exception {
                future.asStage().sync();
            }
        });
        assertThat(e.getCause(), CoreMatchers.instanceOf(Http2NoMoreStreamIdsException.class));
    }

    @Test
    public void channelClosedWhenCloseListenerCompletes() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        // Create a promise before actually doing the close, because otherwise we would be adding a listener to a future
        // that is already completed because we are using EmbeddedChannel which executes code in the JUnit thread.
        Promise<Void> p = childChannel.newPromise();
        p.asFuture().addListener(childChannel, (channel, future) -> {
            channelOpen.set(channel.isOpen());
            channelActive.set(channel.isActive());
        });
        childChannel.close().cascadeTo(p).asStage().sync();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenChannelClosePromiseCompletes() throws Exception {
         LastInboundHandler inboundHandler = new LastInboundHandler();
         Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

         assertTrue(childChannel.isOpen());
         assertTrue(childChannel.isActive());

         final AtomicBoolean channelOpen = new AtomicBoolean(true);
         final AtomicBoolean channelActive = new AtomicBoolean(true);

         childChannel.closeFuture().addListener(childChannel, (channel, future) -> {
             channelOpen.set(channel.isOpen());
             channelActive.set(channel.isActive());
         });
        childChannel.close().asStage().sync();

        assertFalse(channelOpen.get());
         assertFalse(channelActive.get());
         assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedWhenWriteFutureFails() throws Exception {
        final Queue<Promise<Void>> writePromises = new ArrayDeque<>();

        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        final AtomicBoolean channelOpen = new AtomicBoolean(true);
        final AtomicBoolean channelActive = new AtomicBoolean(true);

        Http2Headers headers = Http2Headers.newHeaders();
        when(frameWriter.writeHeaders(any(ChannelHandlerContext.class), anyInt(),
                eq(headers), anyInt(), anyBoolean())).thenAnswer(invocationOnMock -> {
            Promise<Void> promise = ImmediateEventExecutor.INSTANCE.newPromise();
            writePromises.offer(promise);
            return promise;
        });

        Future<Void> f = childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers));
        assertFalse(f.isDone());
        f.addListener(childChannel, (channel, future)-> {
            channelOpen.set(channel.isOpen());
            channelActive.set(channel.isActive());
        });

        Promise<Void> first = writePromises.poll();
        first.setFailure(new ClosedChannelException());
        f.asStage().await();

        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void channelClosedTwiceMarksPromiseAsSuccessful() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());
        childChannel.close().asStage().sync();
        childChannel.close().asStage().sync();

        assertFalse(childChannel.isOpen());
        assertFalse(childChannel.isActive());
    }

    @Test
    public void settingChannelOptsAndAttrs() throws Exception {
        AttributeKey<String> key = AttributeKey.newInstance(UUID.randomUUID().toString());

        Channel childChannel = newOutboundStream(new ChannelHandler() { });
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        childChannel.attr(key).set("bar");
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));
        assertEquals("bar", childChannel.attr(key).get());
    }

    @Test
    public void outboundFlowControlWritability() throws Exception {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() { });
        assertTrue(childChannel.isActive());

        assertTrue(childChannel.isWritable());
        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
        parentChannel.flush();

        // Test for initial window size
        assertTrue(initialRemoteStreamWindow <
                childChannel.getOption(ChannelOption.WRITE_BUFFER_WATER_MARK).high());

        assertTrue(childChannel.isWritable());
        int size = 16 * 1024 * 1024;
        childChannel.write(new DefaultHttp2DataFrame(
                onHeapAllocator().allocate(size).fill((byte) 0).writerOffset(size).send()));
        assertEquals(0, childChannel.writableBytes());
        assertFalse(childChannel.isWritable());
    }

    @Test
    public void writabilityOfParentIsRespected() throws Exception {
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() { });
        childChannel.setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(2048, 4096));
        parentChannel.setOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(256, 512));
        assertTrue(childChannel.isWritable());
        assertTrue(parentChannel.isActive());

        childChannel.writeAndFlush(new DefaultHttp2HeadersFrame(Http2Headers.newHeaders()));
        parentChannel.flush();

        assertTrue(childChannel.isWritable());
        childChannel.write(new DefaultHttp2DataFrame(bb(256).send()));
        assertTrue(childChannel.isWritable());
        childChannel.writeAndFlush(new DefaultHttp2DataFrame(bb(512).send()));

        long writableBytes = childChannel.writableBytes();
        assertNotEquals(0, writableBytes);
        // Add something to the ChannelOutboundBuffer of the parent to simulate queuing in the parents channel buffer
        // and verify that this only affect the writability of the parent channel while the child stays writable
        // until it used all of its credits.
        parentChannel.pipeline().firstContext().write(
                onHeapAllocator().allocate(800).skipWritableBytes(800));
        assertFalse(parentChannel.isWritable());

        assertTrue(childChannel.isWritable());
        assertEquals(4096, childChannel.writableBytes());

        // Flush everything which simulate writing everything to the socket.
        parentChannel.flush();
        assertTrue(parentChannel.isWritable());
        assertTrue(childChannel.isWritable());
        assertEquals(writableBytes, childChannel.writableBytes());

        Future<Void> future = childChannel.writeAndFlush(new DefaultHttp2DataFrame(
                bb((int) writableBytes).send()));
        assertFalse(childChannel.isWritable());
        assertTrue(parentChannel.isWritable());

        parentChannel.flush();
        assertFalse(future.isDone());
        assertTrue(parentChannel.isWritable());
        assertFalse(childChannel.isWritable());

        // Now write an window update frame for the stream which then should ensure we will flush the bytes that were
        // queued in the RemoteFlowController before for the stream.
        frameInboundWriter.writeInboundWindowUpdate(childChannel.stream().id(), (int) writableBytes);
        assertTrue(childChannel.isWritable());
        assertTrue(future.isDone());
    }

    @Test
    public void channelClosedWhenInactiveFired() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);

        final AtomicBoolean channelOpen = new AtomicBoolean(false);
        final AtomicBoolean channelActive = new AtomicBoolean(false);
        assertTrue(childChannel.isOpen());
        assertTrue(childChannel.isActive());

        childChannel.pipeline().addLast(new ChannelHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                channelOpen.set(ctx.channel().isOpen());
                channelActive.set(ctx.channel().isActive());

                ctx.fireChannelInactive();
            }
        });

        childChannel.close().asStage().sync();
        assertFalse(channelOpen.get());
        assertFalse(channelActive.get());
    }

    @Test
    public void channelInactiveHappensAfterExceptionCaughtEvents() throws Exception {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicInteger exceptionCaught = new AtomicInteger(-1);
        final AtomicInteger channelInactive = new AtomicInteger(-1);
        final AtomicInteger channelUnregistered = new AtomicInteger(-1);
        Http2StreamChannel childChannel = newOutboundStream(new ChannelHandler() {

            @Override
            public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) throws Exception {
                ctx.close();
                throw new Exception("Exception for test");
            }
        });

        childChannel.pipeline().addLast(new ChannelHandler() {

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                channelInactive.set(count.getAndIncrement());
                ctx.fireChannelInactive();
            }

            @Override
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                exceptionCaught.set(count.getAndIncrement());
                ctx.fireChannelExceptionCaught(cause);
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) {
                channelUnregistered.set(count.getAndIncrement());
                ctx.fireChannelUnregistered();
            }
        });

        childChannel.pipeline().fireChannelInboundEvent(new Object());

        // The events should have happened in this order because the inactive and deregistration events
        // get deferred as they do in the AbstractChannel.
        assertEquals(0, exceptionCaught.get());
        assertEquals(1, channelInactive.get());
        assertEquals(2, channelUnregistered.get());
    }

    @Test
    public void endOfStreamDoesNotDiscardData() throws Exception {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            if (shouldDisableAutoRead.get()) {
                obj.channel().setOption(ChannelOption.AUTO_READ, false);
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.setOption(ChannelOption.AUTO_READ, false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4").send()).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };

        parentChannel.pipeline().addFirst(readCompleteSupressHandler);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.<Http2DataFrame>readInbound());

        // Deliver frames, and then a stream closed while read is inactive.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);

        shouldDisableAutoRead.set(true);
        childChannel.setOption(ChannelOption.AUTO_READ, true);
        numReads.set(1);

        frameInboundWriter.writeInboundRstStream(childChannel.stream().id(), Http2Error.NO_ERROR.code());

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEqualsAndRelease(dataFrame2, inboundHandler.<Http2DataFrame>readInbound());
        assertNull(inboundHandler.readInbound());

        // As we limited the number to 1 we also need to call read() again.
        childChannel.read();

        assertEqualsAndRelease(dataFrame3, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame4, inboundHandler.<Http2DataFrame>readInbound());

        Http2ResetFrame resetFrame = useUserEventForResetFrame() ? inboundHandler.readUserEvent() :
                inboundHandler.readInbound();

        assertEquals(childChannel.stream(), resetFrame.stream());
        assertEquals(Http2Error.NO_ERROR.code(), resetFrame.errorCode());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        childChannel.closeFuture().asStage().sync();
    }

    @Test
    public void readPriorityFrame() throws Exception {
        LastInboundHandler handler = new LastInboundHandler();

        Http2StreamChannel channel = newInboundStream(3, true, handler);
        frameInboundWriter.writeInboundPriority(channel.stream().id(), 0, (short) 2, false);

        // header frame should be multiplexed via fireChannelRead(...)
        verifyFramesMultiplexedToCorrectChannel(channel, handler, 1);

        Http2PriorityFrame priorityFrame = handler.readUserEvent();
        assertEquals(channel.stream(), priorityFrame.stream());
        assertEquals(0, priorityFrame.streamDependency());
        assertEquals(2, priorityFrame.weight());
        assertFalse(priorityFrame.exclusive());
    }

    private boolean useUserEventForResetFrame() {
        return true;
    }

    private boolean ignoreWindowUpdateFrames() {
        return true;
    }

    @Test
    public void windowUpdateFrames() throws Exception {
        AtomicInteger numReads = new AtomicInteger(1);
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        frameInboundWriter.writeInboundWindowUpdate(childChannel.stream().id(), 4);

        Http2WindowUpdateFrame updateFrame = inboundHandler.readInbound();
        if (ignoreWindowUpdateFrames()) {
            assertNull(updateFrame);
        } else {
            assertEquals(new DefaultHttp2WindowUpdateFrame(4).stream(childChannel.stream()), updateFrame);
        }

        frameInboundWriter.writeInboundWindowUpdate(Http2CodecUtil.CONNECTION_STREAM_ID, 6);

        assertNull(parentChannel.readInbound());
        childChannel.close().asStage().sync();
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopAutoRead() throws Exception {
        AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            channelReadCompleteCount.incrementAndGet();
            if (shouldDisableAutoRead.get()) {
                obj.channel().setOption(ChannelOption.AUTO_READ, false);
            }
        };
        LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.setOption(ChannelOption.AUTO_READ, false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4").send()).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };
        parentChannel.pipeline().addFirst(readCompleteSupressHandler);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.<Http2DataFrame>readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);

        numReads.set(10);
        shouldDisableAutoRead.set(true);
        childChannel.setOption(ChannelOption.AUTO_READ, true);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);

        // Detecting EOS should flush all pending data regardless of read calls.
        assertEqualsAndRelease(dataFrame2, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame3, inboundHandler.<Http2DataFrame>readInbound());
        assertEqualsAndRelease(dataFrame4, inboundHandler.<Http2DataFrame>readInbound());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for read when auto read was off + 1 for when auto read was back on
        assertEquals(3, channelReadCompleteCount.get());
    }

    @Test
    public void childQueueIsDrainedAndNewDataIsDispatchedInParentReadLoopNoAutoRead() throws Exception {
        final AtomicInteger numReads = new AtomicInteger(1);
        final AtomicInteger channelReadCompleteCount = new AtomicInteger(0);
        final AtomicBoolean shouldDisableAutoRead = new AtomicBoolean();
        Consumer<ChannelHandlerContext> ctxConsumer = obj -> {
            channelReadCompleteCount.incrementAndGet();
            if (shouldDisableAutoRead.get()) {
                obj.channel().setOption(ChannelOption.AUTO_READ, false);
            }
        };
        final LastInboundHandler inboundHandler = new LastInboundHandler(ctxConsumer);
        Http2StreamChannel childChannel = newInboundStream(3, false, numReads, inboundHandler);
        childChannel.setOption(ChannelOption.AUTO_READ, false);

        Http2DataFrame dataFrame1 = new DefaultHttp2DataFrame(bb("1").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame2 = new DefaultHttp2DataFrame(bb("2").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame3 = new DefaultHttp2DataFrame(bb("3").send()).stream(childChannel.stream());
        Http2DataFrame dataFrame4 = new DefaultHttp2DataFrame(bb("4").send()).stream(childChannel.stream());

        assertEquals(new DefaultHttp2HeadersFrame(request).stream(childChannel.stream()), inboundHandler.readInbound());

        ChannelHandler readCompleteSupressHandler = new ChannelHandler() {
            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                // We want to simulate the parent channel calling channelRead and delay calling channelReadComplete.
            }
        };
        parentChannel.pipeline().addFirst(readCompleteSupressHandler);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("1"), 0, false);

        assertEqualsAndRelease(dataFrame1, inboundHandler.readInbound());

        // We want one item to be in the queue, and allow the numReads to be larger than 1. This will ensure that
        // when beginRead() is called the child channel is added to the readPending queue of the parent channel.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("2"), 0, false);

        numReads.set(2);
        childChannel.read();

        assertEqualsAndRelease(dataFrame2, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());

        // This is the second item that was read, this should be the last until we call read() again. This should also
        // notify of readComplete().
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("3"), 0, false);

        assertEqualsAndRelease(dataFrame3, inboundHandler.readInbound());

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("4"), 0, false);
        assertNull(inboundHandler.readInbound());

        childChannel.read();

        assertEqualsAndRelease(dataFrame4, inboundHandler.readInbound());

        assertNull(inboundHandler.readInbound());

        // Now we want to call channelReadComplete and simulate the end of the read loop.
        parentChannel.pipeline().remove(readCompleteSupressHandler);
        parentChannel.flushInbound();

        // 3 = 1 for initialization + 1 for first read of 2 items + 1 for second read of 2 items +
        // 1 for parent channel readComplete
        assertEquals(4, channelReadCompleteCount.get());
    }

    @Test
    public void useReadWithoutAutoReadInRead() throws Exception {
        useReadWithoutAutoReadBuffered(false);
    }

    @Test
    public void useReadWithoutAutoReadInReadComplete() throws Exception {
        useReadWithoutAutoReadBuffered(true);
    }

    private void useReadWithoutAutoReadBuffered(final boolean triggerOnReadComplete) throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        // Write some bytes to get the channel into the idle state with buffered data and also verify we
        // do not dispatch it until we receive a read() call.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar"), 0, false);

        // Add a handler which will request reads.
        childChannel.pipeline().addFirst(new ChannelHandler() {

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                ctx.fireChannelReadComplete();
                if (triggerOnReadComplete) {
                    ctx.read();
                    ctx.read();
                }
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(msg);
                if (!triggerOnReadComplete) {
                    ctx.read();
                    ctx.read();
                }
            }
        });

        inboundHandler.channel().read();

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);

        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("hello world2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("foo2"), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb("bar2"), 0, true);

        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 3);
    }

    private static final class FlushSniffer implements ChannelHandler {

        private boolean didFlush;

        public boolean checkFlush() {
            boolean r = didFlush;
            didFlush = false;
            return r;
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            didFlush = true;
            ctx.flush();
        }
    }

    @Test
    public void windowUpdatesAreFlushed() throws Exception {
        windowUpdatesAreFlushed(true);
    }

    @Test
    public void windowUpdatesNotDoneAutomatically() throws Exception {
        windowUpdatesAreFlushed(false);
    }

    private void windowUpdatesAreFlushed(boolean autoWriteWindowUpdateFrames) throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        FlushSniffer flushSniffer = new FlushSniffer();
        parentChannel.pipeline().addFirst(flushSniffer);

        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        childChannel.setOption(
                Http2StreamChannelOption.AUTO_STREAM_FLOW_CONTROL, autoWriteWindowUpdateFrames);

        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        assertTrue(flushSniffer.checkFlush());

        // Write some bytes to get the channel into the idle state with buffered data and also verify we
        // do not dispatch it until we receive a read() call.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        assertTrue(flushSniffer.checkFlush());

        verify(frameWriter, never())
                .writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());
        // only the first one was read because it was legacy auto-read behavior.
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        assertFalse(flushSniffer.checkFlush());

        // Trigger a read of the second frame.
        childChannel.read();
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        if (autoWriteWindowUpdateFrames) {
            // We expect a flush here because the StreamChannel will flush the smaller increment but the
            // connection will collect the bytes and decide not to send a wire level frame until more are consumed.
            assertTrue(flushSniffer.checkFlush());
        } else {
            assertFalse(flushSniffer.checkFlush());
        }

        verify(frameWriter, never()).writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());

        // Call read one more time which should trigger the writing of the flow control update.
        childChannel.read();
        if (autoWriteWindowUpdateFrames) {
            verify(frameWriter).writeWindowUpdate(any(ChannelHandlerContext.class), eq(0), eq(32 * 1024));
            verify(frameWriter).writeWindowUpdate(
                    any(ChannelHandlerContext.class), eq(childChannel.stream().id()), eq(32 * 1024));
            assertTrue(flushSniffer.checkFlush());
        } else {
            verify(frameWriter, never()).writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());
            assertFalse(flushSniffer.checkFlush());

            // Let's manually send a window update frame now.
            Future<Void> f = childChannel.writeAndFlush(new DefaultHttp2WindowUpdateFrame(32 * 1024)
                    .stream(childChannel.stream()));
            assertTrue(f.isSuccess());
            verify(frameWriter).writeWindowUpdate(any(ChannelHandlerContext.class), eq(0), eq(32 * 1024));
            verify(frameWriter).writeWindowUpdate(
                    any(ChannelHandlerContext.class), eq(childChannel.stream().id()), eq(32 * 1024));
            assertTrue(flushSniffer.checkFlush());

            // Let's try to send one more even though there are no more pending bytes
            f = childChannel.writeAndFlush(new DefaultHttp2WindowUpdateFrame(32 * 1024)
                    .stream(childChannel.stream()));
            assertNotNull(f.cause());
        }
    }

    @Test
    public void windowUpdatesSendWhenAutoReadEnabled() throws Exception {
        LastInboundHandler inboundHandler = new LastInboundHandler();
        FlushSniffer flushSniffer = new FlushSniffer();
        parentChannel.pipeline().addFirst(flushSniffer);

        Http2StreamChannel childChannel = newInboundStream(3, false, inboundHandler);
        childChannel.setOption(
                Http2StreamChannelOption.AUTO_STREAM_FLOW_CONTROL, false);

        assertTrue(childChannel.getOption(ChannelOption.AUTO_READ));
        childChannel.setOption(ChannelOption.AUTO_READ, false);
        assertFalse(childChannel.getOption(ChannelOption.AUTO_READ));

        Http2HeadersFrame headersFrame = inboundHandler.readInbound();
        assertNotNull(headersFrame);

        assertTrue(flushSniffer.checkFlush());

        // Write some bytes to get the channel into the idle state with buffered data and also verify we
        // do not dispatch it until we receive a read() call.
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        frameInboundWriter.writeInboundData(childChannel.stream().id(), bb(16 * 1024), 0, false);
        assertTrue(flushSniffer.checkFlush());

        verify(frameWriter, never()).writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());
        // only the first one was read because it was legacy auto-read behavior.
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        assertFalse(flushSniffer.checkFlush());

        // Trigger a read of the second frame.
        childChannel.read();
        verifyFramesMultiplexedToCorrectChannel(childChannel, inboundHandler, 1);
        assertFalse(flushSniffer.checkFlush());

        verify(frameWriter, never()).writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());

        childChannel.read();

        verify(frameWriter, never()).writeWindowUpdate(any(ChannelHandlerContext.class), anyInt(), anyInt());
        assertFalse(flushSniffer.checkFlush());

        childChannel.setOption(
                Http2StreamChannelOption.AUTO_STREAM_FLOW_CONTROL, true);

        verify(frameWriter).writeWindowUpdate(any(ChannelHandlerContext.class), eq(0), eq(32 * 1024));
        verify(frameWriter).writeWindowUpdate(
                any(ChannelHandlerContext.class), eq(childChannel.stream().id()), eq(32 * 1024));
        assertTrue(flushSniffer.checkFlush());
    }

    @Test
    public void sslExceptionTriggersChildChannelException() throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());
        final RuntimeException testExc = new RuntimeException(new SSLException("foo"));
        channel.parent().pipeline().addLast(new ChannelHandler() {
            @Override
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                if (cause != testExc) {
                    ChannelHandler.super.channelExceptionCaught(ctx, cause);
                }
            }
        });
        channel.parent().pipeline().fireChannelExceptionCaught(testExc);

        assertTrue(channel.isActive());
        RuntimeException exc = assertThrows(RuntimeException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
        assertEquals(testExc, exc);
    }

    @Test
    public void customExceptionForwarding() throws Exception {
        final LastInboundHandler inboundHandler = new LastInboundHandler();
        Http2StreamChannel channel = newInboundStream(3, false, inboundHandler);
        assertTrue(channel.isActive());
        final RuntimeException testExc = new RuntimeException("xyz");
        channel.parent().pipeline().addLast(new ChannelHandler() {
            @Override
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                if (cause != testExc) {
                    ChannelHandler.super.channelExceptionCaught(ctx, cause);
                } else {
                    ctx.pipeline().fireChannelExceptionCaught(new Http2MultiplexActiveStreamsException(cause));
                }
            }
        });
        channel.parent().pipeline().fireChannelExceptionCaught(testExc);

        assertTrue(channel.isActive());
        RuntimeException exc = assertThrows(RuntimeException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                inboundHandler.checkException();
            }
        });
        assertEquals(testExc, exc);
    }

    private static void verifyFramesMultiplexedToCorrectChannel(Http2StreamChannel streamChannel,
                                                                LastInboundHandler inboundHandler,
                                                                int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            Http2StreamFrame frame = inboundHandler.readInbound();
            assertNotNull(frame, i + " out of " + numFrames + " received");
            assertEquals(streamChannel.stream(), frame.stream());
            Resource.dispose(frame);
        }
        assertNull(inboundHandler.readInbound());
    }

    private static int eqStreamId(Http2StreamChannel channel) {
        return eq(channel.stream().id());
    }
}
