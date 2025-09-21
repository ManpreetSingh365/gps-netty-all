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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Production-ready Device Session Service following modern Java 21 and Spring Boot 3.5.5 practices.
 * Handles device session lifecycle with proper error handling, caching, and monitoring.
 */
@Service
public class DeviceSessionService implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionService.class);

    private final RedisSessionRepository sessionRepository;
    private final Duration sessionIdleTimeout;
    private final int maxSessions;

    public DeviceSessionService(
            RedisSessionRepository sessionRepository,
            @Value("${device-gateway.session.idle-timeout-seconds:600}") long idleTimeoutSeconds,
            @Value("${device-gateway.session.max-sessions:10000}") int maxSessions) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "Session repository must not be null");
        this.sessionIdleTimeout = Duration.ofSeconds(idleTimeoutSeconds);
        this.maxSessions = maxSessions;
    }

    /**
     * Creates or updates a device session for the given IMEI and channel.
     * 
     * @param imei the device IMEI
     * @param channel the network channel
     * @return the created or updated session
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if session limit exceeded or creation fails
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public DeviceSession createOrUpdateSession(IMEI imei, Channel channel) {
        Objects.requireNonNull(imei, "IMEI must not be null");
        Objects.requireNonNull(channel, "Channel must not be null");

        try {
            Optional<DeviceSession> existing = sessionRepository.findByImei(imei);
            if (existing.isPresent()) {
                log.info("Updating existing session for IMEI: {}", imei.value());
                return updateExistingSession(existing.get(), channel);
            } else {
                log.info("Creating new session for IMEI: {}", imei.value());
                return createNewSession(imei, channel);
            }
        } catch (Exception e) {
            log.error("Failed to create or update session for IMEI: {}", imei.value(), e);
            throw new RuntimeException("Session operation failed", e);
        }
    }

    /**
     * Retrieves session by IMEI with caching.
     */
    @Cacheable(value = "session-by-imei", key = "#imei.value()")
    public Optional<DeviceSession> getSession(IMEI imei) {
        if (imei == null) {
            return Optional.empty();
        }
        try {
            return sessionRepository.findByImei(imei);
        } catch (Exception e) {
            log.error("Error retrieving session for IMEI: {}", imei.value(), e);
            return Optional.empty();
        }
    }

    /**
     * Retrieves session by channel.
     */
    public Optional<DeviceSession> getSession(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }
        try {
            return sessionRepository.findByChannel(channel);
        } catch (Exception e) {
            log.error("Error retrieving session for channel: {}", channel.id().asShortText(), e);
            return Optional.empty();
        }
    }

    /**
     * Removes session by ID with cache eviction.
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessionRepository.delete(sessionId);
            log.info("Removed session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error removing session: {}", sessionId, e);
        }
    }

    /**
     * Removes session by channel.
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void removeSession(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            getSession(channel).ifPresent(session -> removeSession(session.getId()));
        } catch (Exception e) {
            log.error("Error removing session for channel: {}", channel.id().asShortText(), e);
        }
    }

    /**
     * Updates device position asynchronously.
     */
    public CompletableFuture<Void> updateLastPosition(String imei, double latitude, double longitude, Instant timestamp) {
        return CompletableFuture.runAsync(() -> {
            try {
                IMEI imeiObj = IMEI.of(imei);
                Optional<DeviceSession> sessionOpt = getSession(imeiObj);
                
                if (sessionOpt.isPresent()) {
                    DeviceSession session = sessionOpt.get();
                    session.updatePosition(latitude, longitude, timestamp);
                    sessionRepository.save(session);
                    log.debug("Updated position for {}: [{}, {}]", imei, latitude, longitude);
                }
            } catch (Exception e) {
                log.error("Error updating position for: {}", imei, e);
            }
        });
    }

    /**
     * Updates device heartbeat timestamp.
     */
    public void updateLastHeartbeat(String imei) {
        try {
            IMEI imeiObj = IMEI.of(imei);
            Optional<DeviceSession> sessionOpt = getSession(imeiObj);
            
            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                session.setLastHeartbeat(Instant.now());
                sessionRepository.save(session);
                log.debug("Updated heartbeat for: {}", imei);
            }
        } catch (Exception e) {
            log.error("Error updating heartbeat for: {}", imei, e);
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
            log.error("Error fetching all sessions", e);
            return List.of();
        }
    }

    /**
     * Retrieves session statistics with caching.
     */
    @Cacheable("session-stats")
    public SessionStats getSessionStats() {
        try {
            List<DeviceSession> sessions = getAllSessions();
            long authenticatedCount = sessions.stream()
                    .filter(DeviceSession::isAuthenticated)
                    .count();
                    
            return new SessionStats(
                    sessions.size(),
                    (int) authenticatedCount,
                    sessions.size() - (int) authenticatedCount
            );
        } catch (Exception e) {
            log.error("Error computing session stats", e);
            return new SessionStats(0, 0, 0);
        }
    }

    /**
     * Disconnects device and removes session.
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public boolean disconnectDevice(IMEI imei) {
        try {
            Optional<DeviceSession> sessionOpt = getSession(imei);
            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                removeSession(session.getId());
                log.info("Disconnected device: {} (session: {})", imei.value(), session.getId());
                return true;
            } else {
                log.debug("No active session found for device: {}", imei.value());
                return false;
            }
        } catch (Exception e) {
            log.error("Error disconnecting device: {}", imei.value(), e);
            return false;
        }
    }

    /**
     * Scheduled cleanup of idle sessions with cache eviction.
     */
    @Scheduled(fixedRateString = "${device-gateway.session.cleanup-interval:60000}")
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void cleanupIdleSessions() {
        CompletableFuture.runAsync(() -> {
            try {
                List<DeviceSession> idleSessions = sessionRepository.findIdle(sessionIdleTimeout);
                int cleanedUp = 0;
                
                for (DeviceSession session : idleSessions) {
                    try {
                        sessionRepository.delete(session.getId());
                        cleanedUp++;
                    } catch (Exception e) {
                        log.error("Error cleaning session: {}", session.getId(), e);
                    }
                }
                
                if (cleanedUp > 0) {
                    log.info("Cleaned up {} idle sessions", cleanedUp);
                }
            } catch (Exception e) {
                log.error("Error during session cleanup", e);
            }
        });
    }

    /**
     * Health check endpoint implementation.
     */
    @Override
    public Health health() {
        try {
            SessionStats stats = getSessionStats();
            int activeCount = stats.totalSessions();
            double utilization = maxSessions > 0 ? (activeCount / (double) maxSessions) * 100 : 0;
            
            return Health.up()
                    .withDetail("activeSessions", activeCount)
                    .withDetail("authenticatedSessions", stats.authenticatedSessions())
                    .withDetail("unauthenticatedSessions", stats.unauthenticatedSessions())
                    .withDetail("maxSessions", maxSessions)
                    .withDetail("sessionUtilization", String.format("%.2f%%", utilization))
                    .withDetail("idleTimeout", sessionIdleTimeout.toString())
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
        if (getAllSessions().size() >= maxSessions) {
            throw new RuntimeException("Maximum session limit reached: " + maxSessions);
        }
        
        try {
            DeviceSession session = DeviceSession.create(
                    imei,
                    channel.id().asShortText(),
                    extractRemoteAddress(channel)
            );
            sessionRepository.save(session);
            log.info("Created session: {} for IMEI: {}", session.getId(), imei.value());
            return session;
        } catch (Exception e) {
            log.error("Failed to create session for IMEI: {}", imei.value(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    private DeviceSession updateExistingSession(DeviceSession existingSession, Channel newChannel) {
        try {
            existingSession.setChannel(
                    newChannel.id().asShortText(),
                    extractRemoteAddress(newChannel)
            );
            sessionRepository.save(existingSession);
            log.debug("Updated session channel: {}", existingSession.getId());
            return existingSession;
        } catch (Exception e) {
            log.error("Failed to update session: {}", existingSession.getId(), e);
            throw new RuntimeException("Failed to update session", e);
        }
    }

    private String extractRemoteAddress(Channel channel) {
        return channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";
    }

    /**
     * Session statistics record.
     */
    public record SessionStats(
            int totalSessions,
            int authenticatedSessions,
            int unauthenticatedSessions
    ) {}
}