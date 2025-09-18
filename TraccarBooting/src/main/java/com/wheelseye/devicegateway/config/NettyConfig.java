package com.wheelseye.devicegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

//* Immutable Netty TCP Server configuration.
// port – TCP port where the Netty server listens for incoming connections. (default: 5023)
// bossThreads – Number of threads dedicated to accepting new connections. (default: 1)
// workerThreads – Number of threads responsible for processing I/O and handling connected clients. (default: 4)
// backlog – Maximum number of pending connections allowed in the queue before new connections are rejected. (default: 128)
// keepAlive – Enables TCP keep-alive to detect and maintain live connections. (default: true)
// tcpNoDelay – Disables Nagle’s algorithm to send packets immediately without delay. (default: true)
// idleTimeoutSeconds – Duration in seconds after which inactive connections are automatically closed. (default: 600)
@ConfigurationProperties(prefix = "device-gateway.tcp")
public record NettyConfig(
        int port,
        int bossThreads,
        int workerThreads,
        int backlog,
        boolean keepAlive,
        boolean tcpNoDelay,
        int idleTimeoutSeconds
) {
    public NettyConfig {
        if (port == 0) port = 5023;
        if (bossThreads == 0) bossThreads = 1;
        if (workerThreads == 0) workerThreads = 4;
        if (backlog == 0) backlog = 128;
        if (keepAlive) keepAlive = true;
        if (tcpNoDelay) tcpNoDelay = true;
        if (idleTimeoutSeconds == 0) idleTimeoutSeconds = 600;
    }
}
