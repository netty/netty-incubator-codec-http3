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

import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;

import static io.netty.incubator.codec.quic.QuicStreamType.BIDIRECTIONAL;

abstract class Http3PushStreamInitializer extends ChannelInitializer<QuicStreamChannel> {

    static void verifyIsUnidirectional(QuicStreamChannel ch) {
        if (ch.type() == BIDIRECTIONAL) {
            throw new IllegalArgumentException("Using push stream initializer for bi-directional stream: " +
                    ch.streamId());
        }
    }

    /**
     * Initialize the {@link QuicStreamChannel} to handle {@link Http3PushStreamFrame}s. At the point of calling this
     * method it is already valid to write {@link Http3PushStreamFrame}s as the codec is already in the pipeline.
     *
     * @param ch the {QuicStreamChannel} for the push stream.
     */
    protected abstract void initPushStream(QuicStreamChannel ch);
}
