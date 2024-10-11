package com.gempukku.server.netty

import com.gempukku.context.ContextScheduledExecutor
import com.gempukku.context.lifecycle.LifecycleObserver
import com.gempukku.context.processor.inject.Inject
import com.gempukku.context.processor.inject.InjectProperty
import com.gempukku.context.resolver.expose.Exposes
import com.gempukku.server.BanChecker
import com.gempukku.server.HttpMethod
import com.gempukku.server.HttpProcessingException
import com.gempukku.server.HttpServerSystem
import com.gempukku.server.ServerRequestHandler
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpContentCompressor
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.regex.Pattern

@Exposes(LifecycleObserver::class, HttpServerSystem::class)
class NettyServerSystem : LifecycleObserver, HttpServerSystem {

    @InjectProperty("port")
    private var port: Int = 8080

    @Inject(allowsNull = true)
    private var banChecker: BanChecker? = null

    @Inject
    private lateinit var contextExecutor: ContextScheduledExecutor

    private val registrations: MutableList<Registration> = mutableListOf()

    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

    override fun registerRequestHandler(
        method: HttpMethod,
        uriPattern: Pattern,
        requestHandler: ServerRequestHandler
    ): Runnable {
        val registration = Registration(method, uriPattern, requestHandler)
        registrations.add(registration)
        return Runnable {
            registrations.remove(registration)
        }
    }

    override fun afterContextStartup() {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()

        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .handler(LoggingHandler(LogLevel.INFO))
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                public override fun initChannel(ch: SocketChannel) {
                    val pipeline = ch.pipeline()
                    pipeline.addLast(HttpServerCodec())
                    pipeline.addLast(HttpObjectAggregator(Short.MAX_VALUE.toInt()))
                    pipeline.addLast(HttpContentCompressor())
                    pipeline.addLast(
                        GempukkuHttpRequestHandler(
                            banChecker,
                            { uri, request, remoteIp, responseWriter ->
                                val registration = registrations.firstOrNull {
                                    it.method == request.method().toInternal() && it.uriPattern.matcher(uri).matches()
                                }
                                registration?.let {
                                    contextExecutor.submit {
                                        registration.requestHandler.handleRequest(
                                            uri,
                                            request,
                                            remoteIp,
                                            responseWriter
                                        )
                                    }
                                } ?: throw HttpProcessingException(404)
                            }
                        ))
                }
            })
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)

        val bind = b.bind(port)
        serverChannel = bind.sync().channel()
    }

    override fun beforeContextStopped() {
        serverChannel?.close()?.sync()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
    }
}

private fun io.netty.handler.codec.http.HttpMethod.toInternal(): HttpMethod {
    return when (this) {
        io.netty.handler.codec.http.HttpMethod.POST -> HttpMethod.POST
        else -> HttpMethod.GET
    }
}

private data class Registration(
    val method: HttpMethod,
    val uriPattern: Pattern,
    val requestHandler: ServerRequestHandler,
)