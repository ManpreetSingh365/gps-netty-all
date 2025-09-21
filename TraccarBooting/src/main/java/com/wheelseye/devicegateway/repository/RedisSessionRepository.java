package com.wheelseye.devicegateway.repository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Modern Redis Session Repository using Spring Boot 3.5.5 and Java 21 best
 * practices.
 * 
 * Features:
 * - No manual Lua scripts - uses Redis operations directly
 * - Proper Jackson serialization with type information
 * - Comprehensive error handling and logging
 * - Modern Java patterns (records, Optional, Stream API)
 * - Production-ready with proper cleanup and monitoring
 */
@Repository
public class RedisSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRepository.class);

    // Redis key patterns
    private static final String SESSION_PREFIX = "device:session:";
    private static final String CHANNEL_PREFIX = "channel:session:";
    private static final String IMEI_PREFIX = "imei:session:";
    private static final String ACTIVE_SESSIONS = "active:sessions";
    private static final String METRICS_KEY = "session:metrics";

    // Session TTL configuration
    private static final Duration SESSION_TTL = Duration.ofHours(1);
    private static final Duration INDEX_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, Object> redis;
    private final ObjectMapper mapper;

    public RedisSessionRepository(
            RedisTemplate<String, Object> redisTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.redis = redisTemplate;
        this.mapper = objectMapper;
    }

    /**
     * Save session using atomic Redis operations (no Lua scripts).
     */
    public void save(DeviceSession session) {
        Objects.requireNonNull(session, "Session cannot be null");

        try {
            var sessionData = SessionData.fromDeviceSession(session);
            var sessionKey = SESSION_PREFIX + session.getId();

            // Save main session data with TTL
            redis.opsForValue().set(sessionKey, sessionData, SESSION_TTL);

            // Create indices with TTL
            createIndices(session);

            // Add to active sessions set
            redis.opsForSet().add(ACTIVE_SESSIONS, session.getId());
            redis.expire(ACTIVE_SESSIONS, INDEX_TTL);

            // Update metrics
            updateMetrics(session, true);

            log.debug("✅ Session saved: {}", session.getId());

        } catch (Exception e) {
            log.error("❌ Failed to save session: {}", session.getId(), e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    /**
     * Find session by ID with robust error handling.
     */
    public Optional<DeviceSession> findById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        try {
            var sessionKey = SESSION_PREFIX + sessionId;
            var rawData = redis.opsForValue().get(sessionKey);

            if (rawData == null) {
                log.debug("📭 Session not found: {}", sessionId);
                return Optional.empty();
            }

            var sessionData = convertToSessionData(rawData, sessionId);
            if (sessionData == null) {
                return Optional.empty();
            }

            var session = sessionData.toDeviceSession();
            log.debug("✅ Session found: {}", sessionId);
            return Optional.of(session);

        } catch (Exception e) {
            log.error("❌ Error finding session: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * Find session by channel.
     */
    public Optional<DeviceSession> findByChannel(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }

        try {
            var channelId = channel.id().asShortText();
            var channelKey = CHANNEL_PREFIX + channelId;
            var sessionIdObj = redis.opsForValue().get(channelKey);

            if (sessionIdObj == null) {
                log.debug("📭 No session found for channel: {}", channelId);
                return Optional.empty();
            }

            return findById(sessionIdObj.toString());

        } catch (Exception e) {
            log.error("❌ Error finding session by channel: {}", channel.id().asShortText(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by IMEI.
     */
    public Optional<DeviceSession> findByImei(IMEI imei) {
        if (imei == null) {
            return Optional.empty();
        }

        try {
            var imeiKey = IMEI_PREFIX + imei.value();
            var sessionIdObj = redis.opsForValue().get(imeiKey);

            if (sessionIdObj == null) {
                log.debug("📭 No session found for IMEI: {}", imei.value());
                return Optional.empty();
            }

            return findById(sessionIdObj.toString());

        } catch (Exception e) {
            log.error("❌ Error finding session by IMEI: {}", imei.value(), e);
            return Optional.empty();
        }
    }

    /**
     * Delete session with complete cleanup.
     */
    public void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            // Get session first to clean up indices
            var sessionOpt = findById(sessionId);
            if (sessionOpt.isEmpty()) {
                log.debug("📭 Session not found for deletion: {}", sessionId);
                return;
            }

            var session = sessionOpt.get();

            // Delete main session
            redis.delete(SESSION_PREFIX + sessionId);

            // Delete indices
            deleteIndices(session);

            // Remove from active sessions
            redis.opsForSet().remove(ACTIVE_SESSIONS, sessionId);

            // Update metrics
            updateMetrics(session, false);

            log.debug("✅ Session deleted: {}", sessionId);

        } catch (Exception e) {
            log.error("❌ Error deleting session: {}", sessionId, e);
        }
    }

    /**
     * Find all active sessions.
     */
    public List<DeviceSession> findAllActive() {
        try {
            var sessionIds = redis.opsForSet().members(ACTIVE_SESSIONS);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }

            return sessionIds.stream()
                    .map(Object::toString)
                    .map(this::findById)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Error finding all active sessions", e);
            return List.of();
        }
    }

    /**
     * Find idle sessions for cleanup.
     */
    public List<DeviceSession> findIdle(Duration idleTimeout) {
        try {
            var cutoffTime = Instant.now().minus(idleTimeout);
            var allSessions = findAllActive();

            return allSessions.stream()
                    .filter(session -> session.getLastActivityAt().isBefore(cutoffTime))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Error finding idle sessions", e);
            return List.of();
        }
    }

    /**
     * Get count of active sessions.
     */
    public long countActive() {
        try {
            var count = redis.opsForSet().size(ACTIVE_SESSIONS);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.error("❌ Error counting active sessions", e);
            return 0L;
        }
    }

    /**
     * Check if session exists.
     */
    public boolean exists(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + sessionId));
        } catch (Exception e) {
            log.error("❌ Error checking session existence: {}", sessionId, e);
            return false;
        }
    }

    // Private helper methods

    private void createIndices(DeviceSession session) {
        // Index by channel ID
        if (session.getChannelId() != null) {
            var channelKey = CHANNEL_PREFIX + session.getChannelId();
            redis.opsForValue().set(channelKey, session.getId(), INDEX_TTL);
        }

        // Index by IMEI
        if (session.getImei() != null) {
            var imeiKey = IMEI_PREFIX + session.getImei().value();
            redis.opsForValue().set(imeiKey, session.getId(), INDEX_TTL);
        }
    }

    private void deleteIndices(DeviceSession session) {
        // Delete channel index
        if (session.getChannelId() != null) {
            redis.delete(CHANNEL_PREFIX + session.getChannelId());
        }

        // Delete IMEI index
        if (session.getImei() != null) {
            redis.delete(IMEI_PREFIX + session.getImei().value());
        }
    }

    private void updateMetrics(DeviceSession session, boolean increment) {
        try {
            var delta = increment ? 1 : -1;
            redis.opsForHash().increment(METRICS_KEY, "total", delta);

            if (session.isAuthenticated()) {
                redis.opsForHash().increment(METRICS_KEY, "authenticated", delta);
            }

            // Set TTL on metrics hash
            redis.expire(METRICS_KEY, Duration.ofDays(1));

        } catch (Exception e) {
            log.warn("⚠️ Failed to update metrics", e);
        }
    }

    private SessionData convertToSessionData(Object rawData, String sessionId) {
        try {
            if (rawData instanceof SessionData sessionData) {
                return sessionData;
            }

            if (rawData instanceof LinkedHashMap<?, ?> dataMap) {
                return convertMapToSessionData(dataMap);
            }

            // Try ObjectMapper conversion
            return mapper.convertValue(rawData, SessionData.class);

        } catch (Exception e) {
            log.error("❌ Failed to convert raw data to SessionData: {}", sessionId, e);
            // Clean up corrupted session
            redis.delete(SESSION_PREFIX + sessionId);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private SessionData convertMapToSessionData(LinkedHashMap<?, ?> dataMap) {
        var map = (Map<String, Object>) dataMap;

        return new SessionData(
                getStringValue(map, "sessionId"),
                getStringValue(map, "imei"),
                getStringValue(map, "channelId"),
                getInstantValue(map, "createdAt"),
                getInstantValue(map, "lastActivityAt"),
                getBooleanValue(map, "authenticated"),
                getStringValue(map, "remoteAddress"),
                getMapValue(map, "attributes"));
    }

    private String getStringValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getBooleanValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value != null ? value.toString() : "false");
    }

    private Instant getInstantValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value == null) {
            return Instant.now();
        }

        try {
            return switch (value) {
                case String str -> Instant.parse(str);
                case Number num -> Instant.ofEpochMilli(num.longValue());
                default -> mapper.convertValue(value, Instant.class);
            };
        } catch (Exception e) {
            log.warn("⚠️ Failed to parse Instant value: {}, using current time", value);
            return Instant.now();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapValue(Map<String, Object> map, String key) {
        var value = map.get(key);
        if (value instanceof Map<?, ?> valueMap) {
            return (Map<String, Object>) valueMap;
        }
        return new HashMap<>();
    }

    /**
     * Session Data Transfer Object for Redis serialization.
     */
    public static class SessionData {
        private String sessionId;
        private String imei;
        private String channelId;
        private Instant createdAt;
        private Instant lastActivityAt;
        private boolean authenticated;
        private String remoteAddress;
        private Map<String, Object> attributes;

        // Default constructor for Jackson
        public SessionData() {
            this.attributes = new HashMap<>();
        }

        @JsonCreator
        public SessionData(
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("imei") String imei,
                @JsonProperty("channelId") String channelId,
                @JsonProperty("createdAt") Instant createdAt,
                @JsonProperty("lastActivityAt") Instant lastActivityAt,
                @JsonProperty("authenticated") boolean authenticated,
                @JsonProperty("remoteAddress") String remoteAddress,
                @JsonProperty("attributes") Map<String, Object> attributes) {
            this.sessionId = sessionId;
            this.imei = imei;
            this.channelId = channelId;
            this.createdAt = createdAt;
            this.lastActivityAt = lastActivityAt;
            this.authenticated = authenticated;
            this.remoteAddress = remoteAddress;
            this.attributes = attributes != null ? attributes : new HashMap<>();
        }

        public static SessionData fromDeviceSession(DeviceSession session) {
            return new SessionData(
                    session.getId(),
                    session.getImei() != null ? session.getImei().value() : null,
                    session.getChannelId(),
                    session.getCreatedAt(),
                    session.getLastActivityAt(),
                    session.isAuthenticated(),
                    session.getRemoteAddress(),
                    new HashMap<>(session.getAttributes()));
        }

        public DeviceSession toDeviceSession() {
            var imeiObj = imei != null ? IMEI.of(imei) : null;

            return new DeviceSession(
                    sessionId, // String id
                    imeiObj, // IMEI imei
                    channelId, // String channelId
                    remoteAddress, // String remoteAddress
                    null, // Channel channel
                    null, // String protocolVersion
                    null, // String deviceVariant
                    DeviceSession.DeviceStatus.CONNECTED, // DeviceStatus status (FIXED)
                    authenticated, // boolean authenticated
                    createdAt, // Instant createdAt
                    lastActivityAt, // Instant lastActivityAt
                    lastActivityAt, // Instant lastLoginAt
                    attributes != null ? attributes : Map.of() // Map<String, Object> attributes
            );
        }

        // public DeviceSession toDeviceSession() {
        //     var imeiObj = imei != null ? IMEI.of(imei) : null;

        //     // Use the static factory method instead
        //     var session = DeviceSession.create(imeiObj, channelId, remoteAddress);

        //     // Set additional properties
        //     session.setAuthenticated(authenticated);
        //     if (attributes != null) {
        //         attributes.forEach(session::setAttribute);
        //     }

        //     return session;
        // }

        // Getters and setters
        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getImei() {
            return imei;
        }

        public void setImei(String imei) {
            this.imei = imei;
        }

        public String getChannelId() {
            return channelId;
        }

        public void setChannelId(String channelId) {
            this.channelId = channelId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getLastActivityAt() {
            return lastActivityAt;
        }

        public void setLastActivityAt(Instant lastActivityAt) {
            this.lastActivityAt = lastActivityAt;
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        public void setAuthenticated(boolean authenticated) {
            this.authenticated = authenticated;
        }

        public String getRemoteAddress() {
            return remoteAddress;
        }

        public void setRemoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, Object> attributes) {
            this.attributes = attributes;
        }
    }
}