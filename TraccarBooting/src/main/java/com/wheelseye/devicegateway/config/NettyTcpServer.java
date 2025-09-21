package com.wheelseye.devicegateway.config;

import com.wheelseye.devicegateway.protocol.Protocol;
import com.wheelseye.devicegateway.protocol.ProtocolConfig;
import com.wheelseye.devicegateway.protocol.ProtocolFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import java.util.concurrent.TimeUnit;

/**
 * Fixed Netty TCP Server - Delayed initialization after protocol registration
 */
@Configuration
public class NettyTcpServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyTcpServer.class);

    @Value("${device-gateway.tcp.port:5023}")
    private int port;

    @Value("${device-gateway.tcp.boss-threads:1}")
    private int bossThreads;

    @Value("${device-gateway.tcp.worker-threads:0}")
    private int workerThreads;

    @Value("${device-gateway.tcp.business-threads:4}")
    private int businessThreads;

    @Value("${device-gateway.tcp.idle-timeout:300}")
    private int idleTimeoutSeconds;

    @Value("${device-gateway.tcp.protocol:GT06}")
    private String defaultProtocol;

    private final ProtocolFactory protocolFactory;
    private final ApplicationContext applicationContext;

    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessExecutorGroup;
    private volatile boolean serverStarted = false;

    public NettyTcpServer(ProtocolFactory protocolFactory, ApplicationContext applicationContext) {
        this.protocolFactory = protocolFactory;
        this.applicationContext = applicationContext;
        
        logger.info("🔧 NettyTcpServer bean created, waiting for protocol registration...");
    }

    /**
     * Start server AFTER protocols are registered
     */
    @EventListener
    @Order(10) // Run after protocol registration (Order 1-2)
    public void startServerWhenReady(ApplicationReadyEvent event) {
        if (!serverStarted) {
            try {
                startServer();
            } catch (Exception e) {
                logger.error("💥 Failed to start Netty TCP Server during ApplicationReadyEvent", e);
                throw new RuntimeException("Server startup failed", e);
            }
        }
    }

    private void startServer() throws Exception {
        logger.info("🚀 Starting Netty TCP Server on port {} with protocol {}", port, defaultProtocol);

        // Wait a moment for protocol registration to complete
        Thread.sleep(100);

        // Verify protocol is registered with detailed error reporting
        if (!protocolFactory.isRegistered(defaultProtocol)) {
            var availableProtocols = protocolFactory.getRegisteredProtocols();
            var errorMsg = String.format(
                "💥 Protocol '%s' is not registered!\n" +
                "📋 Available protocols: %s\n" +
                "📊 Total registered: %d\n" +
                "💡 Hint: Check if Gt06Protocol bean is created and ProtocolRegistrationConfig is working",
                defaultProtocol, availableProtocols, protocolFactory.getRegisteredCount()
            );
            
            logger.error(errorMsg.toString());
            throw new IllegalStateException(errorMsg.toString());
        }

        logger.info("✅ Protocol '{}' found, proceeding with server startup", defaultProtocol);

        // Create event loop groups
        bossGroup = new NioEventLoopGroup(bossThreads);
        int effectiveWorkerThreads = workerThreads == 0 ? Runtime.getRuntime().availableProcessors() : workerThreads;
        workerGroup = new NioEventLoopGroup(effectiveWorkerThreads);
        businessExecutorGroup = new DefaultEventExecutorGroup(businessThreads);

        logger.info("🔧 Created event loops: boss={}, worker={}, business={}", 
            bossThreads, effectiveWorkerThreads, businessThreads);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new LoggingHandler(LogLevel.INFO))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        setupPipeline(ch);
                    }
                });

            // Bind and start accepting connections
            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            serverStarted = true;
            
            logger.info("🎉 Netty TCP Server started successfully!");
            logger.info("🌐 Listening on port: {}", port);
            logger.info("📋 Using protocol: {}", defaultProtocol);
            logger.info("📊 Registered protocols: {}", protocolFactory.getRegisteredProtocols());

        } catch (Exception e) {
            logger.error("💥 Failed to start Netty TCP Server", e);
            shutdown();
            throw e;
        }
    }

    private void setupPipeline(SocketChannel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();

        logger.debug("🔧 Setting up pipeline for channel: {}", channel.remoteAddress());

        // 1. Idle State Handler
        pipeline.addLast("idleState", new IdleStateHandler(
            idleTimeoutSeconds, idleTimeoutSeconds, idleTimeoutSeconds, TimeUnit.SECONDS));

        // Get the protocol configuration
        Protocol protocol = protocolFactory.get(defaultProtocol)
            .orElseThrow(() -> new IllegalStateException("Protocol not found: " + defaultProtocol));

        ProtocolConfig config = protocol.config();
        logger.debug("📋 Protocol config: {}", config);

        // 2. Frame Decoder
        if (config.useLengthFieldFraming()) {
            logger.debug("🔧 Using LengthFieldBasedFrameDecoder");
            pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(
                8192,
                config.lengthFieldOffset(),
                config.lengthFieldLength(),
                config.lengthAdjustment(),
                config.initialBytesToStrip()));
        } else {
            logger.debug("🔧 Using custom frame decoder: {}", protocol.frameDecoder().getClass().getSimpleName());
            ChannelHandler frameDecoder = protocol.frameDecoder();
            if (frameDecoder != null) {
                pipeline.addLast("frameDecoder", frameDecoder);
            }
        }

        // 3. Protocol Decoder
        ChannelHandler protocolDecoder = protocol.protocolDecoder();
        if (protocolDecoder != null) {
            logger.debug("🔧 Adding protocol decoder: {}", protocolDecoder.getClass().getSimpleName());
            pipeline.addLast("protocolDecoder", protocolDecoder);
        } else {
            throw new IllegalStateException("Protocol " + protocol.name() + " must provide a protocol decoder");
        }

        // 4. Business Handler - Get lazily to avoid circular dependency
        try {
            Object businessHandler = applicationContext.getBean("deviceBusinessHandler");
            if (businessHandler instanceof ChannelHandler) {
                logger.debug("🔧 Adding business handler: {}", businessHandler.getClass().getSimpleName());
                pipeline.addLast(businessExecutorGroup, "businessHandler", (ChannelHandler) businessHandler);
            }
        } catch (Exception e) {
            logger.warn("⚠️ Business handler not available, using fallback: {}", e.getMessage());
            // Create a simple pass-through handler as fallback
            pipeline.addLast(businessExecutorGroup, "businessHandler", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    logger.debug("🔄 Fallback handler processing message: {}", msg.getClass().getSimpleName());
                    ctx.fireChannelRead(msg);
                }
            });
        }

        // 5. Protocol Encoder
        ChannelHandler protocolEncoder = protocol.protocolEncoder();
        if (protocolEncoder != null) {
            logger.debug("🔧 Adding protocol encoder: {}", protocolEncoder.getClass().getSimpleName());
            pipeline.addLast("protocolEncoder", protocolEncoder);
        } else {
            throw new IllegalStateException("Protocol " + protocol.name() + " must provide a protocol encoder");
        }

        // 6. Length Field Prepender (optional)
        if (config.prependLengthOnOutbound()) {
            logger.debug("🔧 Adding length field prepender");
            pipeline.addLast("lengthPrepender", new LengthFieldPrepender(config.lengthFieldLength()));
        }

        logger.debug("✅ Pipeline configured: {}", pipeline.names());
    }

    @PreDestroy
    public void shutdown() {
        if (!serverStarted) {
            return;
        }
        
        logger.info("🛑 Shutting down Netty TCP Server...");
        
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            logger.warn("⚠️ Interrupted while closing server channel", e);
            Thread.currentThread().interrupt();
        } finally {
            if (businessExecutorGroup != null) {
                businessExecutorGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            serverStarted = false;
            logger.info("✅ Netty TCP Server shutdown completed");
        }
    }
    
    /**
     * Get server status for health checks
     */
    public boolean isRunning() {
        return serverStarted && serverChannel != null && serverChannel.isActive();
    }
}