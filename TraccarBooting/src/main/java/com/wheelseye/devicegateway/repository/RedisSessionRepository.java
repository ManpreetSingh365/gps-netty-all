package com.wheelseye.devicegateway.repository;

import com.wheelseye.devicegateway.model.DeviceSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis-based DeviceSessionRepository
 * 
 * Uses Redis for session storage with TTL and indexing.
 * No database persistence required.
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

    /**
     * Save session to Redis with TTL
     */
    public DeviceSession save(DeviceSession session) {
        try {
            String sessionKey = SESSION_KEY_PREFIX + session.getId();
            String imeiIndexKey = IMEI_INDEX_PREFIX + session.getImei();
            String channelIndexKey = CHANNEL_INDEX_PREFIX + session.getChannelId();

            // Store session data
            redisTemplate.opsForValue().set(sessionKey, session, DEFAULT_TTL);

            // Create indexes for fast lookup
            redisTemplate.opsForValue().set(imeiIndexKey, session.getId(), DEFAULT_TTL);
            redisTemplate.opsForValue().set(channelIndexKey, session.getId(), DEFAULT_TTL);

            // Add to active sessions set
            redisTemplate.opsForSet().add(ACTIVE_SESSIONS_SET, session.getId());

            log.debug("💾 Saved session to Redis: {}", session.getId());
            return session;

        } catch (Exception e) {
            log.error("❌ Error saving session to Redis: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save session to Redis", e);
        }
    }

    /**
     * Find session by ID
     */
    public Optional<DeviceSession> findById(String sessionId) {
        try {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;
            DeviceSession session = (DeviceSession) redisTemplate.opsForValue().get(sessionKey);
            return Optional.ofNullable(session);

        } catch (Exception e) {
            log.error("❌ Error finding session by ID {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by IMEI
     */
    public Optional<DeviceSession> findByImei(String imei) {
        try {
            String imeiIndexKey = IMEI_INDEX_PREFIX + imei;
            String sessionId = (String) redisTemplate.opsForValue().get(imeiIndexKey);

            if (sessionId != null) {
                return findById(sessionId);
            }
            return Optional.empty();

        } catch (Exception e) {
            log.error("❌ Error finding session by IMEI {}: {}", imei, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Find session by channel ID
     */
    public Optional<DeviceSession> findByChannelId(String channelId) {
        try {
            String channelIndexKey = CHANNEL_INDEX_PREFIX + channelId;
            String sessionId = (String) redisTemplate.opsForValue().get(channelIndexKey);

            if (sessionId != null) {
                return findById(sessionId);
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
                return List.of();
            }

            return sessionIds.stream()
                    .map(Object::toString)
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
     * Find sessions that haven't been active since cutoff time
     */
    public List<DeviceSession> findByLastActivityAtBefore(Instant cutoffTime) {
        return findAll().stream()
                .filter(session -> session.getLastActivityAt() != null && 
                                 session.getLastActivityAt().isBefore(cutoffTime))
                .collect(Collectors.toList());
    }

    /**
     * Find authenticated sessions
     */
    public List<DeviceSession> findByAuthenticated(boolean authenticated) {
        return findAll().stream()
                .filter(session -> session.isAuthenticated() == authenticated)
                .collect(Collectors.toList());
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
     * Count active sessions (alias for compatibility)
     */
    public long countActive() {
        return count();
    }

    /**
     * Delete session by ID
     */
    public void deleteById(String sessionId) {
        try {
            Optional<DeviceSession> sessionOpt = findById(sessionId);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();

                // Remove from Redis
                String sessionKey = SESSION_KEY_PREFIX + sessionId;
                String imeiIndexKey = IMEI_INDEX_PREFIX + session.getImei();
                String channelIndexKey = CHANNEL_INDEX_PREFIX + session.getChannelId();

                redisTemplate.delete(sessionKey);
                redisTemplate.delete(imeiIndexKey);
                redisTemplate.delete(channelIndexKey);

                // Remove from active sessions set
                redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_SET, sessionId);

                log.debug("🗑️ Deleted session from Redis: {}", sessionId);
            }

        } catch (Exception e) {
            log.error("❌ Error deleting session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Delete session by DeviceSession object
     */
    public void delete(DeviceSession session) {
        if (session != null) {
            deleteById(session.getId());
        }
    }

    /**
     * Check if session exists by IMEI
     */
    public boolean existsByImei(String imei) {
        try {
            String imeiIndexKey = IMEI_INDEX_PREFIX + imei;
            return Boolean.TRUE.equals(redisTemplate.hasKey(imeiIndexKey));

        } catch (Exception e) {
            log.error("❌ Error checking session existence for IMEI {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Update session TTL
     */
    public void refreshTTL(String sessionId) {
        try {
            String sessionKey = SESSION_KEY_PREFIX + sessionId;
            redisTemplate.expire(sessionKey, DEFAULT_TTL);

        } catch (Exception e) {
            log.error("❌ Error refreshing TTL for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Get session statistics
     */
    public Map<String, Long> getSessionStatistics() {
        try {
            List<DeviceSession> allSessions = findAll();

            long total = allSessions.size();
            long authenticated = allSessions.stream()
                    .filter(DeviceSession::isAuthenticated)
                    .count();
            long withLocation = allSessions.stream()
                    .filter(DeviceSession::hasValidLocation)
                    .count();
            long active = allSessions.stream()
                    .filter(DeviceSession::isChannelActive)
                    .count();

            return Map.of(
                    "total", total,
                    "authenticated", authenticated,
                    "withLocation", withLocation,
                    "active", active
            );

        } catch (Exception e) {
            log.error("❌ Error getting session statistics: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Clear all sessions (for testing/maintenance)
     */
    public void deleteAll() {
        try {
            // Get all session IDs first
            Set<Object> sessionIds = redisTemplate.opsForSet().members(ACTIVE_SESSIONS_SET);

            if (sessionIds != null) {
                // Delete each session properly
                sessionIds.forEach(sessionId -> deleteById(sessionId.toString()));
            }

            // Clear the active sessions set
            redisTemplate.delete(ACTIVE_SESSIONS_SET);

            log.info("🧹 Cleared all sessions from Redis");

        } catch (Exception e) {
            log.error("❌ Error clearing all sessions: {}", e.getMessage(), e);
        }
    }
}
