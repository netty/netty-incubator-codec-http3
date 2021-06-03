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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.internal.ObjectUtil;

import static io.netty.incubator.codec.http3.Http3FrameValidationUtils.frameTypeUnexpected;
import static io.netty.incubator.codec.http3.Http3FrameValidationUtils.validateFrameRead;
import static io.netty.incubator.codec.http3.Http3FrameValidationUtils.validateFrameWritten;

class Http3FrameTypeDuplexValidationHandler<T extends Http3Frame> extends ChannelDuplexHandler {

    private final Class<T> frameType;

    Http3FrameTypeDuplexValidationHandler(Class<T> frameType) {
        this.frameType = ObjectUtil.checkNotNull(frameType, "frameType");
    }

    @Override
    public final void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        T frame = validateFrameWritten(frameType, msg, promise);
        if (frame != null) {
            write(ctx, frame, promise);
        } else {
            writeFrameDiscarded(promise, msg);
        }
    }

    void write(ChannelHandlerContext ctx, T msg, ChannelPromise promise) throws Exception {
        ctx.write(msg, promise);
    }

    void writeFrameDiscarded(ChannelPromise promise, Object discardedFrame) {
        frameTypeUnexpected(promise, discardedFrame);
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final T frame = validateFrameRead(frameType, ctx, msg);
        if (frame != null) {
            channelRead(ctx, frame);
        } else {
            readFrameDiscarded(ctx, msg);
        }
    }

    void channelRead(ChannelHandlerContext ctx, T frame) throws Exception {
        ctx.fireChannelRead(frame);
    }

    void readFrameDiscarded(ChannelHandlerContext ctx, Object discardedFrame) {
        frameTypeUnexpected(ctx, discardedFrame);
    }
}
