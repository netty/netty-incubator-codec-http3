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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.util.AttributeMap;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.function.LongFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_CONTROL_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_PUSH_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_QPACK_DECODER_STREAM_TYPE;
import static io.netty.incubator.codec.http3.Http3CodecUtils.HTTP3_QPACK_ENCODER_STREAM_TYPE;

@RunWith(Parameterized.class)
public class Http3UnidirectionalStreamInboundHandlerTest {

    private final boolean server;

    @Parameterized.Parameters(name = "{index}: server = {0}")
    public static Object[] parameters() {
        return new Object[] { false, true };
    }

    public Http3UnidirectionalStreamInboundHandlerTest(boolean server) {
        this.server = server;
    }

    @Test
    public void testUnkownStream() {
        EmbeddedChannel channel = newChannel();
        ByteBuf buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, 0x06);
        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        assertNull(channel.pipeline().context(Http3UnidirectionalStreamInboundHandler.class));
        assertTrue(channel.isActive());

        // Write some buffer to the stream. This should be just released.
        ByteBuf someBuffer = Unpooled.buffer();
        assertFalse(channel.writeInbound(someBuffer));
        assertEquals(0, someBuffer.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    public void testUnknownStreamWithCustomHandler() {
        long streamType = 0x06;
        EmbeddedChannel channel = newChannel(v -> {
            assertEquals(streamType, v);
            // Add an handler that will just forward the received bytes.
            return new ChannelInboundHandlerAdapter();
        });
        ByteBuf buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, streamType);
        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        assertNull(channel.pipeline().context(Http3UnidirectionalStreamInboundHandler.class));
        assertTrue(channel.isActive());

        // Write some buffer to the stream. This should be just released.
        ByteBuf someBuffer = Unpooled.buffer().writeLong(9);
        assertTrue(channel.writeInbound(someBuffer.retainedDuplicate()));
        assertTrue(channel.finish());

        Http3TestUtils.assertBufferEquals(someBuffer, channel.readInbound());
        assertNull(channel.readInbound());
    }

