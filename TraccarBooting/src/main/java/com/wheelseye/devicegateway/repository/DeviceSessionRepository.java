package com.wheelseye.devicegateway.repository;

import com.wheelseye.devicegateway.model.DeviceSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CORRECTED Device Session Repository - Fixes Redis Deserialization Issues
 * 
 * This corrected version addresses the critical "@class" type ID errors by:
 * - Proper handling of Redis deserialization without type casting issues
 * - Safe object conversion with proper error handling
 * - Clean repository operations without complex type validation
 * 
 * @author WheelsEye Development Team  
 * @version 2.2.0 - CORRECTED
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DeviceSessionRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis key patterns
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String IMEI_INDEX_PREFIX = "imei-index:";
    private static final String CHANNEL_INDEX_PREFIX = "channel-index:";
    private static final String ACTIVE_SESSIONS_SET = "active-sessions";

    // Session TTL (30 minutes)
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    /**
     * Save session to Redis with atomic transaction
     */
    public DeviceSession save(DeviceSession session) {
        Objects.requireNonNull(session, "Session cannot be null");
        Objects.requireNonNull(session.getId(), "Session ID cannot be null");
        Objects.requireNonNull(session.getImei(), "Session IMEI cannot be null");

        String sessionKey = SESSION_KEY_PREFIX + session.getId();
        String imeiIndexKey = IMEI_INDEX_PREFIX + session.getImei();
        String channelIndexKey = CHANNEL_INDEX_PREFIX + session.getChannelId();

        try {
            // Update last activity
            session.touch();

            // Execute atomic transaction
            List<Object> results = redisTemplate.execute(new SessionCallback<List<Object>>() {
                @Override
                public List<Object> execute(RedisOperations operations) throws DataAccessException {
                    operations.multi();

                    // Store session with TTL
                    operations.opsForValue().set(sessionKey, session, SESSION_TTL);

                    // Create lookup indexes with TTL
                    operations.opsForValue().set(imeiIndexKey, session.getId(), SESSION_TTL);
                    if (session.getChannelId() != null) {
                        operations.opsForValue().set(channelIndexKey, session.getId(), SESSION_TTL);
                    }

                    // Add to active sessions set
                    operations.opsForSet().add(ACTIVE_SESSIONS_SET, session.getId());
                    operations.expire(ACTIVE_SESSIONS_SET, SESSION_TTL);

                    return operations.exec();
                }
            });

            log.debug("💾 Saved session to Redis: {} for IMEI: {}", session.getId(), session.getImei());
            return session;

        } catch (Exception e) {
            log.error("❌ Failed to save session {}: {}", session.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to save session to Redis", e);
        }
    }

    /**
     * CORRECTED: Find session by ID with safe deserialization
     */
    public Optional<DeviceSession> findById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;
            Object rawSession = redisTemplate.opsForValue().get(sessionKey);

            if (rawSession == null) {
                log.debug("🔍 No session found for ID: {}", sessionId);
                return Optional.empty();
            }

            // CORRECTED: Safe conversion using proper deserialization
            DeviceSession session = convertToDeviceSession(rawSession, sessionId);
            if (session != null) {
                log.debug("✅ Found session: {} for IMEI: {}", sessionId, session.getImei());
                return Optional.of(session);
            } else {
                log.warn("⚠️ Could not convert session data for ID: {}", sessionId);
                // Clean up corrupted session
                redisTemplate.delete(sessionKey);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.error("❌ Error finding session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * CORRECTED: Safe conversion from Redis object to DeviceSession
     */
    private DeviceSession convertToDeviceSession(Object rawSession, String sessionId) {
        try {
            // If it's already a DeviceSession (direct cast worked)
            if (rawSession instanceof DeviceSession) {
                return (DeviceSession) rawSession;
            }

            // If it's a Map (JSON deserialized as Map), convert it
            if (rawSession instanceof Map<?, ?> sessionMap) {
                return mapToDeviceSession(sessionMap);
            }

            // If it's a LinkedHashMap (common Redis deserialization result)
            if (rawSession instanceof LinkedHashMap<?, ?> linkedMap) {
                return mapToDeviceSession(linkedMap);
            }

            log.warn("⚠️ Unexpected session type for ID {}: {}", sessionId, rawSession.getClass().getSimpleName());
            return null;

        } catch (Exception e) {
            log.error("❌ Error converting session {}: {}", sessionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert Map data to DeviceSession object
     */
    @SuppressWarnings("unchecked")
    private DeviceSession mapToDeviceSession(Map<?, ?> sessionMap) {
        try {
            // Extract basic fields safely
            String id = getStringValue(sessionMap, "id");
            String imei = getStringValue(sessionMap, "imei");
            String channelId = getStringValue(sessionMap, "channelId");
            String remoteAddress = getStringValue(sessionMap, "remoteAddress");

            if (id == null || imei == null) {
                log.warn("⚠️ Missing required fields in session map: id={}, imei={}", id, imei);
                return null;
            }

            // Create DeviceSession with required fields
            DeviceSession session = DeviceSession.create(imei, channelId, remoteAddress);
            session.setId(id);

            // Set optional fields safely
            session.setAuthenticated(getBooleanValue(sessionMap, "authenticated"));

            // GPS coordinates
            Double latitude = getDoubleValue(sessionMap, "lastLatitude");
            Double longitude = getDoubleValue(sessionMap, "lastLongitude");
            if (latitude != null && longitude != null) {
                session.setLastLatitude(latitude);
                session.setLastLongitude(longitude);
            }

            // Timestamps
            Instant lastActivityAt = getInstantValue(sessionMap, "lastActivityAt");
            if (lastActivityAt != null) {
                session.setLastActivityAt(lastActivityAt);
            }

            Instant createdAt = getInstantValue(sessionMap, "createdAt");
            if (createdAt != null) {
                session.setCreatedAt(createdAt);
            }

            Instant lastPositionTime = getInstantValue(sessionMap, "lastPositionTime");
            if (lastPositionTime != null) {
                session.setLastPositionTime(lastPositionTime);
            }

            // Status
            String statusStr = getStringValue(sessionMap, "status");
            if (statusStr != null) {
                try {
                    DeviceSession.SessionStatus status = DeviceSession.SessionStatus.valueOf(statusStr);
                    session.setStatus(status);
                } catch (IllegalArgumentException e) {
                    log.debug("Invalid status value: {}, using default", statusStr);
                }
            }

            // Protocol info
            session.setProtocolVersion(getStringValue(sessionMap, "protocolVersion"));
            session.setDeviceModel(getStringValue(sessionMap, "deviceModel"));
            session.setFirmwareVersion(getStringValue(sessionMap, "firmwareVersion"));

            // Device status
            session.setSignalStrength(getIntegerValue(sessionMap, "signalStrength"));
            session.setIsCharging(getBooleanValue(sessionMap, "isCharging"));
            session.setBatteryLevel(getIntegerValue(sessionMap, "batteryLevel"));

            return session;

        } catch (Exception e) {
            log.error("❌ Error mapping session data: {}", e.getMessage(), e);
            return null;
        }
    }

    // Helper methods for safe type conversion
    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Boolean getBooleanValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return false; // default
    }

    private Double getDoubleValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Integer getIntegerValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0; // default
    }

    private Instant getInstantValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return Instant.ofEpochMilli(((Number) value).longValue());
        }
        if (value instanceof String) {
            try {
                return Instant.parse((String) value);
            } catch (Exception e) {
                log.debug("Could not parse instant from string: {}", value);
                return null;
            }
        }
        return null;
    }

    /**
     * Find session by IMEI using index
     */
    public Optional<DeviceSession> findByImei(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String imeiIndexKey = IMEI_INDEX_PREFIX + imei;
            Object sessionIdObj = redisTemplate.opsForValue().get(imeiIndexKey);

            if (sessionIdObj instanceof String sessionId) {
                return findById(sessionId);
            } else if (sessionIdObj != null) {
                log.warn("⚠️ Invalid session ID type for IMEI {}: {} - cleaning up", 
                        imei, sessionIdObj.getClass().getSimpleName());
                redisTemplate.delete(imeiIndexKey);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ Error finding session by IMEI {}: {}", imei, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by channel ID using index
     */
    public Optional<DeviceSession> findByChannelId(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String channelIndexKey = CHANNEL_INDEX_PREFIX + channelId;
            Object sessionIdObj = redisTemplate.opsForValue().get(channelIndexKey);

            if (sessionIdObj instanceof String sessionId) {
                return findById(sessionId);
            } else if (sessionIdObj != null) {
                log.warn("⚠️ Invalid session ID type for channel {}: {} - cleaning up", 
                        channelId, sessionIdObj.getClass().getSimpleName());
                redisTemplate.delete(channelIndexKey);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ Error finding session by channel ID {}: {}", channelId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get all active sessions
     */
    public List<DeviceSession> findAll() {
        try {
            Set<Object> sessionIds = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_SET);

            if (sessionIds == null || sessionIds.isEmpty()) {
                return Collections.emptyList();
            }

            return sessionIds.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(this::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Error finding all sessions: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Find sessions by last activity cutoff for cleanup
     */
    public List<DeviceSession> findByLastActivityAtBefore(Instant cutoffTime) {
        Objects.requireNonNull(cutoffTime, "Cutoff time cannot be null");

        return findAll().stream()
                .filter(session -> session.getLastActivityAt() != null && 
                                 session.getLastActivityAt().isBefore(cutoffTime))
                .collect(Collectors.toList());
    }

    /**
     * Delete session with comprehensive cleanup
     */
    public void deleteById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }

        try {
            // Get session first to clean up indexes
            Optional<DeviceSession> sessionOpt = findById(sessionId);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                deleteSessionWithIndexes(sessionId, session.getImei(), session.getChannelId());
            } else {
                // Clean up just the session key and active set
                deleteSessionBasic(sessionId);
            }

            log.debug("🗑️ Deleted session: {}", sessionId);

        } catch (Exception e) {
            log.error("❌ Error deleting session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Delete session with all indexes atomically
     */
    private void deleteSessionWithIndexes(String sessionId, String imei, String channelId) {
        try {
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    operations.multi();

                    // Remove main session
                    operations.delete(SESSION_KEY_PREFIX + sessionId);

                    // Remove indexes
                    if (imei != null) {
                        operations.delete(IMEI_INDEX_PREFIX + imei);
                    }
                    if (channelId != null) {
                        operations.delete(CHANNEL_INDEX_PREFIX + channelId);
                    }

                    // Remove from active sessions set
                    operations.opsForSet().remove(ACTIVE_SESSIONS_SET, sessionId);

                    return operations.exec();
                }
            });
        } catch (Exception e) {
            log.warn("⚠️ Failed atomic deletion for {}, trying basic cleanup: {}", sessionId, e.getMessage());
            deleteSessionBasic(sessionId);
        }
    }

    /**
     * Basic session deletion fallback
     */
    private void deleteSessionBasic(String sessionId) {
        try {
            redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
            redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_SET, sessionId);
        } catch (Exception e) {
            log.error("❌ Even basic deletion failed for {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Count active sessions
     */
    public long count() {
        try {
            Long count = redisTemplate.opsForSet().size(ACTIVE_SESSIONS_SET);
            return count != null ? count : 0L;
        } catch (Exception e) {
            log.error("❌ Error counting sessions: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Health check for Redis connectivity
     */
    public boolean isHealthy() {
        try {
            String testKey = "health-check-" + System.currentTimeMillis();
            String testValue = "OK";

            redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
            Object retrieved = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);

            return testValue.equals(retrieved);
        } catch (Exception e) {
            log.error("❌ Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Clear all sessions (for testing/maintenance)
     */
    public void deleteAll() {
        try {
            log.warn("🧹 Clearing ALL sessions from Redis");

            Set<String> sessionKeys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
            Set<String> imeiKeys = redisTemplate.keys(IMEI_INDEX_PREFIX + "*");
            Set<String> channelKeys = redisTemplate.keys(CHANNEL_INDEX_PREFIX + "*");

            if (sessionKeys != null && !sessionKeys.isEmpty()) {
                redisTemplate.delete(sessionKeys);
                log.info("🗑️ Deleted {} session keys", sessionKeys.size());
            }

            if (imeiKeys != null && !imeiKeys.isEmpty()) {
                redisTemplate.delete(imeiKeys);
                log.info("🗑️ Deleted {} IMEI index keys", imeiKeys.size());
            }

            if (channelKeys != null && !channelKeys.isEmpty()) {
                redisTemplate.delete(channelKeys);
                log.info("🗑️ Deleted {} channel index keys", channelKeys.size());
            }

            redisTemplate.delete(ACTIVE_SESSIONS_SET);
            log.info("✅ All sessions cleared successfully");

        } catch (Exception e) {
            log.error("❌ Error clearing all sessions: {}", e.getMessage(), e);
        }
    }
}