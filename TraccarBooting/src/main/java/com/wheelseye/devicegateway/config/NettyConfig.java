package com.wheelseye.devicegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
    @DefaultValue("5023") int port,
    @DefaultValue("1") int bossThreads,
    @DefaultValue("0") int workerThreads,
    @DefaultValue("1024") int backlog,
    @DefaultValue("true") boolean keepAlive,
    @DefaultValue("true") boolean tcpNoDelay,
    @DefaultValue("600") int idleTimeoutSeconds
) {
    
    // Compact constructor with validation
    public NettyConfig {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (bossThreads < 1) {
            throw new IllegalArgumentException("Boss threads must be at least 1");
        }
        if (workerThreads < 0) {
            throw new IllegalArgumentException("Worker threads cannot be negative");
        }
        if (backlog < 1) {
            throw new IllegalArgumentException("Backlog must be at least 1");
        }
        if (idleTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Idle timeout must be at least 1 second");
        }
    }
    
    /**
     * Get effective worker threads (0 means use available processors)
     */
    public int getEffectiveWorkerThreads() {
        return workerThreads == 0 ? Runtime.getRuntime().availableProcessors() : workerThreads;
    }
}