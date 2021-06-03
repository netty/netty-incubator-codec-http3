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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;

final class Http3FrameValidationUtils {

    private Http3FrameValidationUtils() {
        // no instances
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object msg) {
        return (T) msg;
    }

    private static <T> boolean isValid(Class<T> frameType, Object msg) {
        return frameType.isInstance(msg);
    }

    static <T> T validateFrameWritten(Class<T> expectedFrameType, Object msg, ChannelPromise promise) {
        if (isValid(expectedFrameType, msg)) {
            return cast(msg);
        }
        return null;
    }

    static <T> T validateFrameRead(Class<T> expectedFrameType, ChannelHandlerContext ctx, Object msg) {
        if (isValid(expectedFrameType, msg)) {
            return cast(msg);
        }
        return null;
    }

    static void frameTypeUnexpected(ChannelPromise promise, Object frame) {
        String type = StringUtil.simpleClassName(frame);
        ReferenceCountUtil.release(frame);
        promise.setFailure(new Http3Exception(Http3ErrorCode.H3_FRAME_UNEXPECTED,
                "Frame of type " + type + " unexpected"));
    }

    static void frameTypeUnexpected(ChannelHandlerContext ctx, Object frame) {
        ReferenceCountUtil.release(frame);
        Http3CodecUtils.connectionError(ctx, Http3ErrorCode.H3_FRAME_UNEXPECTED, "Frame type unexpected", true);
    }
}
