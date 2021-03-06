/*
 * Copyright 2020 The Netty Project
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
package io.netty.incubator.codec.http3;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.incubator.codec.http3.Http3ErrorCode.H3_FRAME_UNEXPECTED;
import static io.netty.incubator.codec.http3.Http3RequestStreamValidationHandler.newClientValidator;
import static io.netty.incubator.codec.http3.Http3RequestStreamValidationHandler.newServerValidator;
import static io.netty.incubator.codec.http3.Http3TestUtils.assertException;
import static io.netty.incubator.codec.http3.Http3TestUtils.assertFrameEquals;
import static io.netty.incubator.codec.http3.Http3TestUtils.verifyClose;
import static io.netty.util.ReferenceCountUtil.release;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http3RequestStreamValidationHandlerTest extends Http3FrameTypeValidationHandlerTest {
    private final QpackDecoder decoder;

    public Http3RequestStreamValidationHandlerTest() {
        super(true, true);
        decoder = new QpackDecoder(new DefaultHttp3SettingsFrame());
    }

    @Override
    protected ChannelHandler newHandler() {
        return new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
                Http3RequestStreamEncodeStateValidator encStateValidator = new Http3RequestStreamEncodeStateValidator();
                Http3RequestStreamDecodeStateValidator decStateValidator = new Http3RequestStreamDecodeStateValidator();
                ch.pipeline().addLast(encStateValidator);
                ch.pipeline().addLast(decStateValidator);
                ch.pipeline().addLast(newServerValidator(qpackAttributes, decoder, encStateValidator,
                        decStateValidator));
            }
        };
    }

    @Override
    protected List<Http3RequestStreamFrame> newValidFrames() {
        return Arrays.asList(new DefaultHttp3HeadersFrame(), new DefaultHttp3DataFrame(Unpooled.directBuffer()),
                new DefaultHttp3UnknownFrame(Http3CodecUtils.MAX_RESERVED_FRAME_TYPE, Unpooled.buffer()));
    }

    @Test
    public void testInvalidFrameSequenceStartInbound() throws Exception {
        final EmbeddedQuicStreamChannel channel = newStream(QuicStreamType.BIDIRECTIONAL, newHandler());
        Http3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.buffer());
        try {
            channel.writeInbound(dataFrame);
            fail();
        } catch (Exception e) {
            assertException(H3_FRAME_UNEXPECTED, e);
        }
        verifyClose(H3_FRAME_UNEXPECTED, parent);
        assertEquals(0, dataFrame.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    public void testInvalidFrameSequenceEndInbound() throws Exception {
        final EmbeddedQuicStreamChannel channel = newStream(QuicStreamType.BIDIRECTIONAL, newHandler());

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        Http3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3DataFrame dataFrame2 = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3DataFrame dataFrame3 = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3HeadersFrame trailersFrame = new DefaultHttp3HeadersFrame();

        assertTrue(channel.writeInbound(headersFrame));
        assertTrue(channel.writeInbound(dataFrame.retainedDuplicate()));
        assertTrue(channel.writeInbound(dataFrame2.retainedDuplicate()));
        assertTrue(channel.writeInbound(trailersFrame));
        try {
            channel.writeInbound(dataFrame3);
            fail();
        } catch (Exception e) {
            assertException(H3_FRAME_UNEXPECTED, e);
        }

        verifyClose(H3_FRAME_UNEXPECTED, parent);
        assertTrue(channel.finish());
        assertEquals(0, dataFrame3.refCnt());

        assertFrameEquals(headersFrame, channel.readInbound());
        assertFrameEquals(dataFrame, channel.readInbound());
        assertFrameEquals(dataFrame2, channel.readInbound());
        assertFrameEquals(trailersFrame, channel.readInbound());
        assertNull(channel.readInbound());
    }

    @Test
    public void testInvalidFrameSequenceStartOutbound() throws Exception {
        EmbeddedQuicStreamChannel channel = newStream(QuicStreamType.BIDIRECTIONAL, newHandler());

        Http3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.buffer());
        try {
            channel.writeOutbound(dataFrame);
            fail();
        } catch (Exception e) {
            assertException(H3_FRAME_UNEXPECTED, e);
        }
        assertFalse(channel.finish());
        assertEquals(0, dataFrame.refCnt());
    }

    @Test
    public void testInvalidFrameSequenceEndOutbound() throws Exception {
        EmbeddedQuicStreamChannel channel = newStream(QuicStreamType.BIDIRECTIONAL, newHandler());

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        Http3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3DataFrame dataFrame2 = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3DataFrame dat3Frame3 = new DefaultHttp3DataFrame(Unpooled.buffer());
        Http3HeadersFrame trailersFrame = new DefaultHttp3HeadersFrame();
        assertTrue(channel.writeOutbound(headersFrame));
        assertTrue(channel.writeOutbound(dataFrame.retainedDuplicate()));
        assertTrue(channel.writeOutbound(dataFrame2.retainedDuplicate()));
        assertTrue(channel.writeOutbound(trailersFrame));

        try {
            channel.writeOutbound(dat3Frame3);
            fail();
        } catch (Exception e) {
            assertException(H3_FRAME_UNEXPECTED, e);
        }
        assertTrue(channel.finish());
        assertEquals(0, dat3Frame3.refCnt());

        assertFrameEquals(headersFrame, channel.readOutbound());
        assertFrameEquals(dataFrame, channel.readOutbound());
        assertFrameEquals(dataFrame2, channel.readOutbound());
        assertFrameEquals(trailersFrame, channel.readOutbound());
        assertNull(channel.readOutbound());
    }

    @Test
    public void testGoawayReceivedBeforeWritingHeaders() throws Exception {
        EmbeddedQuicStreamChannel channel = newClientStream(() -> true);

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        try {
            channel.writeOutbound(headersFrame);
            fail();
        } catch (Exception e) {
            assertException(H3_FRAME_UNEXPECTED, e);
        }
        // We should have closed the channel.
        assertFalse(channel.isActive());
        assertFalse(channel.finish());
        assertNull(channel.readOutbound());
    }

    @Test
    public void testGoawayReceivedAfterWritingHeaders() throws Exception {
        AtomicBoolean goAway = new AtomicBoolean();
        EmbeddedQuicStreamChannel channel = newClientStream(goAway::get);

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        Http3DataFrame dataFrame = new DefaultHttp3DataFrame(Unpooled.buffer());
        assertTrue(channel.writeOutbound(headersFrame));
        goAway.set(true);
        assertTrue(channel.writeOutbound(dataFrame.retainedDuplicate()));
        assertTrue(channel.finish());
        assertFrameEquals(headersFrame, channel.readOutbound());
        assertFrameEquals(dataFrame, channel.readOutbound());

        assertNull(channel.readOutbound());
    }

    @Test
    public void testClientHeadRequestWithContentLength() throws Exception {
        EmbeddedQuicStreamChannel channel = newClientStream(() -> false);

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method(HttpMethod.HEAD.asciiName());
        assertTrue(channel.writeOutbound(headersFrame));

        Http3HeadersFrame responseHeadersFrame = new DefaultHttp3HeadersFrame();
        responseHeadersFrame.headers().setLong(HttpHeaderNames.CONTENT_LENGTH, 10);

        assertTrue(channel.writeInbound(responseHeadersFrame));
        channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);

        assertTrue(channel.finishAndReleaseAll());
    }

    @Test
    public void testClientNonHeadRequestWithContentLengthNoData() throws Exception {
        testClientNonHeadRequestWithContentLength(true, false);
    }

    @Test
    public void testClientNonHeadRequestWithContentLengthNoDataAndTrailers() throws Exception {
        testClientNonHeadRequestWithContentLength(true, true);
    }

    @Test
    public void testClientNonHeadRequestWithContentLengthNotEnoughData() throws Exception {
        testClientNonHeadRequestWithContentLength(false, false);
    }

    @Test
    public void testClientNonHeadRequestWithContentLengthNotEnoughDataAndTrailer() throws Exception {
        testClientNonHeadRequestWithContentLength(false, true);
    }

    private void testClientNonHeadRequestWithContentLength(boolean noData, boolean trailers) throws Exception {
        EmbeddedQuicStreamChannel channel = newClientStream(() -> false);

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().method(HttpMethod.GET.asciiName());
        assertTrue(channel.writeOutbound(headersFrame));

        Http3HeadersFrame responseHeadersFrame = new DefaultHttp3HeadersFrame();
        responseHeadersFrame.headers().setLong(HttpHeaderNames.CONTENT_LENGTH, 10);

        assertTrue(channel.writeInbound(responseHeadersFrame));
        if (!noData) {
            assertTrue(channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.buffer().writeZero(9))));
        }
        try {
            if (trailers) {
                channel.writeInbound(new DefaultHttp3HeadersFrame());
            } else {
                channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
                channel.checkException();
            }
        } catch (Exception e) {
            assertException(Http3ErrorCode.H3_MESSAGE_ERROR, e);
        }
        assertTrue(channel.finishAndReleaseAll());
    }

    @Test
    public void testServerWithContentLengthNoData() throws Exception {
        testServerWithContentLength(true, false);
    }

    @Test
    public void testServerWithContentLengthNoDataAndTrailers() throws Exception {
        testServerWithContentLength(true, true);
    }

    @Test
    public void testServerWithContentLengthNotEnoughData() throws Exception {
        testServerWithContentLength(false, false);
    }

    @Test
    public void testServerWithContentLengthNotEnoughDataAndTrailer() throws Exception {
        testServerWithContentLength(false, true);
    }

    private void testServerWithContentLength(boolean noData, boolean trailers) throws Exception {
        EmbeddedQuicStreamChannel channel = newServerStream();

        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().setLong(HttpHeaderNames.CONTENT_LENGTH, 10);
        headersFrame.headers().method(HttpMethod.POST.asciiName());
        assertTrue(channel.writeInbound(headersFrame));

        if (!noData) {
            assertTrue(channel.writeInbound(new DefaultHttp3DataFrame(Unpooled.buffer().writeZero(9))));
        }
        try {
            if (trailers) {
                channel.writeInbound(new DefaultHttp3HeadersFrame());
            } else {
                channel.pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
                channel.checkException();
            }
        } catch (Exception e) {
            assertException(Http3ErrorCode.H3_MESSAGE_ERROR, e);
        }
        assertTrue(channel.finishAndReleaseAll());
    }

    @Test
    public void testHttp3HeadersFrameWithConnectionHeader() throws Exception {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().add(HttpHeaderNames.CONNECTION, "something");
        testHeadersFrame(headersFrame, Http3ErrorCode.H3_MESSAGE_ERROR);
    }

    @Test
    public void testHttp3HeadersFrameWithTeHeaderAndInvalidValue() throws Exception {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().add(HttpHeaderNames.TE, "something");
        testHeadersFrame(headersFrame, Http3ErrorCode.H3_MESSAGE_ERROR);
    }

    @Test
    public void testHttp3HeadersFrameWithTeHeaderAndValidValue() throws Exception {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS);
        testHeadersFrame(headersFrame, null);
    }

    @Test
    public void testInformationalResponseAfterActualResponseServer() throws Exception {
        testInformationalResponse(true, true, newResponse(OK), newResponse(CONTINUE));
    }

    @Test
    public void testInformationalResponseAfterActualResponseClient() throws Exception {
        testInformationalResponse(false, true, newResponse(OK), newResponse(CONTINUE));
    }

    @Test
    public void testMultiInformationalResponseServer() throws Exception {
        testInformationalResponse(true, false, newResponse(CONTINUE), newResponse(CONTINUE), newResponse(OK));
    }

    @Test
    public void testMultiInformationalResponseClient() throws Exception {
        testInformationalResponse(false, false, newResponse(CONTINUE), newResponse(CONTINUE), newResponse(OK));
    }

    @Test
    public void testMultiInformationalResponseAfterActualResponseServer() throws Exception {
        testInformationalResponse(true, false, newResponse(CONTINUE), newResponse(CONTINUE), newResponse(OK));
    }

    @Test
    public void testMultiInformationalResponseAfterActualResponseClient() throws Exception {
        testInformationalResponse(false, false, newResponse(CONTINUE), newResponse(CONTINUE), newResponse(OK));
    }

    @Test
    public void testInformationalResponseWithDataAndTrailersServer() throws Exception {
        testInformationalResponse(true, false, newResponse(CONTINUE), newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()),
                new DefaultHttp3HeadersFrame());
    }

    @Test
    public void testInformationalResponseWithDataAndTrailersClient() throws Exception {
        testInformationalResponse(false, false, newResponse(CONTINUE), newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()),
                new DefaultHttp3HeadersFrame());
    }

    @Test
    public void testInformationalResponseWithDataServer() throws Exception {
        testInformationalResponse(true, false, newResponse(CONTINUE), newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()));
    }

    @Test
    public void testInformationalResponseWithDataClient() throws Exception {
        testInformationalResponse(false, false, newResponse(CONTINUE), newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()));
    }

    @Test
    public void testInformationalResponsePostDataServer() throws Exception {
        testInformationalResponse(true, true, newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()), newResponse(CONTINUE));
    }

    @Test
    public void testInformationalResponsePostDataClient() throws Exception {
        testInformationalResponse(false, true, newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()), newResponse(CONTINUE));
    }

    @Test
    public void testInformationalResponsePostTrailersServer() throws Exception {
        testInformationalResponse(true, true, newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()), new DefaultHttp3HeadersFrame(), newResponse(CONTINUE));
    }

    @Test
    public void testInformationalResponsePostTrailersClient() throws Exception {
        testInformationalResponse(false, true, newResponse(OK),
                new DefaultHttp3DataFrame(Unpooled.buffer()), new DefaultHttp3HeadersFrame(), newResponse(CONTINUE));
    }

    private void testInformationalResponse(boolean server, boolean expectFail, Http3Frame... frames) throws Exception {
        EmbeddedQuicStreamChannel channel = server ? newServerStream() :
                newClientStream(() -> false);

        for (int i = 0; i < frames.length; i++) {
            Http3Frame frame = frames[i];
            Http3Frame read = null;
            try {
                if (server) {
                    assertTrue(channel.writeOutbound(frame));
                    if (expectFail && i == frames.length - 1) {
                        fail();
                    } else {
                        read = channel.readOutbound();
                    }
                } else {
                    assertTrue(channel.writeInbound(frame));
                    if (expectFail && i == frames.length - 1) {
                        fail();
                    } else {
                        read = channel.readInbound();
                    }
                }
                assertEquals(frame, read);
            } catch (Exception e) {
                assertException(H3_FRAME_UNEXPECTED, e);
                if (!server) {
                    verifyClose(H3_FRAME_UNEXPECTED, parent);
                }
            } finally {
                release(read);
            }
        }
        assertFalse(parent.finish());
        assertFalse(channel.finish());
    }

    private void testHeadersFrame(Http3HeadersFrame headersFrame, Http3ErrorCode code) throws Exception {
        EmbeddedQuicStreamChannel channel = newServerStream();
        try {
            assertTrue(channel.writeInbound(headersFrame));
            if (code != null) {
                fail();
            }
        } catch (Throwable cause) {
            if (code == null) {
                throw cause;
            }
            assertException(code, cause);
            assertEquals((Integer) code.code, channel.outputShutdownError());
        }
        // Only expect produced messages when there was no error.
        assertEquals(code == null, channel.finishAndReleaseAll());
    }

    private EmbeddedQuicStreamChannel newClientStream(final BooleanSupplier goAwayReceivedSupplier) throws Exception {
        return newStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel ch) {
                Http3RequestStreamEncodeStateValidator encStateValidator = new Http3RequestStreamEncodeStateValidator();
                Http3RequestStreamDecodeStateValidator decStateValidator = new Http3RequestStreamDecodeStateValidator();
                ch.pipeline().addLast(encStateValidator);
                ch.pipeline().addLast(decStateValidator);
                ch.pipeline().addLast(newClientValidator(goAwayReceivedSupplier, qpackAttributes, decoder,
                        encStateValidator, decStateValidator));
            }
        });
    }

    private EmbeddedQuicStreamChannel newServerStream() throws Exception {
        return newStream(QuicStreamType.BIDIRECTIONAL, newHandler());
    }

    private static Http3Frame newResponse(HttpResponseStatus status) {
        Http3HeadersFrame frame = new DefaultHttp3HeadersFrame();
        frame.headers().status(status.codeAsText());
        return frame;
    }
}
