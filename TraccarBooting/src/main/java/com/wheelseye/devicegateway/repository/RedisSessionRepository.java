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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * EMERGENCY RedisSessionRepository with CRASH PREVENTION
 * 
 * Implements aggressive error handling and session cleanup to prevent
 * infinite loops and application crashes from corrupted Redis data.
 * 
 * @author WheelsEye Development Team - EMERGENCY FIX
 * @since 1.0.0
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisSessionRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis key patterns
    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String IMEI_INDEX_PREFIX = "imei-index:";
    private static final String CHANNEL_INDEX_PREFIX = "channel-index:";
    private static final String ACTIVE_SESSIONS_SET = "active-sessions";

    // Default TTL for sessions (30 minutes)
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    // EMERGENCY: Track corrupted sessions to prevent infinite loops
    private static final Set<String> CORRUPTED_SESSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CLEANUP_IN_PROGRESS = ConcurrentHashMap.newKeySet();

    // Statistics for monitoring
    private volatile long successfulDeserializations = 0;
    private volatile long failedDeserializations = 0;
    private volatile long corruptedSessionsCleaned = 0;
    private volatile long emergencyCleanups = 0;

    /**
     * Save session to Redis with enhanced error handling
     */
    public DeviceSession save(DeviceSession session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }

        try {
            String sessionKey = SESSION_KEY_PREFIX + session.getId();
            String imeiIndexKey = IMEI_INDEX_PREFIX + session.getImei();
            String channelIndexKey = CHANNEL_INDEX_PREFIX + session.getChannelId();

            // Remove from corrupted list if it was there
            CORRUPTED_SESSIONS.remove(session.getId());

            // Use Redis transaction for atomic operations
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    operations.multi();

                    // Store session data with TTL
                    operations.opsForValue().set(sessionKey, session, DEFAULT_TTL);

                    // Create indexes for fast lookup with TTL
                    operations.opsForValue().set(imeiIndexKey, session.getId(), DEFAULT_TTL);
                    operations.opsForValue().set(channelIndexKey, session.getId(), DEFAULT_TTL);

                    // Add to active sessions set
                    operations.opsForSet().add(ACTIVE_SESSIONS_SET, session.getId());

                    return operations.exec();
                }
            });

            log.debug("💾 Saved session to Redis: {}", session.getId());
            return session;

        } catch (Exception e) {
            log.error("❌ Error saving session to Redis: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save session to Redis", e);
        }
    }

    /**
     * EMERGENCY: Find session by ID with aggressive corruption handling
     */
    public Optional<DeviceSession> findById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Optional.empty();
        }

        // EMERGENCY: Skip if already identified as corrupted
        if (CORRUPTED_SESSIONS.contains(sessionId)) {
            log.debug("🛑 EMERGENCY: Skipping known corrupted session {}", sessionId);
            return Optional.empty();
        }

        // EMERGENCY: Skip if cleanup is in progress
        if (CLEANUP_IN_PROGRESS.contains(sessionId)) {
            log.debug("🧹 EMERGENCY: Cleanup in progress for session {}", sessionId);
            return Optional.empty();
        }

        try {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;

            // Set current session context for circuit breaker
            setCurrentSessionContext(sessionId);

            Object rawSession = redisTemplate.opsForValue().get(sessionKey);

            if (rawSession == null) {
                log.debug("🔍 No session found for ID: {}", sessionId);
                return Optional.empty();
            }

            // Handle successful deserialization
            if (rawSession instanceof DeviceSession) {
                DeviceSession session = (DeviceSession) rawSession;
                successfulDeserializations++;
                log.debug("✅ Found session: {}", sessionId);
                return Optional.of(session);
            } else {
                // Handle unexpected types - EMERGENCY CLEANUP
                log.error("🚨 EMERGENCY: Unexpected session type for ID {}: {} - FORCE CLEANUP", 
                         sessionId, rawSession.getClass().getSimpleName());
                emergencyCleanupSession(sessionId);
                return Optional.empty();
            }

        } catch (Exception e) {
            log.error("🚨 EMERGENCY: Critical error finding session {}: {}", sessionId, e.getMessage());

            // Increment failed counter
            failedDeserializations++;

            // EMERGENCY: Mark as corrupted and cleanup immediately
            markAsCorruptedAndCleanup(sessionId, e);
            return Optional.empty();

        } finally {
            // Clear session context
            clearCurrentSessionContext();
        }
    }

    /**
     * EMERGENCY: Mark session as corrupted and perform immediate cleanup
     */
    private void markAsCorruptedAndCleanup(String sessionId, Exception error) {
        // Add to corrupted sessions set
        CORRUPTED_SESSIONS.add(sessionId);

        log.error("🛑 EMERGENCY: Marked session {} as CORRUPTED due to: {}", 
                 sessionId, error.getMessage());

        // Trigger immediate emergency cleanup
        emergencyCleanupSession(sessionId);

        // Prevent memory leak - limit corrupted sessions set size
        if (CORRUPTED_SESSIONS.size() > 1000) {
            Iterator<String> iterator = CORRUPTED_SESSIONS.iterator();
            for (int i = 0; i < 100 && iterator.hasNext(); i++) {
                iterator.next();
                iterator.remove();
            }
            log.warn("🧹 EMERGENCY: Cleaned up oldest corrupted session markers");
        }
    }

    /**
     * EMERGENCY: Force cleanup of corrupted session
     */
    private void emergencyCleanupSession(String sessionId) {
        // Prevent concurrent cleanup
        if (!CLEANUP_IN_PROGRESS.add(sessionId)) {
            log.debug("🛑 EMERGENCY: Cleanup already in progress for {}", sessionId);
            return;
        }

        try {
            log.warn("🚨 EMERGENCY CLEANUP: Force removing corrupted session {}", sessionId);

            // Force delete all related keys
            String sessionKey = SESSION_KEY_PREFIX + sessionId;

            // Delete main session key
            Boolean deleted = redisTemplate.delete(sessionKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.info("🗑️ EMERGENCY: Deleted main session key for {}", sessionId);
            }

            // Remove from active sessions set
            Long removed = redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_SET, sessionId);
            if (removed != null && removed > 0) {
                log.info("🗑️ EMERGENCY: Removed {} from active sessions set", sessionId);
            }

            // Try to cleanup indexes (best effort)
            try {
                Set<String> imeiKeys = redisTemplate.keys(IMEI_INDEX_PREFIX + "*");
                Set<String> channelKeys = redisTemplate.keys(CHANNEL_INDEX_PREFIX + "*");

                if (imeiKeys != null) {
                    for (String imeiKey : imeiKeys) {
                        Object value = redisTemplate.opsForValue().get(imeiKey);
                        if (sessionId.equals(value)) {
                            redisTemplate.delete(imeiKey);
                            log.debug("🗑️ EMERGENCY: Cleaned up IMEI index for {}", sessionId);
                        }
                    }
                }

                if (channelKeys != null) {
                    for (String channelKey : channelKeys) {
                        Object value = redisTemplate.opsForValue().get(channelKey);
                        if (sessionId.equals(value)) {
                            redisTemplate.delete(channelKey);
                            log.debug("🗑️ EMERGENCY: Cleaned up channel index for {}", sessionId);
                        }
                    }
                }
            } catch (Exception indexError) {
                log.warn("⚠️ EMERGENCY: Index cleanup failed for {}: {}", sessionId, indexError.getMessage());
            }

            emergencyCleanups++;
            corruptedSessionsCleaned++;

            log.info("✅ EMERGENCY CLEANUP COMPLETED for session {}", sessionId);

        } catch (Exception e) {
            log.error("❌ EMERGENCY CLEANUP FAILED for session {}: {}", sessionId, e.getMessage(), e);
        } finally {
            CLEANUP_IN_PROGRESS.remove(sessionId);
        }
    }

    /**
     * Find session by IMEI with corruption protection
     */
    public Optional<DeviceSession> findByImei(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String imeiIndexKey = IMEI_INDEX_PREFIX + imei;
            Object sessionIdObj = redisTemplate.opsForValue().get(imeiIndexKey);

            if (sessionIdObj instanceof String) {
                String sessionId = (String) sessionIdObj;
                return findById(sessionId);
            } else if (sessionIdObj != null) {
                log.warn("⚠️ Invalid session ID type for IMEI {}: {}", imei, sessionIdObj.getClass().getSimpleName());
                redisTemplate.delete(imeiIndexKey);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ Error finding session by IMEI {}: {}", imei, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by channel ID with corruption protection
     */
    public Optional<DeviceSession> findByChannelId(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String channelIndexKey = CHANNEL_INDEX_PREFIX + channelId;
            Object sessionIdObj = redisTemplate.opsForValue().get(channelIndexKey);

            if (sessionIdObj instanceof String) {
                String sessionId = (String) sessionIdObj;
                return findById(sessionId);
            } else if (sessionIdObj != null) {
                log.warn("⚠️ Invalid session ID type for channel {}: {}", channelId, sessionIdObj.getClass().getSimpleName());
                redisTemplate.delete(channelIndexKey);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ Error finding session by channel ID {}: {}", channelId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get all active sessions with corruption filtering
     */
    public List<DeviceSession> findAll() {
        try {
            Set<Object> sessionIds = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_SET);

            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }

            return sessionIds.stream()
                    .filter(id -> id instanceof String)
                    .map(Object::toString)
                    .filter(sessionId -> !CORRUPTED_SESSIONS.contains(sessionId)) // Filter corrupted
                    .map(this::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("❌ Error finding all sessions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Find sessions by last activity cutoff time (for cleanup)
     */
    public List<DeviceSession> findByLastActivityAtBefore(Instant cutoffTime) {
        if (cutoffTime == null) {
            return List.of();
        }

        return findAll().stream()
                .filter(session -> session.getLastActivityAt() != null && 
                                 session.getLastActivityAt().isBefore(cutoffTime))
                .collect(Collectors.toList());
    }

    /**
     * Delete session by ID with comprehensive cleanup
     */
    public void deleteById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }

        // Mark for cleanup to prevent concurrent access
        CLEANUP_IN_PROGRESS.add(sessionId);

        try {
            Optional<DeviceSession> sessionOpt = findById(sessionId);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                deleteSessionCompletely(sessionId, session.getImei(), session.getChannelId());
            } else {
                // Force cleanup even if session not found
                emergencyCleanupSession(sessionId);
            }

            // Remove from corrupted list
            CORRUPTED_SESSIONS.remove(sessionId);

            log.debug("🗑️ Deleted session from Redis: {}", sessionId);

        } catch (Exception e) {
            log.error("❌ Error deleting session {}: {}", sessionId, e.getMessage(), e);
            // Force emergency cleanup on error
            emergencyCleanupSession(sessionId);
        } finally {
            CLEANUP_IN_PROGRESS.remove(sessionId);
        }
    }

    /**
     * Complete session deletion with all indexes
     */
    private void deleteSessionCompletely(String sessionId, String imei, String channelId) {
        try {
            redisTemplate.execute(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) throws DataAccessException {
                    operations.multi();

                    // Remove main session data
                    operations.delete(SESSION_KEY_PREFIX + sessionId);

                    // Remove indexes if we have the info
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
            log.warn("⚠️ Error during complete session deletion: {}", e.getMessage());
            // Fallback to emergency cleanup
            emergencyCleanupSession(sessionId);
        }
    }

    /**
     * Count active sessions (excluding corrupted)
     */
    public long count() {
        try {
            Long count = redisTemplate.opsForSet().size(ACTIVE_SESSIONS_SET);
            long activeCount = count != null ? count : 0L;

            // Subtract corrupted sessions
            return Math.max(0, activeCount - CORRUPTED_SESSIONS.size());
        } catch (Exception e) {
            log.error("❌ Error counting sessions: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Get enhanced repository statistics including emergency metrics
     */
    // public Map<String, Object> getSessionStatistics() {
    //     try {
    //         List<DeviceSession> allSessions = findAll();

    //         long total = allSessions.size();
    //         long authenticated = allSessions.stream()
    //                 .filter(DeviceSession::isAuthenticated)
    //                 .count();
    //         long withLocation = allSessions.stream()
    //                 .filter(DeviceSession::hasValidLocation)
    //                 .count();
    //         long active = allSessions.stream()
    //                 .filter(DeviceSession::isChannelActive)
    //                 .count();

    //         return Map.of(
    //                 "total", total,
    //                 "authenticated", authenticated,
    //                 "withLocation", withLocation,
    //                 "active", active,
    //                 "successfulDeserializations", successfulDeserializations,
    //                 "failedDeserializations", failedDeserializations,
    //                 "corruptedSessions", (long) CORRUPTED_SESSIONS.size(),
    //                 "corruptedSessionsCleaned", corruptedSessionsCleaned,
    //                 "emergencyCleanups", emergencyCleanups,
    //                 "cleanupInProgress", (long) CLEANUP_IN_PROGRESS.size(),
    //                 "timestamp", Instant.now()
    //         );

    //     } catch (Exception e) {
    //         log.error("❌ Error getting session statistics: {}", e.getMessage(), e);
    //         return Map.of(
    //             "error", e.getMessage(),
    //             "corruptedSessions", (long) CORRUPTED_SESSIONS.size(),
    //             "emergencyCleanups", emergencyCleanups,
    //             "timestamp", Instant.now()
    //         );
    //     }
    // }

    /**
     * EMERGENCY: Clear all corrupted session markers
     */
    public void clearCorruptedSessions() {
        int cleared = CORRUPTED_SESSIONS.size();
        CORRUPTED_SESSIONS.clear();
        CLEANUP_IN_PROGRESS.clear();

        log.info("🧹 EMERGENCY: Cleared {} corrupted session markers", cleared);
    }

    /**
     * EMERGENCY: Get list of corrupted session IDs
     */
    public Set<String> getCorruptedSessionIds() {
        return new HashSet<>(CORRUPTED_SESSIONS);
    }

    // Context management for circuit breaker
    private static final ThreadLocal<String> CURRENT_SESSION_CONTEXT = new ThreadLocal<>();

    private void setCurrentSessionContext(String sessionId) {
        CURRENT_SESSION_CONTEXT.set(sessionId);
    }

    private void clearCurrentSessionContext() {
        CURRENT_SESSION_CONTEXT.remove();
    }

    public static String getCurrentSessionContext() {
        return CURRENT_SESSION_CONTEXT.get();
    }

    /**
     * Health check with emergency metrics
     */
    public Map<String, Object> healthCheck() {
        try {
            String testKey = "health-check-" + System.currentTimeMillis();
            String testValue = "OK";

            redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
            Object retrieved = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);

            boolean healthy = testValue.equals(retrieved);

            return Map.of(
                "redis_connectivity", healthy,
                "successful_deserializations", successfulDeserializations,
                "failed_deserializations", failedDeserializations,
                "corrupted_sessions", CORRUPTED_SESSIONS.size(),
                "emergency_cleanups", emergencyCleanups,
                "cleanup_in_progress", CLEANUP_IN_PROGRESS.size(),
                "timestamp", Instant.now()
            );
        } catch (Exception e) {
            log.error("❌ Redis health check failed: {}", e.getMessage());
            return Map.of(
                "redis_connectivity", false,
                "error", e.getMessage(),
                "corrupted_sessions", CORRUPTED_SESSIONS.size(),
                "timestamp", Instant.now()
            );
        }
    }

    /**
     * EMERGENCY: Force cleanup of all sessions
     */
    public void deleteAll() {
        try {
            log.warn("🚨 EMERGENCY: Force clearing ALL Redis sessions");

            // Clear all session patterns
            Set<String> sessionKeys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
            Set<String> imeiKeys = redisTemplate.keys(IMEI_INDEX_PREFIX + "*");
            Set<String> channelKeys = redisTemplate.keys(CHANNEL_INDEX_PREFIX + "*");

            if (sessionKeys != null && !sessionKeys.isEmpty()) {
                redisTemplate.delete(sessionKeys);
                log.info("🗑️ EMERGENCY: Deleted {} session keys", sessionKeys.size());
            }

            if (imeiKeys != null && !imeiKeys.isEmpty()) {
                redisTemplate.delete(imeiKeys);
                log.info("🗑️ EMERGENCY: Deleted {} IMEI index keys", imeiKeys.size());
            }

            if (channelKeys != null && !channelKeys.isEmpty()) {
                redisTemplate.delete(channelKeys);
                log.info("🗑️ EMERGENCY: Deleted {} channel index keys", channelKeys.size());
            }

            redisTemplate.delete(ACTIVE_SESSIONS_SET);

            // Clear tracking sets
            CORRUPTED_SESSIONS.clear();
            CLEANUP_IN_PROGRESS.clear();

            // Reset statistics
            successfulDeserializations = 0;
            failedDeserializations = 0;
            corruptedSessionsCleaned = 0;
            emergencyCleanups = 0;

            log.info("✅ EMERGENCY: All sessions cleared and statistics reset");

        } catch (Exception e) {
            log.error("❌ EMERGENCY: Error clearing all sessions: {}", e.getMessage(), e);
        }
    }
}