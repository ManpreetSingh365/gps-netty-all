package com.wheelseye.devicegateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Redis-Only Application Configuration
 * 
 * No JPA/Database dependencies, only Redis for session storage.
 */
@Configuration
@EnableCaching
@EnableAsync
@EnableScheduling
@ConfigurationProperties(prefix = "device.gateway")
@Data
public class DeviceGatewayConfig {

    @Min(1024)
    @Max(65535)
    private int serverPort = 5023;

    @Min(100)
    @Max(100000) 
    private int maxSessions = 10000;

    @Min(300)
    @Max(7200)
    private long sessionIdleTimeout = 1800; // 30 minutes

    @Min(1)
    @Max(32)
    private int workerThreads = Runtime.getRuntime().availableProcessors();

    @Min(1) 
    @Max(4)
    private int bossThreads = 1;

    private boolean enableDeviceConfiguration = true;

    @Min(10)
    @Max(3600)
    private int defaultReportingInterval = 30;

}
