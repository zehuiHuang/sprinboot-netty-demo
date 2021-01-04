package com.huiying.pipeline;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * Inbound 事件的传播方向为 Head -> Tail，而 Outbound 事件传播方向是 Tail -> Head
 * <p>
 * <p>
 * ExceptionHandler统一的异常处理器
 *
 *       1）InboundHandler顺序执行，OutboundHandler逆序执行
 *
 *       2）InboundHandler之间传递数据，通过ctx.fireChannelRead(msg)
 *
 *       3）InboundHandler通过ctx.write(msg)，则会传递到outboundHandler
 *
 *       4)  使用ctx.write(msg)传递消息，Inbound需要放在结尾，在Outbound之后，不然outboundhandler会不执行；
 *
 *            但是使用channel.write(msg)、pipline.write(msg)情况会不一致，都会执行,那是因为channel和pipline会贯穿整个流。
 *
 *       5)  outBound和Inbound谁先执行，针对客户端和服务端而言，客户端是发起请求再接受数据，先outbound再inbound，服务端则相反
 */
public class HttpServer {
    public void start(int port) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            //入站
                            ch.pipeline()
                                    .addLast(new SampleInBoundHandler("SampleInBoundHandlerA", false))
                                    .addLast(new SampleInBoundHandler("SampleInBoundHandlerB", false))
                                    .addLast(new SampleInBoundHandler("SampleInBoundHandlerC", true));
                            //出站
                            ch.pipeline()
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerA"))
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerB"))
                                    .addLast(new SampleOutBoundHandler("SampleOutBoundHandlerC"))
                                    .addLast(new ExceptionHandler());
                            //异常处理
                            ch.pipeline()
                                    .addLast(new ExceptionSampleInBoundHandler("ExceptionSampleInBoundHandler", false));

                        }
                    })
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            ChannelFuture f = b.bind().sync();
            System.out.println("Http Server started， Listening on " + port);
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        new HttpServer().start(8088);
    }
}