    @Test
    public void testPushStream() {
        EmbeddedChannel channel = newChannel();
        ByteBuf buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, HTTP3_PUSH_STREAM_TYPE);
        Http3CodecUtils.writeVariableLengthInteger(buffer, 2);
        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        if (server) {
            Http3TestUtils.verifyClose(Http3ErrorCode.H3_STREAM_CREATION_ERROR, (QuicChannel) channel.parent());
        } else {
            ByteBuf b = Unpooled.buffer();
            assertFalse(channel.writeInbound(b));
            assertEquals(0, b.refCnt());
        }
        assertFalse(channel.finish());
    }

    @Test
    public void testPushStreamNoMaxPushIdFrameSent() {
        testPushStream(false);
    }

    @Test
    public void testPushStreamMaxPushIdFrameSentWithSmallerId() {
        testPushStream(true);
    }

    private void testPushStream(boolean sendPushId) {
        QuicChannel parent = Http3TestUtils.mockParent();
        AttributeMap map = new DefaultAttributeMap();
        when(parent.attr(any())).then(i -> map.attr(i.getArgument(0)));

        Http3ControlStreamOutboundHandler outboundControlHandler = new Http3ControlStreamOutboundHandler(server,
                new DefaultHttp3SettingsFrame(), new CodecHandler());

        EmbeddedChannel outboundControlChannel = new EmbeddedChannel(
                parent, DefaultChannelId.newInstance(), true, false, outboundControlHandler);
        // Let's drain everything that was written while channelActive(...) was called.
        for (;;) {
            Object written = outboundControlChannel.readOutbound();
            if (written == null) {
                break;
            }
            ReferenceCountUtil.release(written);
        }

        if (sendPushId) {
            assertTrue(outboundControlChannel.writeOutbound(new DefaultHttp3MaxPushIdFrame(0)));
            Object push = outboundControlChannel.readOutbound();
            ReferenceCountUtil.release(push);
        }

        Http3UnidirectionalStreamInboundHandler handler = new Http3UnidirectionalStreamInboundHandler(
                v -> new CodecHandler(), new Http3ControlStreamInboundHandler(server, null),
                outboundControlHandler, null);
        EmbeddedChannel channel =  new EmbeddedChannel(parent, DefaultChannelId.newInstance(),
                true, false, handler);

        ByteBuf buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, HTTP3_PUSH_STREAM_TYPE);
        Http3CodecUtils.writeVariableLengthInteger(buffer, 2);

        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        if (server) {
            Http3TestUtils.verifyClose(Http3ErrorCode.H3_STREAM_CREATION_ERROR, (QuicChannel) channel.parent());
        } else {
            ByteBuf b = Unpooled.buffer();
            assertFalse(channel.writeInbound(b));
            assertEquals(0, b.refCnt());
            Http3TestUtils.verifyClose(Http3ErrorCode.H3_ID_ERROR, (QuicChannel) channel.parent());
        }
        assertFalse(channel.finish());
        assertFalse(outboundControlChannel.finish());
    }

    @Test
    public void testControlStream() {
        testStreamSetup(HTTP3_CONTROL_STREAM_TYPE, Http3ControlStreamInboundHandler.class, true);
    }

    @Test
    public void testQpackEncoderStream() {
        testStreamSetup(HTTP3_QPACK_ENCODER_STREAM_TYPE, QpackStreamHandler.class, false);
    }

    @Test
    public void testQpackDecoderStream() {
        testStreamSetup(HTTP3_QPACK_DECODER_STREAM_TYPE, QpackStreamHandler.class, false);
    }

    private void testStreamSetup(long type, Class<? extends ChannelHandler> clazz, boolean hasCodec) {
        EmbeddedChannel channel = newChannel();
        ByteBuf buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, type);
        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        assertNull(channel.pipeline().context(Http3UnidirectionalStreamInboundHandler.class));
        assertNotNull(channel.pipeline().context(clazz));
        if (hasCodec) {
            assertNotNull(channel.pipeline().context(CodecHandler.class));
        } else {
            assertNull(channel.pipeline().context(CodecHandler.class));
        }
        assertFalse(channel.finish());

        channel = new EmbeddedChannel(channel.parent(), DefaultChannelId.newInstance(),
                true, false, new Http3UnidirectionalStreamInboundHandler(
                v -> new CodecHandler(), new Http3ControlStreamInboundHandler(server, null),
                new Http3ControlStreamOutboundHandler(server, new DefaultHttp3SettingsFrame(),
                        new CodecHandler()), null));

        // Try to create the stream a second time, this should fail
        buffer = Unpooled.buffer(8);
        Http3CodecUtils.writeVariableLengthInteger(buffer, type);
        assertFalse(channel.writeInbound(buffer));
        assertEquals(0, buffer.refCnt());
        Http3TestUtils.verifyClose(Http3ErrorCode.H3_STREAM_CREATION_ERROR, (QuicChannel) channel.parent());
        assertFalse(channel.finish());
    }

    private EmbeddedChannel newChannel() {
        return newChannel(null);
    }

    private EmbeddedChannel newChannel(LongFunction<ChannelHandler> factory) {
        QuicChannel parent = Http3TestUtils.mockParent();
        AttributeMap map = new DefaultAttributeMap();
        when(parent.attr(any())).then(i -> map.attr(i.getArgument(0)));
        Http3UnidirectionalStreamInboundHandler handler = new Http3UnidirectionalStreamInboundHandler(
                v -> new CodecHandler(), new Http3ControlStreamInboundHandler(server, null),
                new Http3ControlStreamOutboundHandler(server, new DefaultHttp3SettingsFrame(),
                        new CodecHandler()), factory);
        return new EmbeddedChannel(parent, DefaultChannelId.newInstance(),
                true, false, handler);
    }

    private static final class CodecHandler extends ChannelHandlerAdapter {  }
}
