package com.wheelseye.devicegateway.service;

import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.repository.RedisSessionRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-ready Device Session Service with performance optimizations.
 * 
 * Key improvements:
 * - Fixed excessive logging from scheduled tasks
 * - Reduced session lookup frequency to fix excessive Redis queries
 * - Asynchronous position updates to prevent blocking
 * - Comprehensive health monitoring with detailed metrics
 * - Proper cache management and eviction strategies
 * - Performance counters for monitoring and debugging
 */
@Service
public class DeviceSessionService implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionService.class);

    // Performance counters
    private final AtomicLong sessionCreatedCount = new AtomicLong(0);
    private final AtomicLong sessionUpdatedCount = new AtomicLong(0);
    private final AtomicLong positionUpdateCount = new AtomicLong(0);
    private final AtomicLong heartbeatCount = new AtomicLong(0);
    private final AtomicLong cleanupRunCount = new AtomicLong(0);

    private final RedisSessionRepository sessionRepository;
    private final Duration sessionIdleTimeout;
    private final int maxSessions;

    public DeviceSessionService(
            RedisSessionRepository sessionRepository,
            @Value("${device-gateway.session.idle-timeout-seconds:1800}") long idleTimeoutSeconds,
            @Value("${device-gateway.session.max-sessions:10000}") int maxSessions) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "Session repository must not be null");
        this.sessionIdleTimeout = Duration.ofSeconds(idleTimeoutSeconds);
        this.maxSessions = maxSessions;
        
        log.info("🔧 DeviceSessionService initialized - idleTimeout: {}s, maxSessions: {}", 
                 idleTimeoutSeconds, maxSessions);
    }

    /**
     * Creates or updates a device session for the given IMEI and channel.
     * Optimized to reduce unnecessary Redis calls.
     */
    @CacheEvict(value = { "device-sessions", "session-stats" }, allEntries = true)
    public DeviceSession createOrUpdateSession(IMEI imei, Channel channel) {
        Objects.requireNonNull(imei, "IMEI must not be null");
        Objects.requireNonNull(channel, "Channel must not be null");

        try {
            var existing = sessionRepository.findByImei(imei);

            if (existing.isPresent()) {
                var session = existing.get();
                log.info("🔄 Updating existing session for device: {}", imei.value());

                // Since channelId and remoteAddress are immutable, we can only update the channel
                session.setChannel(channel);
                session.touch(); // Updates lastActivityAt

                sessionRepository.save(session);
                sessionUpdatedCount.incrementAndGet();
                return session;

            } else {
                log.info("🆕 Creating new session for device: {}", imei.value());
                return createNewSession(imei, channel);
            }

        } catch (Exception e) {
            log.error("❌ Failed to create or update session for device {}: {}",
                    imei.value(), e.getMessage(), e);
            throw new RuntimeException("Session operation failed", e);
        }
    }

    /**
     * Retrieves session by IMEI with caching for performance.
     */
    @Cacheable(value = "session-by-imei", key = "#imei.value()", unless = "#result.isEmpty()")
    public Optional<DeviceSession> getSession(IMEI imei) {
        if (imei == null) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findByImei(imei);
        } catch (Exception e) {
            log.error("❌ Error retrieving session for device {}: {}",
                    imei.value(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Retrieves session by channel without caching to avoid excessive lookups.
     */
    public Optional<DeviceSession> getSession(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findByChannel(channel);
        } catch (Exception e) {
            log.error("❌ Error retrieving session for channel {}: {}",
                    channel.id().asShortText(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Updates device position asynchronously to prevent blocking.
     */
    public CompletableFuture<Void> updateLastPosition(String imei, double latitude, double longitude,
            Instant timestamp) {
        return CompletableFuture.runAsync(() -> {
            try {
                var imeiObj = IMEI.of(imei);
                var sessionOpt = sessionRepository.findByImei(imeiObj);

                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();

                    // Update position using available methods
                    session.setLastLatitude(latitude);
                    session.setLastLongitude(longitude);
                    session.setLastPositionTime(timestamp); // Use correct method name
                    session.touch(); // Updates lastActivityAt

                    sessionRepository.save(session);
                    positionUpdateCount.incrementAndGet();

                    log.debug("📍 Updated position for {}: [{:.6f}, {:.6f}]",
                            imei, latitude, longitude);
                } else {
                    log.warn("⚠️ No session found for position update: {}", imei);
                }

            } catch (Exception e) {
                log.error("❌ Error updating position for {}: {}", imei, e.getMessage(), e);
            }
        });
    }

    /**
     * Updates device heartbeat timestamp without excessive logging.
     */
    public void updateLastHeartbeat(String imei) {
        try {
            var imeiObj = IMEI.of(imei);
            var sessionOpt = sessionRepository.findByImei(imeiObj);

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                session.touch(); // This updates lastActivityAt internally

                sessionRepository.save(session);
                heartbeatCount.incrementAndGet();

                // Only log heartbeat every 100th time to reduce log spam
                if (heartbeatCount.get() % 100 == 0) {
                    log.debug("💓 Heartbeat batch update for {}: count={}",
                            imei, heartbeatCount.get());
                }
            }
        } catch (Exception e) {
            log.error("❌ Error updating heartbeat for {}: {}", imei, e.getMessage(), e);
        }
    }

    /**
     * Removes session by ID with proper cleanup.
     */
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            sessionRepository.delete(sessionId);
            log.info("🗑️ Removed session: {}", sessionId);
        } catch (Exception e) {
            log.error("❌ Error removing session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Removes session by channel with proper error handling.
     */
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void removeSession(Channel channel) {
        if (channel == null) {
            return;
        }

        try {
            var sessionOpt = sessionRepository.findByChannel(channel);
            sessionOpt.ifPresent(session -> removeSession(session.getId()));
        } catch (Exception e) {
            log.error("❌ Error removing session for channel {}: {}",
                    channel.id().asShortText(), e.getMessage(), e);
        }
    }

    /**
     * Retrieves all active sessions with caching.
     */
    @Cacheable("device-sessions")
    public List<DeviceSession> getAllSessions() {
        try {
            return sessionRepository.findAllActive();
        } catch (Exception e) {
            log.error("❌ Error fetching all sessions", e);
            return List.of();
        }
    }

    /**
     * Retrieves session statistics with caching.
     */
    @Cacheable("session-stats")
    public SessionStats getSessionStats() {
        try {
            var sessions = getAllSessions();
            long authenticatedCount = sessions.stream()
                    .filter(DeviceSession::isAuthenticated)
                    .count();

            return new SessionStats(
                    sessions.size(),
                    (int) authenticatedCount,
                    sessions.size() - (int) authenticatedCount,
                    sessionCreatedCount.get(),
                    sessionUpdatedCount.get(),
                    positionUpdateCount.get(),
                    heartbeatCount.get());
        } catch (Exception e) {
            log.error("❌ Error computing session stats", e);
            return new SessionStats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Disconnects device and removes session.
     */
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public boolean disconnectDevice(IMEI imei) {
        try {
            var sessionOpt = sessionRepository.findByImei(imei);

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                removeSession(session.getId());
                log.info("📵 Disconnected device: {} (session: {})",
                        imei.value(), session.getId());
                return true;
            } else {
                log.debug("📭 No active session found for device: {}", imei.value());
                return false;
            }

        } catch (Exception e) {
            log.error("❌ Error disconnecting device {}: {}", imei.value(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * FIXED: Scheduled cleanup with proper interval and reduced logging.
     * Runs every 5 minutes (300,000ms) instead of every 60ms to prevent log spam.
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes fixed delay (not rate-based)
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void cleanupIdleSessions() {
        // Run cleanup asynchronously to prevent blocking
        CompletableFuture.runAsync(() -> {
            long runNumber = cleanupRunCount.incrementAndGet();
            
            try {
                var startTime = Instant.now();
                var idleSessions = sessionRepository.findIdle(sessionIdleTimeout);
                var cleanedUp = 0;

                for (var session : idleSessions) {
                    try {
                        sessionRepository.delete(session.getId());
                        cleanedUp++;
                    } catch (Exception e) {
                        log.error("❌ Error cleaning session {}: {}",
                                session.getId(), e.getMessage(), e);
                    }
                }

                var duration = Duration.between(startTime, Instant.now());

                // Only log when there are sessions to clean or every 12th run (1 hour)
                if (cleanedUp > 0) {
                    log.info("🧹 Cleaned up {} idle sessions in {}ms (run #{})",
                            cleanedUp, duration.toMillis(), runNumber);
                } else if (runNumber % 12 == 0) {
                    log.info("🧹 Session cleanup completed - no idle sessions found (run #{}, {}ms)",
                            runNumber, duration.toMillis());
                } else {
                    log.debug("🧹 No idle sessions to clean (run #{}, checked in {}ms)",
                            runNumber, duration.toMillis());
                }

            } catch (Exception e) {
                log.error("❌ Error during session cleanup (run #{}): {}", runNumber, e.getMessage(), e);
            }
        });
    }

    /**
     * Enhanced health check with detailed metrics.
     */
    @Override
    public Health health() {
        try {
            var stats = getSessionStats();
            var utilization = maxSessions > 0 ? (stats.totalSessions() / (double) maxSessions) * 100 : 0;

            var healthBuilder = utilization < 90 ? Health.up() : Health.down();

            return healthBuilder
                    .withDetail("activeSessions", stats.totalSessions())
                    .withDetail("authenticatedSessions", stats.authenticatedSessions())
                    .withDetail("unauthenticatedSessions", stats.unauthenticatedSessions())
                    .withDetail("maxSessions", maxSessions)
                    .withDetail("sessionUtilization", String.format("%.1f%%", utilization))
                    .withDetail("idleTimeout", sessionIdleTimeout.toString())
                    .withDetail("cleanupRuns", cleanupRunCount.get())
                    .withDetail("performanceCounters", Map.of(
                            "sessionsCreated", stats.sessionsCreated(),
                            "sessionsUpdated", stats.sessionsUpdated(),
                            "positionUpdates", stats.positionUpdates(),
                            "heartbeats", stats.heartbeats()))
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withException(e)
                    .build();
        }
    }

    // Private helper methods
    private DeviceSession createNewSession(IMEI imei, Channel channel) {
        var currentSessionCount = sessionRepository.countActive();

        if (currentSessionCount >= maxSessions) {
            throw new RuntimeException("Maximum session limit reached: " + maxSessions);
        }

        try {
            // Use the factory method or constructor directly
            var session = DeviceSession.create(
                    imei,
                    channel.id().asShortText(),
                    extractRemoteAddress(channel));

            // Set the channel after creation
            session.setChannel(channel);
            session.setAuthenticated(true);

            sessionRepository.save(session);
            sessionCreatedCount.incrementAndGet();

            log.info("✅ Created session {} for device: {}", session.getId(), imei.value());
            return session;

        } catch (Exception e) {
            log.error("❌ Failed to create session for device {}: {}",
                    imei.value(), e.getMessage(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    private String extractRemoteAddress(Channel channel) {
        return channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";
    }

    private String generateSessionId() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Enhanced session statistics record with performance counters.
     */
    public record SessionStats(
            int totalSessions,
            int authenticatedSessions,
            int unauthenticatedSessions,
            long sessionsCreated,
            long sessionsUpdated,
            long positionUpdates,
            long heartbeats) {
    }
}
