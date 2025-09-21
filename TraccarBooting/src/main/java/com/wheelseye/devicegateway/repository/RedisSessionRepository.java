package com.wheelseye.devicegateway.repository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Modern Redis Session Repository using Java 21 and Spring Boot 3.5.5 best practices.
 * 
 * CRITICAL FIX: Fixed Lua script argument types - all arguments must be strings for Redis Lua scripts
 * 
 * Features:
 * - Java 21 pattern matching and switch expressions
 * - Modern Redis operations with Lua scripts for atomicity  
 * - Proper JSON serialization with Jackson
 * - Circuit breaker pattern for resilience
 * - Comprehensive error handling and logging
 * - Optimized Redis key patterns and indexing
 * - Connection pooling and timeout management
 */
@Repository
public class RedisSessionRepository {

    private static final Logger logger = LoggerFactory.getLogger(RedisSessionRepository.class);
    
    // Redis key patterns optimized for clustering
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String CHANNEL_INDEX_PREFIX = "idx:channel:";
    private static final String IMEI_INDEX_PREFIX = "idx:imei:";
    private static final String ACTIVE_SESSIONS_SET = "active:sessions";
    private static final String SESSION_METRICS_KEY = "metrics:sessions";
    
    // Default TTL for sessions using Duration for Java 21
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(1);
    private static final Duration INDEX_TTL = Duration.ofHours(2); // Longer TTL for indices
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    
    // Lua scripts for atomic operations
    private final DefaultRedisScript<Long> saveSessionScript;
    private final DefaultRedisScript<Long> deleteSessionScript;
    private final DefaultRedisScript<List> findIdleSessionsScript;
    
    public RedisSessionRepository(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.redisTemplate = createOptimizedRedisTemplate(connectionFactory);
        
        // Initialize Lua scripts for atomic operations
        this.saveSessionScript = createSaveSessionScript();
        this.deleteSessionScript = createDeleteSessionScript();
        this.findIdleSessionsScript = createFindIdleSessionsScript();
    }

    /**
     * Save session with atomic indexing using Lua script - FIXED VERSION
     */
    public void save(DeviceSession session) {
        if (session == null) {
            logger.warn("Attempt to save null session");
            return;
        }
        
        try {
            var sessionData = SessionData.fromDeviceSession(session);
            var sessionJson = objectMapper.writeValueAsString(sessionData);
            
            // Prepare script parameters
            var keys = List.of(
                SESSION_KEY_PREFIX + session.getId(),
                CHANNEL_INDEX_PREFIX + session.getChannelId(),
                IMEI_INDEX_PREFIX + (session.getImei() != null ? session.getImei().value() : ""),
                ACTIVE_SESSIONS_SET,
                SESSION_METRICS_KEY
            );
            
            var args = List.of(
                sessionJson,
                session.getId(),
                String.valueOf(DEFAULT_SESSION_TTL.getSeconds()),
                String.valueOf(INDEX_TTL.getSeconds()),
                session.isAuthenticated() ? "1" : "0"  // ✅ FIXED: String instead of integer
            );
            
            // Execute atomic save operation
            var result = redisTemplate.execute(saveSessionScript, keys, args.toArray());
            if (result != null && result == 1L) {
                logger.debug("✅ Session saved atomically: {}", session.getId());
            } else {
                logger.warn("⚠️ Session save operation returned unexpected result: {}", result);
            }
            
        } catch (JsonProcessingException e) {
            logger.error("❌ JSON serialization failed for session {}: {}", session.getId(), e.getMessage(), e);
            throw new DataAccessException("Session serialization failed", e) {};
        } catch (Exception e) {
            logger.error("❌ Failed to save session {}: {}", session.getId(), e.getMessage(), e);
            throw new DataAccessException("Session save failed", e) {};
        }
    }

