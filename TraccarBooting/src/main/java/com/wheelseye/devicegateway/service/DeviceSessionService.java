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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Fixed Device Session Service - Resolved circular dependencies and SpEL issues
 */
@Service
public class DeviceSessionService implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(DeviceSessionService.class);

    private final RedisSessionRepository sessionRepository;

    // Fixed property paths and removed problematic SpEL expressions
    @Value("${device-gateway.session.idle-timeout-seconds:600}")
    private long sessionIdleTimeoutSeconds;

    @Value("${device-gateway.session.max-sessions:10000}")
    private int maxSessions;

    public DeviceSessionService(RedisSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Create or update device session
     */
    @CacheEvict(value = { "device-sessions", "session-stats" }, allEntries = true)
    public DeviceSession createOrUpdateSession(IMEI imei, Channel channel) {
        if (imei == null || channel == null) {
            throw new IllegalArgumentException("IMEI and Channel cannot be null");
        }

        Optional<DeviceSession> existingSession = sessionRepository.findByImei(imei);

        if (existingSession.isPresent()) {
            logger.info("Updating existing session for IMEI: {}", imei.value());
            return updateExistingSession(existingSession.get(), channel);
        } else {
            logger.info("Creating new session for IMEI: {}", imei.value());
            return createNewSession(imei, channel);
        }
    }

    private DeviceSession createNewSession(IMEI imei, Channel channel) {
        if (getActiveSessionCount() >= maxSessions) {
            throw new RuntimeException("Maximum session limit reached: " + maxSessions);
        }

        try {
            DeviceSession session = DeviceSession.create(
                    imei,
                    channel.id().asShortText(),
                    extractRemoteAddress(channel));

            sessionRepository.save(session);

            logger.info("Created session: {} for IMEI: {}", session.getId(), imei.value());
            return session;

        } catch (Exception e) {
            logger.error("Failed to create session for IMEI: {}", imei.value(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    private DeviceSession updateExistingSession(DeviceSession existingSession, Channel newChannel) {
        try {
            existingSession.setChannel(
                    newChannel.id().asShortText(),
                    extractRemoteAddress(newChannel));

            sessionRepository.save(existingSession);

            logger.debug("Updated session channel: {}", existingSession.getId());
            return existingSession;

        } catch (Exception e) {
            logger.error("Failed to update session: {}", existingSession.getId(), e);
            throw new RuntimeException("Failed to update session", e);
        }
    }

    @Cacheable(value = "session-by-imei", key = "#imei.value()")
    public Optional<DeviceSession> getSession(IMEI imei) {
        if (imei == null) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findByImei(imei);
        } catch (Exception e) {
            logger.error("Error getting session for IMEI: {}", imei.value(), e);
            return Optional.empty();
        }
    }

    public Optional<DeviceSession> getSession(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findByChannel(channel);
        } catch (Exception e) {
            logger.error("Error getting session for channel: {}", channel.id().asShortText(), e);
            return Optional.empty();
        }
    }

    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }

        try {
            sessionRepository.delete(sessionId);
            logger.info("Removed session: {}", sessionId);
        } catch (Exception e) {
            logger.error("Error removing session: {}", sessionId, e);
        }
    }

    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void removeSession(Channel channel) {
        if (channel == null) {
            return;
        }

        try {
            Optional<DeviceSession> sessionOpt = getSession(channel);
            sessionOpt.ifPresent(session -> removeSession(session.getId()));
        } catch (Exception e) {
            logger.error("Error removing session for channel: {}", channel.id().asShortText(), e);
        }
    }

    public CompletableFuture<Void> updateLastPosition(String imei, double latitude, double longitude,
            Instant timestamp) {
        return CompletableFuture.runAsync(() -> {
            try {
                IMEI imeiObj = IMEI.of(imei);
                Optional<DeviceSession> sessionOpt = getSession(imeiObj);

                if (sessionOpt.isPresent()) {
                    DeviceSession session = sessionOpt.get();
                    session.updatePosition(latitude, longitude, timestamp);
                    sessionRepository.save(session);
                    logger.debug("Updated position for {}: [{}, {}]", imei, latitude, longitude);
                }
            } catch (Exception e) {
                logger.error("Error updating position for: {}", imei, e);
            }
        });
    }

    public void updateLastHeartbeat(String imei) {
        try {
            IMEI imeiObj = IMEI.of(imei);
            Optional<DeviceSession> sessionOpt = getSession(imeiObj);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                session.setLastHeartbeat(Instant.now());
                sessionRepository.save(session);
                logger.debug("Updated heartbeat for: {}", imei);
            }
        } catch (Exception e) {
            logger.error("Error updating heartbeat for: {}", imei, e);
        }
    }

    @Cacheable(value = "device-sessions")
    public List<DeviceSession> getAllSessions() {
        try {
            return sessionRepository.findAll();
        } catch (Exception e) {
            logger.error("Error getting all sessions", e);
            return List.of();
        }
    }

    @Cacheable(value = "session-stats")
    public SessionStats getSessionStats() {
        try {
            List<DeviceSession> sessions = getAllSessions();
            int authenticatedCount = (int) sessions.stream()
                    .filter(DeviceSession::isAuthenticated)
                    .count();

            return new SessionStats(
                    sessions.size(),
                    authenticatedCount,
                    sessions.size() - authenticatedCount);
        } catch (Exception e) {
            logger.error("Error getting session stats", e);
            return new SessionStats(0, 0, 0);
        }
    }

    // Disconnect device and clean up session
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public boolean disconnectDevice(IMEI imei) {
        try {
            var sessionOpt = getSession(imei);

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                removeSession(session.getId());

                logger.info("🔌 Disconnected device: {} (session: {})", imei.value(), session.getId());
                return true;
            } else {
                logger.debug("📭 No active session found for device: {}", imei.value());
                return false;
            }

        } catch (Exception e) {
            logger.error("💥 Error disconnecting device: {}", imei.value(), e);
            return false;
        }
    }

    // Fixed @Scheduled annotation - removed problematic SpEL
    @Scheduled(fixedRate = 60000) // 1 minute
    @CacheEvict(value = { "device-sessions", "session-stats", "session-by-imei" }, allEntries = true)
    public void cleanupIdleSessions() {
        CompletableFuture.runAsync(() -> {
            try {
                List<DeviceSession> idleSessions = sessionRepository.findIdleSessions(sessionIdleTimeoutSeconds);

                int cleanedUp = 0;
                for (DeviceSession session : idleSessions) {
                    try {
                        sessionRepository.delete(session.getId());
                        cleanedUp++;
                    } catch (Exception e) {
                        logger.error("Error cleaning session: {}", session.getId(), e);
                    }
                }

                if (cleanedUp > 0) {
                    logger.info("Cleaned up {} idle sessions", cleanedUp);
                }
            } catch (Exception e) {
                logger.error("Error during session cleanup", e);
            }
        });
    }

    @Override
    public Health health() {
        try {
            SessionStats stats = getSessionStats();
            int activeSessionCount = getActiveSessionCount();

            return Health.up()
                    .withDetail("activeSessions", activeSessionCount)
                    .withDetail("authenticatedSessions", stats.authenticatedSessions())
                    .withDetail("unauthenticatedSessions", stats.unauthenticatedSessions())
                    .withDetail("maxSessions", maxSessions)
                    .withDetail("sessionUtilization", String.format("%.2f%%",
                            (activeSessionCount / (double) maxSessions) * 100))
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    // Helper methods
    private String extractRemoteAddress(Channel channel) {
        return channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";
    }

    private int getActiveSessionCount() {
        return getAllSessions().size();
    }

    public record SessionStats(
            int totalSessions,
            int authenticatedSessions,
            int unauthenticatedSessions) {
    }
}