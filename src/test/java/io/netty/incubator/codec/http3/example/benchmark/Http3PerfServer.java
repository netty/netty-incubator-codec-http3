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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.unix.SegmentedDatagramPacket;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringChannelOption;
import io.netty.incubator.channel.uring.IOUringDatagramChannel;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelOption;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.SegmentedDatagramPacketAllocator;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.logging.InternalLogLevel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public final class Http3PerfServer {

    static final int PORT = 9999;

    private enum TransportType {
        Epoll,
        Iouring,
        Nio;

        static TransportType defaultTransport() {
            if (io.netty.channel.epoll.Epoll.isAvailable()) {
                return Epoll;
            }
            if (io.netty.incubator.channel.uring.IOUring.isAvailable()) {
                return Iouring;
            }
            return Nio;
        }
    }

    private Http3PerfServer() { }

    public static void main(String... args) throws Exception {
        // keep the native lib for profiling.
        System.setProperty("io.netty.native.deleteLibAfterLoading", "false");

        final TransportType transportType;
        if (args.length == 1) {
            transportType = TransportType.valueOf(args[0]);
        } else {
            transportType = TransportType.defaultTransport();
        }

        InternalLoggerFactory.setDefaultFactory(new InternalLoggerFactory() {
            @Override
            protected InternalLogger newInstance(String name) {
                return new InternalLogger() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public boolean isTraceEnabled() {
                        return false;
                    }

                    @Override
                    public void trace(String msg) {
                        // NOOP
                    }

                    @Override
                    public void trace(String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void trace(String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void trace(String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void trace(String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void trace(Throwable t) {
                        // NOOP
                    }

                    @Override
                    public boolean isDebugEnabled() {
                        return false;
                    }

                    @Override
                    public void debug(String msg) {
                        // NOOP
                    }

                    @Override
                    public void debug(String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void debug(String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void debug(String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void debug(String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void debug(Throwable t) {
                        // NOOP
                    }

                    @Override
                    public boolean isInfoEnabled() {
                        return false;
                    }

                    @Override
                    public void info(String msg) {
                        // NOOP
                    }

                    @Override
                    public void info(String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void info(String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void info(String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void info(String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void info(Throwable t) {
                        // NOOP
                    }

                    @Override
                    public boolean isWarnEnabled() {
                        return false;
                    }

                    @Override
                    public void warn(String msg) {
                        // NOOP
                    }

                    @Override
                    public void warn(String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void warn(String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void warn(String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void warn(String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void warn(Throwable t) {
                        // NOOP
                    }

                    @Override
                    public boolean isErrorEnabled() {
                        return false;
                    }

                    @Override
                    public void error(String msg) {
                        // NOOP
                    }

                    @Override
                    public void error(String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void error(String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void error(String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void error(String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void error(Throwable t) {
                        // NOOP
                    }

                    @Override
                    public boolean isEnabled(InternalLogLevel level) {
                        return false;
                    }

                    @Override
                    public void log(InternalLogLevel level, String msg) {
                        // NOOP
                    }

                    @Override
                    public void log(InternalLogLevel level, String format, Object arg) {
                        // NOOP
                    }

                    @Override
                    public void log(InternalLogLevel level, String format, Object argA, Object argB) {
                        // NOOP
                    }

                    @Override
                    public void log(InternalLogLevel level, String format, Object... arguments) {
                        // NOOP
                    }

                    @Override
                    public void log(InternalLogLevel level, String msg, Throwable t) {
                        // NOOP
                    }

                    @Override
                    public void log(InternalLogLevel level, Throwable t) {
                        // NOOP
                    }
                };
            }
        });

        final int maxDatagramSize = 1350;
        final int maxDatagramsForRecvmmsg = 16;
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        SelfSignedCertificate cert = new SelfSignedCertificate();
        QuicSslContext sslContext = QuicSslContextBuilder.forServer(cert.key(), null, cert.cert())
                .applicationProtocols(Http3.supportedApplicationProtocols()).earlyData(true).build();
        QuicServerCodecBuilder codecBuilder = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                .maxRecvUdpPayloadSize(maxDatagramSize)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamDataBidirectionalRemote(1000000)
                .initialMaxStreamsBidirectional(100000)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                ((QuicChannel) ctx.channel()).collectStats().addListener(f -> {
                                    System.err.println(f.getNow());
                                });
                            }
                        });
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                                new ChannelInitializer<QuicStreamChannel>() {
                                    // Called for each request-stream,
                                    @Override
                                    protected void initChannel(QuicStreamChannel ch) {
                                        ch.pipeline().addLast(new Http3RequestHandler());
                                    }
                                }));
                    }
                });
        EventLoopGroup group = null;
        try {
            Bootstrap bs = new Bootstrap();
            final Class<? extends DatagramChannel> channelClass;

            SegmentedDatagramPacketAllocator datagramPacketAllocator = new SegmentedDatagramPacketAllocator() {
                @Override
                public int maxNumSegments() {
                    return 8;
                }

                @Override
                public DatagramPacket newPacket(ByteBuf buffer, int segmentSize,
                                                InetSocketAddress remoteAddress) {
                    return new SegmentedDatagramPacket(buffer, segmentSize, remoteAddress);
                }
            };

            switch (transportType) {
                case Iouring:
                    IOUring.ensureAvailability();
                    group = new IOUringEventLoopGroup(1);
                    channelClass = IOUringDatagramChannel.class;

                    bs.option(IOUringChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE, maxDatagramSize)
                            .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(
                                    maxDatagramsForRecvmmsg * maxDatagramSize));

                    if (IOUringDatagramChannel.isSegmentedDatagramPacketSupported()) {
                        codecBuilder.option(QuicChannelOption.SEGMENTED_DATAGRAM_PACKET_ALLOCATOR, datagramPacketAllocator);
                    }
                    break;
                case Epoll:
                    group = new EpollEventLoopGroup(1);
                    channelClass = EpollDatagramChannel.class;

                    // recvmmsg should be used
                    bs.option(EpollChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE, maxDatagramSize);
                    bs.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(
                            maxDatagramsForRecvmmsg * maxDatagramSize));
                    if (EpollDatagramChannel.isSegmentedDatagramPacketSupported()) {
                        codecBuilder.option(
                                QuicChannelOption.SEGMENTED_DATAGRAM_PACKET_ALLOCATOR, datagramPacketAllocator);
                    }
                    break;
                case Nio:
                    group = new NioEventLoopGroup(1);
                    channelClass = NioDatagramChannel.class;
                    bs.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(maxDatagramSize));
                    break;
                default:
                    throw new IllegalStateException();
            }

            ChannelHandler codec = codecBuilder.build();
            Channel channel = bs.group(group)
                    .channel(channelClass)
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(DatagramChannel datagramChannel) {
                            datagramChannel.pipeline().addLast(codec);
                        }
                    })
                    .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(Integer.MAX_VALUE, Integer.MAX_VALUE))
                    .bind(new InetSocketAddress(PORT)).sync().channel();
            System.out.println("Http3PerfServer started: " + channel.localAddress());
            channel.closeFuture().sync();
        } finally {
            if (group != null) {
                group.shutdownGracefully();
            }
        }
    }
}
