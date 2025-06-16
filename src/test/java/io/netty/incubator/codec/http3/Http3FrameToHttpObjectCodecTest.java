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

package io.netty.incubator.codec.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http3FrameToHttpObjectCodecTest {

    @Test
    public void testUpgradeEmptyFullResponse() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        assertTrue(ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("200", headersFrame.headers().status().toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void encode100ContinueAsHttp2HeadersFrameThatIsNotEndStream() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        assertTrue(ch.writeOutbound(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("100", headersFrame.headers().status().toString());
        assertFalse(ch.isOutputShutdown());

        assertNull(ch.readOutbound());
        assertFalse(ch.finish());
    }

    @Test
    public void encodeNonFullHttpResponse100ContinueIsRejected() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        assertThrows(EncoderException.class, () -> ch.writeOutbound(new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE)));
        ch.finishAndReleaseAll();
    }

    @Test
    public void testUpgradeNonEmptyFullResponse() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, hello)));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("200", headersFrame.headers().status().toString());

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeEmptyFullResponseWithTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        HttpHeaders trailers = response.trailingHeaders();
        trailers.set("key", "value");
        assertTrue(ch.writeOutbound(response));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("200", headersFrame.headers().status().toString());

        Http3HeadersFrame trailersFrame = ch.readOutbound();
        assertEquals("value", trailersFrame.headers().get("key").toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeNonEmptyFullResponseWithTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, hello);
        HttpHeaders trailers = response.trailingHeaders();
        trailers.set("key", "value");
        assertTrue(ch.writeOutbound(response));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("200", headersFrame.headers().status().toString());

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        Http3HeadersFrame trailersFrame = ch.readOutbound();
        assertEquals("value", trailersFrame.headers().get("key").toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeHeaders() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        assertTrue(ch.writeOutbound(response));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        assertEquals("200", headersFrame.headers().status().toString());
        assertFalse(ch.isOutputShutdown());

        assertNull(ch.readOutbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeChunk() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        HttpContent content = new DefaultHttpContent(hello);
        assertTrue(ch.writeOutbound(content));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
            assertFalse(ch.isOutputShutdown());
        } finally {
            dataFrame.release();
        }

        assertNull(ch.readOutbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeEmptyEnd() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ch.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        assertTrue(ch.isOutputShutdown());
        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals(0, dataFrame.content().readableBytes());
        } finally {
            dataFrame.release();
        }

        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeDataEnd() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        LastHttpContent end = new DefaultLastHttpContent(hello, true);
        assertTrue(ch.writeOutbound(end));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testUpgradeDataEndWithTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        LastHttpContent trailers = new DefaultLastHttpContent(hello, true);
        HttpHeaders headers = trailers.trailingHeaders();
        headers.set("key", "value");
        assertTrue(ch.writeOutbound(trailers));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        Http3HeadersFrame headerFrame = ch.readOutbound();
        assertEquals("value", headerFrame.headers().get("key").toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void testDowngradeHeaders() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.path("/");
        headers.method("GET");

        assertTrue(ch.writeInbound(new DefaultHttp3HeadersFrame(headers)));

        HttpRequest request = ch.readInbound();
        assertEquals("/", request.uri());
        assertEquals(HttpMethod.GET, request.method());
        assertEquals(HttpVersion.HTTP_1_1, request.protocolVersion());
        assertFalse(request instanceof FullHttpRequest);
        assertTrue(HttpUtil.isTransferEncodingChunked(request));

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDowngradeHeadersWithContentLength() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.path("/");
        headers.method("GET");
        headers.setInt("content-length", 0);

        assertTrue(ch.writeInbound(new DefaultHttp3HeadersFrame(headers)));

        HttpRequest request = ch.readInbound();
        assertEquals("/", request.uri());
        assertEquals(HttpMethod.GET, request.method());
        assertEquals(HttpVersion.HTTP_1_1, request.protocolVersion());
        assertFalse(request instanceof FullHttpRequest);
        assertFalse(HttpUtil.isTransferEncodingChunked(request));

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDowngradeTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.set("key", "value");

        assertTrue(ch.writeInboundWithFin(new DefaultHttp3HeadersFrame(headers)));

        LastHttpContent trailers = ch.readInbound();
        try {
            assertEquals(0, trailers.content().readableBytes());
            assertEquals("value", trailers.trailingHeaders().get("key"));
            assertFalse(trailers instanceof FullHttpRequest);
        } finally {
            trailers.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDowngradeData() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeInbound(new DefaultHttp3DataFrame(hello)));

        HttpContent content = ch.readInbound();
        try {
            assertEquals("hello world", content.content().toString(CharsetUtil.UTF_8));
            assertFalse(content instanceof LastHttpContent);
        } finally {
            content.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDowngradeEndData() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(true));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeInboundWithFin(new DefaultHttp3DataFrame(hello)));

        HttpContent content = ch.readInbound();
        try {
            assertEquals("hello world", content.content().toString(CharsetUtil.UTF_8));
        } finally {
            content.release();
        }

        LastHttpContent last = ch.readInbound();
        try {
            assertFalse(last.content().isReadable());
            assertTrue(last.trailingHeaders().isEmpty());
        } finally {
            last.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    // client-specific tests
    @Test
    public void testEncodeEmptyFullRequest() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        assertTrue(ch.writeOutbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world")));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeNonEmptyFullRequest() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeOutbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/hello/world", hello)));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("PUT", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeEmptyFullRequestWithTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/hello/world");

        HttpHeaders trailers = request.trailingHeaders();
        trailers.set("key", "value");
        assertTrue(ch.writeOutbound(request));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("PUT", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());

        Http3HeadersFrame trailersFrame = ch.readOutbound();
        assertEquals("value", trailersFrame.headers().get("key").toString());

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeNonEmptyFullRequestWithTrailers() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/hello/world", hello);

        HttpHeaders trailers = request.trailingHeaders();
        trailers.set("key", "value");
        assertTrue(ch.writeOutbound(request));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("PUT", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        Http3HeadersFrame trailersFrame = ch.readOutbound();
        assertEquals("value", trailersFrame.headers().get("key").toString());

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeRequestHeaders() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world");
        assertTrue(ch.writeOutbound(request));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertFalse(ch.isOutputShutdown());

        assertNull(ch.readOutbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeChunkAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        HttpContent content = new DefaultHttpContent(hello);
        assertTrue(ch.writeOutbound(content));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }
        assertFalse(ch.isOutputShutdown());
        assertNull(ch.readOutbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeEmptyEndAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ch.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT);

        assertTrue(ch.isOutputShutdown());
        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals(0, dataFrame.content().readableBytes());
        } finally {
            dataFrame.release();
        }

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeDataEndAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        LastHttpContent end = new DefaultLastHttpContent(hello, true);
        assertTrue(ch.writeOutbound(end));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeTrailersAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        LastHttpContent trailers = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, true);
        HttpHeaders headers = trailers.trailingHeaders();
        headers.set("key", "value");
        assertTrue(ch.writeOutbound(trailers));

        Http3HeadersFrame headerFrame = ch.readOutbound();
        assertEquals("value", headerFrame.headers().get("key").toString());

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeDataEndWithTrailersAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        LastHttpContent trailers = new DefaultLastHttpContent(hello, true);
        HttpHeaders headers = trailers.trailingHeaders();
        headers.set("key", "value");
        assertTrue(ch.writeOutbound(trailers));

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals("hello world", dataFrame.content().toString(CharsetUtil.UTF_8));
        } finally {
            dataFrame.release();
        }

        Http3HeadersFrame headerFrame = ch.readOutbound();
        assertEquals("value", headerFrame.headers().get("key").toString());

        assertTrue(ch.isOutputShutdown());
        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeFullPromiseCompletes() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ChannelFuture writeFuture = ch.writeOneOutbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world"));
        ch.flushOutbound();
        assertTrue(writeFuture.isSuccess());

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeEmptyLastPromiseCompletes() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ChannelFuture f1 = ch.writeOneOutbound(new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world"));
        ChannelFuture f2 = ch.writeOneOutbound(new DefaultLastHttpContent());
        ch.flushOutbound();
        assertTrue(f1.isSuccess());
        assertTrue(f2.isSuccess());

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        Http3DataFrame dataFrame = ch.readOutbound();
        try {
            assertEquals(0, dataFrame.content().readableBytes());
        } finally {
            dataFrame.release();
        }

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeMultiplePromiseCompletes() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ChannelFuture f1 = ch.writeOneOutbound(new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world"));
        ChannelFuture f2 = ch.writeOneOutbound(new DefaultLastHttpContent(
                Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8))));
        ch.flushOutbound();
        assertTrue(f1.isSuccess());
        assertTrue(f2.isSuccess());

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        Http3DataFrame dataFrame = ch.readOutbound();
        assertEquals("foo", dataFrame.content().toString(StandardCharsets.UTF_8));

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeTrailersCompletes() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ChannelFuture f1 = ch.writeOneOutbound(new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world"));
        LastHttpContent last = new DefaultLastHttpContent(
                Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)));
        last.trailingHeaders().add("foo", "bar");
        ChannelFuture f2 = ch.writeOneOutbound(last);
        ch.flushOutbound();
        assertTrue(f1.isSuccess());
        assertTrue(f2.isSuccess());

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("GET", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        Http3DataFrame dataFrame = ch.readOutbound();
        assertEquals("foo", dataFrame.content().toString(StandardCharsets.UTF_8));

        Http3HeadersFrame trailingHeadersFrame = ch.readOutbound();
        assertEquals("bar", trailingHeadersFrame.headers().get("foo").toString());

        assertFalse(ch.finish());
    }

    @Test
    public void testEncodeVoidPromise() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ch.writeOneOutbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/hello/world", Unpooled.wrappedBuffer(new byte[1])),
                ch.voidPromise());
        ch.flushOutbound();

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();
        Http3DataFrame data = ch.readOutbound();
        data.release();
        assertEquals("https", headers.scheme().toString());
        assertEquals("POST", headers.method().toString());
        assertEquals("/hello/world", headers.path().toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    private static final class EncodeCombinationsArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            List<Arguments> arguments = new ArrayList<>();
            for (boolean headers : new boolean[]{false, true}) {
                for (boolean last : new boolean[]{false, true}) {
                    for (boolean nonEmptyContent : new boolean[]{false, true}) {
                        for (boolean hasTrailers : new boolean[]{false, true}) {
                            for (boolean voidPromise : new boolean[]{false, true}) {
                                // this test goes through all the branches of Http3FrameToHttpObjectCodec
                                // and ensures right functionality
                                arguments.add(Arguments.of(headers, last, nonEmptyContent, hasTrailers, voidPromise));
                            }
                        }
                    }
                }
            }
            return arguments.stream();
        }
    }

    @ParameterizedTest(name = "headers: {0}, last: {1}, nonEmptyContent: {2}, hasTrailers: {3}, voidPromise: {4}")
    @ArgumentsSource(value = EncodeCombinationsArgumentsProvider.class)
    public void testEncodeCombination(
            boolean headers,
            boolean last,
            boolean nonEmptyContent,
            boolean hasTrailers,
            boolean voidPromise
    ) {
        ByteBuf content = nonEmptyContent ? Unpooled.wrappedBuffer(new byte[1]) : Unpooled.EMPTY_BUFFER;
        HttpHeaders trailers = new DefaultHttpHeaders();
        if (hasTrailers) {
            trailers.add("foo", "bar");
        }
        HttpObject msg;
        if (headers) {
            if (last) {
                msg = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.POST, "/foo", content, new DefaultHttpHeaders(), trailers);
            } else {
                if (hasTrailers || nonEmptyContent) {
                    // not supported by the netty HTTP/1 model
                    content.release();
                    return;
                }
                msg = new DefaultHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.POST, "/foo", new DefaultHttpHeaders());
            }
        } else {
            if (last) {
                msg = new DefaultLastHttpContent(content, trailers);
            } else {
                if (hasTrailers) {
                    // makes no sense
                    content.release();
                    return;
                }
                msg = new DefaultHttpContent(content);
            }
        }

        List<ChannelPromise> framePromises = new ArrayList<>();
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        framePromises.add(promise);
                        ctx.write(msg, ctx.voidPromise());
                    }
                },
                new Http3FrameToHttpObjectCodec(false)
        );

        ChannelFuture fullPromise = ch.writeOneOutbound(msg, voidPromise ? ch.voidPromise() : ch.newPromise());
        ch.flushOutbound();

        if (headers) {
            Http3HeadersFrame headersFrame = ch.readOutbound();
            assertEquals("https", headersFrame.headers().scheme().toString());
            assertEquals("POST", headersFrame.headers().method().toString());
            assertEquals("/foo", headersFrame.headers().path().toString());
        }
        if (nonEmptyContent) {
            Http3DataFrame dataFrame = ch.readOutbound();
            assertEquals(1, dataFrame.content().readableBytes());
            dataFrame.release();
        }
        if (hasTrailers) {
            Http3HeadersFrame trailersFrame = ch.readOutbound();
            assertEquals("bar", trailersFrame.headers().get("foo"));
        } else if (!nonEmptyContent && !headers) {
            Http3DataFrame dataFrame = ch.readOutbound();
            assertEquals(0, dataFrame.content().readableBytes());
            dataFrame.release();
        }

        if (!voidPromise) {
            assertFalse(fullPromise.isDone());
        }

        assertFalse(ch.isOutputShutdown());
        for (ChannelPromise framePromise : framePromises) {
            framePromise.trySuccess();
        }
        if (last) {
            assertTrue(ch.isOutputShutdown());
        } else {
            assertFalse(ch.isOutputShutdown());
        }
        if (!voidPromise) {
            assertTrue(fullPromise.isDone());
        }
        assertFalse(ch.finish());
    }

    @Test
    public void decode100ContinueHttp2HeadersAsFullHttpResponse() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.scheme(HttpScheme.HTTP.name());
        headers.status(HttpResponseStatus.CONTINUE.codeAsText());

        assertTrue(ch.writeInbound(new DefaultHttp3HeadersFrame(headers)));

        final FullHttpResponse response = ch.readInbound();
        try {
            assertEquals(HttpResponseStatus.CONTINUE, response.status());
            assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
        } finally {
            response.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDecodeResponseHeaders() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.scheme(HttpScheme.HTTP.name());
        headers.status(HttpResponseStatus.OK.codeAsText());

        assertTrue(ch.writeInbound(new DefaultHttp3HeadersFrame(headers)));

        HttpResponse response = ch.readInbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
        assertThat(response).isNotInstanceOf(FullHttpResponse.class);
        assertTrue(HttpUtil.isTransferEncodingChunked(response));

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDecodeResponseHeadersWithContentLength() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.scheme(HttpScheme.HTTP.name());
        headers.status(HttpResponseStatus.OK.codeAsText());
        headers.setInt("content-length", 0);

        assertTrue(ch.writeInbound(new DefaultHttp3HeadersFrame(headers)));

        HttpResponse response = ch.readInbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(HttpVersion.HTTP_1_1, response.protocolVersion());
        assertThat(response).isNotInstanceOf(FullHttpResponse.class);
        assertFalse(HttpUtil.isTransferEncodingChunked(response));

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDecodeResponseTrailersAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        Http3Headers headers = new DefaultHttp3Headers();
        headers.set("key", "value");
        assertTrue(ch.writeInboundWithFin(new DefaultHttp3HeadersFrame(headers)));

        LastHttpContent trailers = ch.readInbound();
        try {
            assertEquals(0, trailers.content().readableBytes());
            assertEquals("value", trailers.trailingHeaders().get("key"));
            assertThat(trailers).isNotInstanceOf(FullHttpRequest.class);
        } finally {
            trailers.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDecodeDataAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeInbound(new DefaultHttp3DataFrame(hello)));

        HttpContent content = ch.readInbound();
        try {
            assertEquals("hello world", content.content().toString(CharsetUtil.UTF_8));
            assertThat(content).isNotInstanceOf(LastHttpContent.class);
        } finally {
            content.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testDecodeEndDataAsClient() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        ByteBuf hello = Unpooled.copiedBuffer("hello world", CharsetUtil.UTF_8);
        assertTrue(ch.writeInboundWithFin(new DefaultHttp3DataFrame(hello)));

        HttpContent content = ch.readInbound();
        try {
            assertEquals("hello world", content.content().toString(CharsetUtil.UTF_8));
        } finally {
            content.release();
        }

        LastHttpContent last = ch.readInbound();
        try {
            assertFalse(last.content().isReadable());
            assertTrue(last.trailingHeaders().isEmpty());
        } finally {
            last.release();
        }

        assertNull(ch.readInbound());
        assertFalse(ch.finish());
    }

    @Test
    public void testHostTranslated() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello/world");
        request.headers().add(HttpHeaderNames.HOST, "example.com");
        assertTrue(ch.writeOutbound(request));

        Http3HeadersFrame headersFrame = ch.readOutbound();
        Http3Headers headers = headersFrame.headers();

        assertEquals("https", headers.scheme().toString());
        assertEquals("example.com", headers.authority().toString());
        assertTrue(ch.isOutputShutdown());

        assertFalse(ch.finish());
    }

    @Test
    public void multipleFramesInFin() throws InterruptedException, CertificateException, ExecutionException {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            // initialized below
                        }
                    })
                    .group(group);

            SelfSignedCertificate cert = new SelfSignedCertificate();

            Channel server = bootstrap.bind("127.0.0.1", 0).sync().channel();
            server.pipeline().addLast(Http3.newQuicServerCodecBuilder()
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamDataBidirectionalRemote(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .sslContext(QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                            .applicationProtocols(Http3.supportedApplicationProtocols()).build())
                    .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(new Http3ServerConnectionHandler(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof Http3HeadersFrame) {
                                        DefaultHttp3HeadersFrame responseHeaders = new DefaultHttp3HeadersFrame();
                                        responseHeaders.headers().status(HttpResponseStatus.OK.codeAsText());
                                        ctx.write(responseHeaders, ctx.voidPromise());
                                        ctx.write(new DefaultHttp3DataFrame(ByteBufUtil.encodeString(
                                                ctx.alloc(), CharBuffer.wrap("foo"), CharsetUtil.UTF_8)),
                                                ctx.voidPromise());
                                        // send a fin, this also flushes
                                        ((DuplexChannel) ctx.channel()).shutdownOutput();
                                    } else {
                                        super.channelRead(ctx, msg);
                                    }
                                }
                            }));
                        }
                    })
                    .build());

            Channel client = bootstrap.bind("127.0.0.1", 0).sync().channel();
            client.config().setAutoRead(true);
            client.pipeline().addLast(Http3.newQuicClientCodecBuilder()
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .sslContext(QuicSslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .applicationProtocols(Http3.supportedApplicationProtocols())
                            .build())
                    .build());

            QuicChannel quicChannel = QuicChannel.newBootstrap(client)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) throws Exception {
                            ch.pipeline().addLast(new Http3ClientConnectionHandler());
                        }
                    })
                    .remoteAddress(server.localAddress())
                    .localAddress(client.localAddress())
                    .connect().get();

            BlockingQueue<Object> received = new LinkedBlockingQueue<>();
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInitializer() {
                @Override
                protected void initRequestStream(QuicStreamChannel ch) {
                    ch.pipeline()
                            .addLast(new Http3FrameToHttpObjectCodec(false))
                            .addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    received.put(msg);
                                }
                            });
                }
            }).get();
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
            request.headers().add(HttpHeaderNames.HOST, "localhost");
            stream.writeAndFlush(request);

            HttpResponse respHeaders = (HttpResponse) received.poll(20, TimeUnit.SECONDS);
            assertNotNull(respHeaders);
            assertEquals(HttpResponseStatus.OK, respHeaders.status());
            assertThat(respHeaders).isNotInstanceOf(LastHttpContent.class);
            HttpContent respBody = (HttpContent) received.poll(20, TimeUnit.SECONDS);
            assertNotNull(respBody);
            assertEquals("foo", respBody.content().toString(CharsetUtil.UTF_8));
            respBody.release();

            LastHttpContent last = (LastHttpContent) received.poll(20, TimeUnit.SECONDS);
            assertNotNull(last);
            last.release();
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void testUnsupportedIncludeSomeDetails() {
        EmbeddedQuicStreamChannel ch = new EmbeddedQuicStreamChannel(new Http3FrameToHttpObjectCodec(false));
        UnsupportedMessageTypeException ex = assertThrows(
                UnsupportedMessageTypeException.class, () -> ch.writeOutbound("unsupported"));
        assertNotNull(ex.getMessage());
        assertFalse(ch.finish());
    }
}
