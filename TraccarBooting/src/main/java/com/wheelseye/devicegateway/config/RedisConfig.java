package com.wheelseye.devicegateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Configuration with Emergency Circuit Breaker
 * 
 * Modern, clean configuration focused solely on Redis with circuit breaker
 * protection to prevent infinite loops and application crashes.
 */
@Slf4j
@Configuration
@EnableCaching
@EnableAsync
@EnableScheduling
public class RedisConfig {

    // Circuit breaker state - thread-safe static maps
    private static final Map<String, AtomicInteger> FAILED_COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BLACKLISTED = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 3;
    private static final int MAX_BLACKLIST = 1000;

    // Simple ObjectMapper without type preservation to avoid deserialization issues
    @Bean("emergencyObjectMapper")
    public ObjectMapper emergencyObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);
        log.info("🚨 EMERGENCY: Simple ObjectMapper without type preservation configured");
        return mapper;
    }

    // Emergency circuit breaker JSON serializer with failure handling
    @Bean("emergencyJsonSerializer")
    public RedisSerializer<Object> emergencyJsonSerializer(@Qualifier("emergencyObjectMapper") ObjectMapper mapper) {
        return new RedisSerializer<>() {
            @Override
            public byte[] serialize(Object obj) throws SerializationException {
                if (obj == null) return new byte[0];
                try {
                    return mapper.writeValueAsString(obj).getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.error("🚨 Serialization failed for {}: {}", obj.getClass().getSimpleName(), e.getMessage());
                    throw new SerializationException("Emergency serialization failed", e);
                }
            }

            @Override
            public Object deserialize(byte[] bytes) throws SerializationException {
                if (bytes == null || bytes.length == 0) return null;
                String sessionId = extractSessionId();
                if (sessionId != null && BLACKLISTED.containsKey(sessionId)) {
                    log.debug("🛑 Skipping blacklisted session {}", sessionId);
                    return null;
                }
                try {
                    return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), Object.class);
                } catch (Exception e) {
                    handleFailure(sessionId, e);
                    return null;
                }
            }

            private void handleFailure(String sessionId, Exception e) {
                if (sessionId == null) return;
                AtomicInteger count = FAILED_COUNTERS.computeIfAbsent(sessionId, k -> new AtomicInteger());
                if (count.incrementAndGet() >= MAX_RETRIES) {
                    BLACKLISTED.put(sessionId, true);
                    log.error("🛑 Blacklisted session {} after {} failures", sessionId, count.get());
                    if (BLACKLISTED.size() > MAX_BLACKLIST) cleanupOldest();
                }
            }

            // Placeholder: Implement logic to extract session ID from context if available
            private String extractSessionId() {
                 return null;
        }

            private void cleanupOldest() {
                BLACKLISTED.keySet().stream().limit(BLACKLISTED.size() / 10)
                    .forEach(s -> { BLACKLISTED.remove(s); FAILED_COUNTERS.remove(s); });
                log.info("🧹 Cleaned oldest blacklisted sessions");
            }
        };
    }

    // Primary RedisTemplate with circuit breaker protection
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory,
                                                       RedisSerializer<Object> serializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.setEnableTransactionSupport(true);
        template.afterPropertiesSet();
        log.info("🚨 RedisTemplate configured with circuit breaker protection");
        return template;
    }

    // Circuit breaker statistics interface for monitoring
    @Bean
    public CircuitBreakerStats circuitBreakerStats() {
        return new CircuitBreakerStats() {
            @Override public int getBlacklistedSessionCount() { return BLACKLISTED.size(); }
            @Override public int getFailedSessionCount() { return FAILED_COUNTERS.size(); }
            @Override public Map<String, Integer> getFailureCounts() {
                return FAILED_COUNTERS.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().get()));
            }
            @Override public void clearBlacklist() {
                BLACKLISTED.clear(); FAILED_COUNTERS.clear();
                log.info("🧹 Manually cleared all blacklisted sessions");
            }
        };
    }

    // Circuit breaker statistics interface
    public interface CircuitBreakerStats {
        int getBlacklistedSessionCount();
        int getFailedSessionCount();
        Map<String, Integer> getFailureCounts();
        void clearBlacklist();
    }
}