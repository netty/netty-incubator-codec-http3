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
package io.netty.incubator.codec.http3.example.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;

final class Http3RequestHandler extends Http3RequestStreamInboundHandler {
    private static final ByteBuf CONTENT = Unpooled.directBuffer().writeZero(16 * 1024);

    private long bytesRequested;
    private boolean notFound = false;

    @Override
    protected void channelRead(ChannelHandlerContext ctx,
                               Http3HeadersFrame frame, boolean isLast) {
        try {
            CharSequence path = frame.headers().path();
            if (path.length() < 2) {
                notFound = true;
            } else {
                try {
                    bytesRequested = Long.parseLong(path.toString().substring(1));
                } catch (NumberFormatException e) {
                    notFound = true;
                }
            }
            if (isLast) {
                writeResponse(ctx);
            }
        } finally {
            ReferenceCountUtil.release(frame);
        }
    }

    @Override
    protected void channelRead(ChannelHandlerContext ctx,
                               Http3DataFrame frame, boolean isLast) {
        if (isLast) {
            writeResponse(ctx);
        }
        ReferenceCountUtil.release(frame);
    }

    private void writeResponse(ChannelHandlerContext ctx) {
        if (notFound) {
            Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
            headersFrame.headers().status(HttpResponseStatus.NOT_FOUND.codeAsText());
            ctx.writeAndFlush(headersFrame).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            return;
        }
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status(HttpResponseStatus.OK.codeAsText());
        if (bytesRequested == 0) {
            ctx.writeAndFlush(headersFrame).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            return;
        }
        ctx.write(headersFrame);
        writeData(ctx);
    }


    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) {
            writeData(ctx);
        }
        super.channelWritabilityChanged(ctx);
    }

    private void writeData(ChannelHandlerContext ctx) {
        if (bytesRequested > 0) {
            ChannelFuture future;
            do {
                int numBytes = Math.min((int) bytesRequested, CONTENT.readableBytes());
                future = ctx.write(new DefaultHttp3DataFrame(CONTENT.retainedSlice(0, numBytes)));
                bytesRequested -= numBytes;
            } while (bytesRequested > 0 && ctx.channel().isWritable());
            ctx.flush();
            if (bytesRequested <= 0) {
                future.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        }
    }
}