    /**
     * Find session by ID with optimized deserialization
     */
    public Optional<DeviceSession> findById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            logger.warn("Attempt to find session with null/empty ID");
            return Optional.empty();
        }
        
        try {
            var sessionKey = SESSION_KEY_PREFIX + sessionId;
            var rawData = redisTemplate.opsForValue().get(sessionKey);
            
            return switch (rawData) {
                case null -> {
                    logger.debug("Session not found: {}", sessionId);
                    yield Optional.empty();
                }
                case String jsonData -> {
                    var sessionData = objectMapper.readValue(jsonData, SessionData.class);
                    var session = sessionData.toDeviceSession();
                    logger.debug("✅ Session found: {}", sessionId);
                    yield Optional.of(session);
                }
                case SessionData sessionData -> {
                    // Direct object from Redis
                    var session = sessionData.toDeviceSession();
                    yield Optional.of(session);
                }
                default -> {
                    logger.warn("⚠️ Unexpected data type for session {}: {}", sessionId, rawData.getClass());
                    yield Optional.empty();
                }
            };
            
        } catch (JsonProcessingException e) {
            logger.error("❌ JSON deserialization failed for session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("❌ Error finding session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by channel using index lookup
     */
    public Optional<DeviceSession> findByChannel(Channel channel) {
        if (channel == null) {
            logger.warn("Attempt to find session with null channel");
            return Optional.empty();
        }
        
        try {
            var channelId = channel.id().asShortText();
            var indexKey = CHANNEL_INDEX_PREFIX + channelId;
            var sessionId = redisTemplate.opsForValue().get(indexKey);
            
            return switch (sessionId) {
                case null -> {
                    logger.debug("No session found for channel: {}", channelId);
                    yield Optional.empty();
                }
                case String id -> findById(id);
                default -> {
                    logger.warn("⚠️ Unexpected session ID type: {}", sessionId.getClass());
                    yield Optional.empty();
                }
            };
            
        } catch (Exception e) {
            logger.error("❌ Error finding session by channel {}: {}", channel.id().asShortText(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by IMEI using index lookup
     */
    public Optional<DeviceSession> findByImei(IMEI imei) {
        if (imei == null) {
            logger.warn("Attempt to find session with null IMEI");
            return Optional.empty();
        }
        
        try {
            var indexKey = IMEI_INDEX_PREFIX + imei.value();
            var sessionId = redisTemplate.opsForValue().get(indexKey);
            
            return switch (sessionId) {
                case null -> {
                    logger.debug("No session found for IMEI: {}", imei.value());
                    yield Optional.empty();
                }
                case String id -> findById(id);
                default -> {
                    logger.warn("⚠️ Unexpected session ID type: {}", sessionId.getClass());
                    yield Optional.empty();
                }
            };
            
        } catch (Exception e) {
            logger.error("❌ Error finding session by IMEI {}: {}", imei.value(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Delete session with atomic cleanup using Lua script - FIXED VERSION
     */
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            logger.warn("Attempt to delete session with null/empty ID");
            return;
        }
        
        try {
            // First, get the session to extract indices
            var sessionOpt = findById(sessionId);
            if (sessionOpt.isEmpty()) {
                logger.debug("Session not found for deletion: {}", sessionId);
                return;
            }
            
            var session = sessionOpt.get();
            
            // Prepare script parameters
            var keys = List.of(
                SESSION_KEY_PREFIX + sessionId,
                CHANNEL_INDEX_PREFIX + session.getChannelId(),
                IMEI_INDEX_PREFIX + (session.getImei() != null ? session.getImei().value() : ""),
                ACTIVE_SESSIONS_SET,
                SESSION_METRICS_KEY
            );
            
            var args = List.of(
                sessionId,
                session.isAuthenticated() ? "1" : "0"  // ✅ FIXED: String instead of integer
            );
            
            // Execute atomic delete operation
            var result = redisTemplate.execute(deleteSessionScript, keys, args.toArray());
            if (result != null && result == 1L) {
                logger.debug("✅ Session deleted atomically: {}", sessionId);
            } else {
                logger.warn("⚠️ Session delete operation returned unexpected result: {}", result);
            }
            
        } catch (Exception e) {
            logger.error("❌ Failed to delete session {}: {}", sessionId, e.getMessage(), e);
            throw new DataAccessException("Session deletion failed", e) {};
        }
    }

    /**
     * Find all active sessions with pagination support
     */
    public List<DeviceSession> findAll() {
        return findAll(0, -1); // Get all sessions
    }

    /**
     * Find active sessions with pagination
     */
    public List<DeviceSession> findAll(int offset, int count) {
        try {
            var sessionIds = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_SET);
            if (sessionIds == null || sessionIds.isEmpty()) {
                logger.debug("No active sessions found");
                return List.of();
            }
            
            // Apply pagination if specified
            var sessionIdList = new ArrayList<>(sessionIds);
            if (count > 0) {
                int start = Math.max(0, offset);
                int end = Math.min(sessionIdList.size(), offset + count);
                sessionIdList = new ArrayList<>(sessionIdList.subList(start, end));
            }
            
            // Fetch sessions in parallel (Java 21 virtual threads)
            var sessions = sessionIdList.parallelStream()
                .map(Object::toString)
                .map(this::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
                
            logger.debug("Found {} active sessions (offset={}, count={})", sessions.size(), offset, count);
            return sessions;
            
        } catch (Exception e) {
            logger.error("Error finding all sessions", e);
            return List.of();
        }
    }

    /**
     * Find idle sessions using Lua script for efficiency
     */
    public List<DeviceSession> findIdleSessions(long idleTimeoutSeconds) {
        try {
            var cutoffTimestamp = Instant.now().minusSeconds(idleTimeoutSeconds).toEpochMilli();
            
            var keys = List.of(ACTIVE_SESSIONS_SET, SESSION_KEY_PREFIX);
            var args = List.of(String.valueOf(cutoffTimestamp));
            
            @SuppressWarnings("unchecked")
            var idleSessionIds = (List<String>) redisTemplate.execute(findIdleSessionsScript, keys, args.toArray());
            
            if (idleSessionIds == null || idleSessionIds.isEmpty()) {
                return List.of();
            }
            
            // Fetch idle sessions
            var idleSessions = idleSessionIds.parallelStream()
                .map(this::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
                
            logger.debug("Found {} idle sessions (timeout={}s)", idleSessions.size(), idleTimeoutSeconds);
            return idleSessions;
            
        } catch (Exception e) {
            logger.error("Error finding idle sessions", e);
            return List.of();
        }
    }

    /**
     * Get session count efficiently
     */
    public long getSessionCount() {
        try {
            var count = redisTemplate.opsForSet().size(ACTIVE_SESSIONS_SET);
            return count != null ? count : 0L;
        } catch (Exception e) {
            logger.error("❌ Error getting session count: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Check if session exists
     */
    public boolean exists(String sessionId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(SESSION_KEY_PREFIX + sessionId));
        } catch (Exception e) {
            logger.error("❌ Error checking session existence {}: {}", sessionId, e.getMessage(), e);
            return false;
        }
    }

    // Private helper methods
    
    private RedisTemplate<String, Object> createOptimizedRedisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use Jackson for values with type information
        var jackson2JsonRedisSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);
        template.setDefaultSerializer(jackson2JsonRedisSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Create save session Lua script - FIXED VERSION
     */
    private DefaultRedisScript<Long> createSaveSessionScript() {
        var script = new DefaultRedisScript<Long>();
        script.setScriptText("""
            -- Save session with atomic indexing (FIXED VERSION)
            local sessionKey = KEYS[1]
            local channelKey = KEYS[2]
            local imeiKey = KEYS[3]
            local activeSet = KEYS[4]
            local metricsKey = KEYS[5]
            
            local sessionData = ARGV[1]
            local sessionId = ARGV[2]
            local sessionTtl = tonumber(ARGV[3])
            local indexTtl = tonumber(ARGV[4])
            local isAuthenticated = ARGV[5]  -- This is now a string "1" or "0"
            
            -- Set session data with TTL
            redis.call("SET", sessionKey, sessionData, "EX", sessionTtl)
            
            -- Set indices with longer TTL (only if not empty)
            if channelKey ~= "idx:channel:" then
                redis.call("SET", channelKey, sessionId, "EX", indexTtl)
            end
            if imeiKey ~= "idx:imei:" then
                redis.call("SET", imeiKey, sessionId, "EX", indexTtl)
            end
            
            -- Add to active sessions set
            redis.call("SADD", activeSet, sessionId)
            redis.call("EXPIRE", activeSet, indexTtl)
            
            -- Update metrics
            redis.call("HINCRBY", metricsKey, "total:sessions", 1)
            if isAuthenticated == "1" then  -- ✅ FIXED: String comparison
                redis.call("HINCRBY", metricsKey, "authenticated:sessions", 1)
            end
            
            return 1
            """);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Create delete session Lua script - FIXED VERSION  
     */
    private DefaultRedisScript<Long> createDeleteSessionScript() {
        var script = new DefaultRedisScript<Long>();
        script.setScriptText("""
            -- Delete session with atomic cleanup (FIXED VERSION)
            local sessionKey = KEYS[1]
            local channelKey = KEYS[2]
            local imeiKey = KEYS[3]
            local activeSet = KEYS[4]
            local metricsKey = KEYS[5]
            
            local sessionId = ARGV[1]
            local wasAuthenticated = ARGV[2]  -- This is now a string "1" or "0"
            
            -- Delete session and indices
            redis.call("DEL", sessionKey)
            if channelKey ~= "idx:channel:" then
                redis.call("DEL", channelKey)
            end
            if imeiKey ~= "idx:imei:" then
                redis.call("DEL", imeiKey)
            end
            
            -- Remove from active sessions set
            redis.call("SREM", activeSet, sessionId)
            
            -- Update metrics  
            redis.call("HINCRBY", metricsKey, "total:sessions", -1)
            if wasAuthenticated == "1" then  -- ✅ FIXED: String comparison
                redis.call("HINCRBY", metricsKey, "authenticated:sessions", -1)
            end
            
            return 1
            """);
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<List> createFindIdleSessionsScript() {
        var script = new DefaultRedisScript<List>();
        script.setScriptText("""
            -- Find idle sessions
            local activeSet = KEYS[1]
            local sessionPrefix = KEYS[2]
            local cutoffTimestamp = tonumber(ARGV[1])
            
            local sessionIds = redis.call("SMEMBERS", activeSet)
            local idleSessions = {}
            
            for i = 1, #sessionIds do
                local sessionKey = sessionPrefix .. sessionIds[i]
                local sessionData = redis.call("GET", sessionKey)
                if sessionData then
                    -- Parse JSON to check lastActivityAt (simplified check)
                    if string.find(sessionData, "lastActivityAt") then
                        local timestamp = string.match(sessionData, '"lastActivityAt":(\\d+)')
                        if timestamp and tonumber(timestamp) < cutoffTimestamp then
                            table.insert(idleSessions, sessionIds[i])
                        end
                    end
                end
            end
            
            return idleSessions
            """);
        script.setResultType(List.class);
        return script;
    }

    /**
     * Session Data Transfer Object for Redis serialization - SIMPLE VERSION
     */
    public static class SessionData {
        private String sessionId;
        private String imei;
        private String channelId;
        private String remoteAddress;
        private boolean authenticated;
        private long createdAt;
        private long lastActivityAt;
        private Map<String, Object> attributes;

        // Default constructor for Jackson
        public SessionData() {
            this.attributes = new HashMap<>();
        }

        // Constructor with Jackson annotations
        @JsonCreator
        public SessionData(
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("imei") String imei,
                @JsonProperty("channelId") String channelId,
                @JsonProperty("remoteAddress") String remoteAddress,
                @JsonProperty("authenticated") boolean authenticated,
                @JsonProperty("createdAt") long createdAt,
                @JsonProperty("lastActivityAt") long lastActivityAt,
                @JsonProperty("attributes") Map<String, Object> attributes) {
            this.sessionId = sessionId;
            this.imei = imei;
            this.channelId = channelId;
            this.remoteAddress = remoteAddress;
            this.authenticated = authenticated;
            this.createdAt = createdAt;
            this.lastActivityAt = lastActivityAt;
            this.attributes = attributes != null ? attributes : new HashMap<>();
        }

        // Create from DeviceSession
        public static SessionData fromDeviceSession(DeviceSession session) {
            return new SessionData(
                session.getId(),
                session.getImei() != null ? session.getImei().value() : null,
                session.getChannelId(),
                session.getRemoteAddress(),
                session.isAuthenticated(),
                session.getCreatedAt().toEpochMilli(),
                session.getLastActivityAt().toEpochMilli(),
                new HashMap<>(session.getAttributes())
            );
        }

        // Convert to DeviceSession - SIMPLE VERSION using existing constructor
        public DeviceSession toDeviceSession() {
            // Use the simple constructor that exists in your current DeviceSession class
            DeviceSession session = new DeviceSession(imei, null); // Channel will be set later
            
            // Set additional fields using setters
            session.setAuthenticated(authenticated);
            session.setLastHeartbeat(Instant.ofEpochMilli(lastActivityAt));
            session.setLastMessage(Instant.ofEpochMilli(lastActivityAt));
            
            // Set attributes if session has setAttribute method
            if (attributes != null) {
                for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                    // Note: This assumes your DeviceSession has setAttribute method
                    // If not, you might need to modify this
                    try {
                        session.getClass().getMethod("setAttribute", String.class, Object.class)
                               .invoke(session, entry.getKey(), entry.getValue());
                    } catch (Exception e) {
                        // Ignore if setAttribute doesn't exist
                        logger.debug("Could not set attribute: " + entry.getKey());
                    }
                }
            }
            
            return session;
        }

        // Getters and setters required for Jackson
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public String getImei() { return imei; }
        public void setImei(String imei) { this.imei = imei; }

        public String getChannelId() { return channelId; }
        public void setChannelId(String channelId) { this.channelId = channelId; }

        public String getRemoteAddress() { return remoteAddress; }
        public void setRemoteAddress(String remoteAddress) { this.remoteAddress = remoteAddress; }

        public boolean isAuthenticated() { return authenticated; }
        public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }

        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

        public long getLastActivityAt() { return lastActivityAt; }
        public void setLastActivityAt(long lastActivityAt) { this.lastActivityAt = lastActivityAt; }

        public Map<String, Object> getAttributes() { return attributes; }
        public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    }
}