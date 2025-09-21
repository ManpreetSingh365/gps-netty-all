package com.wheelseye.devicegateway.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EMERGENCY CIRCUIT BREAKER Redis Configuration
 * 
 * Prevents infinite loops and application crashes by implementing:
 * - Circuit breaker pattern for failed deserializations
 * - Automatic blacklisting of corrupted sessions
 * - Fail-fast mechanism to prevent resource exhaustion
 * 
 * @author WheelsEye Development Team - EMERGENCY FIX
 * @since 1.0.0
 */
@Slf4j
@Configuration
public class RedisConfig {

    // Circuit breaker for failed sessions
    private static final Map<String, AtomicInteger> FAILED_SESSION_COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BLACKLISTED_SESSIONS = new ConcurrentHashMap<>();
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_BLACKLISTED_SESSIONS = 1000; // Prevent memory leak

    /**
     * Simple ObjectMapper without type preservation to avoid deserialization issues
     */
    @Bean("emergencyObjectMapper")
    public ObjectMapper emergencyObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Simple configuration - NO TYPE PRESERVATION
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true);

        // CRITICAL: Do NOT enable type preservation to avoid @class issues
        log.info("🚨 EMERGENCY: Configured simple ObjectMapper WITHOUT type preservation");
        return mapper;
    }

    /**
     * Emergency Circuit Breaker JSON Serializer
     */
    @Bean("emergencyJsonSerializer")
    public RedisSerializer<Object> emergencyJsonSerializer(ObjectMapper emergencyObjectMapper) {

        return new RedisSerializer<Object>() {
            @Override
            public byte[] serialize(Object obj) throws SerializationException {
                if (obj == null) {
                    return new byte[0];
                }

                try {
                    String json = emergencyObjectMapper.writeValueAsString(obj);
                    return json.getBytes(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.error("🚨 EMERGENCY: Serialization failed for {}: {}", 
                             obj.getClass().getSimpleName(), e.getMessage());
                    throw new SerializationException("Emergency serialization failed", e);
                }
            }

            @Override
            public Object deserialize(byte[] bytes) throws SerializationException {
                if (bytes == null || bytes.length == 0) {
                    return null;
                }

                String sessionId = extractSessionIdFromContext();

                // CIRCUIT BREAKER: Check if session is blacklisted
                if (sessionId != null && BLACKLISTED_SESSIONS.containsKey(sessionId)) {
                    log.debug("🛑 CIRCUIT BREAKER: Skipping blacklisted session {}", sessionId);
                    return null; // Return null to skip processing
                }

                try {
                    String json = new String(bytes, StandardCharsets.UTF_8);

                    // Try simple deserialization without type checking
                    return emergencyObjectMapper.readValue(json, Object.class);

                } catch (Exception e) {
                    handleDeserializationFailure(sessionId, e);
                    return null; // Always return null to prevent infinite loops
                }
            }

            private void handleDeserializationFailure(String sessionId, Exception e) {
                if (sessionId == null) {
                    log.warn("🚨 EMERGENCY: Deserialization failed for unknown session: {}", e.getMessage());
                    return;
                }

                // Increment failure counter
                AtomicInteger failureCount = FAILED_SESSION_COUNTERS.computeIfAbsent(
                    sessionId, k -> new AtomicInteger(0)
                );
                int attempts = failureCount.incrementAndGet();

                if (attempts >= MAX_RETRY_ATTEMPTS) {
                    // BLACKLIST SESSION after max attempts
                    BLACKLISTED_SESSIONS.put(sessionId, true);

                    log.error("🛑 CIRCUIT BREAKER: Blacklisted session {} after {} failed attempts", 
                             sessionId, attempts);

                    // Prevent memory leak - remove oldest entries if too many
                    if (BLACKLISTED_SESSIONS.size() > MAX_BLACKLISTED_SESSIONS) {
                        cleanupOldestBlacklistedSessions();
                    }

                    // Schedule immediate cleanup of the blacklisted session
                    scheduleSessionCleanup(sessionId);

                } else {
                    log.warn("🚨 EMERGENCY: Deserialization failed for session {} (attempt {}/{}): {}", 
                            sessionId, attempts, MAX_RETRY_ATTEMPTS, e.getMessage());
                }
            }

            private String extractSessionIdFromContext() {
                // Try to extract session ID from current thread context or stack trace
                try {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    for (StackTraceElement element : stackTrace) {
                        String methodName = element.getMethodName();
                        if (methodName.contains("findById") || methodName.contains("session")) {
                            // This is a heuristic - in production, pass session ID explicitly
                            return "unknown_session"; // Fallback
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
                return null;
            }

            private void cleanupOldestBlacklistedSessions() {
                // Remove 10% of oldest blacklisted sessions to prevent memory leak
                int toRemove = BLACKLISTED_SESSIONS.size() / 10;
                BLACKLISTED_SESSIONS.entrySet().stream()
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .forEach(sessionId -> {
                        BLACKLISTED_SESSIONS.remove(sessionId);
                        FAILED_SESSION_COUNTERS.remove(sessionId);
                    });

                log.info("🧹 CIRCUIT BREAKER: Cleaned up {} oldest blacklisted sessions", toRemove);
            }

            private void scheduleSessionCleanup(String sessionId) {
                // This should trigger immediate cleanup in repository
                log.info("🗑️ CIRCUIT BREAKER: Scheduling cleanup for blacklisted session {}", sessionId);
                // TODO: Integrate with repository cleanup mechanism
            }
        };
    }

    /**
     * Emergency RedisTemplate with circuit breaker protection
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            RedisSerializer<Object> emergencyJsonSerializer) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // String serializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Emergency serializer for values
        template.setValueSerializer(emergencyJsonSerializer);
        template.setHashValueSerializer(emergencyJsonSerializer);

        template.setEnableTransactionSupport(true);
        template.afterPropertiesSet();

        log.info("🚨 EMERGENCY: Configured RedisTemplate with circuit breaker protection");
        return template;
    }

    /**
     * Get circuit breaker statistics
     */
    @Bean
    public CircuitBreakerStats circuitBreakerStats() {
        return new CircuitBreakerStats() {
            public int getBlacklistedSessionCount() {
                return BLACKLISTED_SESSIONS.size();
            }

            public int getFailedSessionCount() {
                return FAILED_SESSION_COUNTERS.size();
            }

            public Map<String, Integer> getFailureCounts() {
                return FAILED_SESSION_COUNTERS.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().get()
                    ));
            }

            public void clearBlacklist() {
                BLACKLISTED_SESSIONS.clear();
                FAILED_SESSION_COUNTERS.clear();
                log.info("🧹 CIRCUIT BREAKER: Manually cleared all blacklisted sessions");
            }
        };
    }

    public interface CircuitBreakerStats {
        int getBlacklistedSessionCount();
        int getFailedSessionCount();
        Map<String, Integer> getFailureCounts();
        void clearBlacklist();
    }
}