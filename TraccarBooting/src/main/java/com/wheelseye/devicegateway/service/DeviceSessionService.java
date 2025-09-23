package com.wheelseye.devicegateway.service;

import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.repository.DeviceSessionRepository;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Complete DeviceSessionService - Production-Ready Implementation
 * 
 * Provides all required methods for GPS tracking system with:
 * - Legacy method compatibility for existing handlers
 * - Modern async operations for performance
 * - Comprehensive session lifecycle management
 * - Production-ready error handling
 * 
 * @author WheelsEye Development Team
 * @version 2.1.0 - Complete Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceSessionService {

    private final DeviceSessionRepository sessionRepository;

    // Session configuration
    private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration SESSION_MAX_AGE = Duration.ofHours(2);

    // === LEGACY COMPATIBILITY METHODS (for DeviceBusinessHandler) ===

    /**
     * Legacy method: Create or update session
     * Maps to modern createSession method
     */
    public DeviceSession createOrUpdateSession(String imei, Channel channel) {
        return createSession(imei, channel);
    }

    /**
     * Legacy method: Update last position (synchronous)
     * Maps to modern async method but returns immediately
     */
    public void updateLastPosition(String imei, Double latitude, Double longitude, Instant timestamp) {
        if (latitude == null || longitude == null) {
            log.warn("⚠️ Skipping position update for {} - invalid coordinates", imei);
            return;
        }

        // Call async method but don't wait for result (fire and forget for legacy compatibility)
        updatePositionAsync(imei, latitude, longitude, timestamp)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    log.error("❌ Async position update failed for {}: {}", imei, throwable.getMessage());
                }
            });
    }

    /**
     * Legacy method: Update last heartbeat
     * Maps to modern touchSession method
     */
    public void updateLastHeartbeat(String imei) {
        touchSession(imei);
    }

    /**
     * Legacy method: Remove session by channel
     * Maps to modern disconnect methods
     */
    public void removeSession(Channel channel) {
        if (channel == null) {
            return;
        }

        try {
            Optional<DeviceSession> sessionOpt = getSession(channel);
            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                disconnectSession(session.getImei());
                log.debug("🗑️ Removed session for channel: {} (IMEI: {})", 
                         channel.id().asShortText(), session.getImei());
            } else {
                log.debug("📭 No session found for channel: {}", channel.id().asShortText());
            }
        } catch (Exception e) {
            log.error("❌ Error removing session for channel {}: {}", 
                     channel.id().asShortText(), e.getMessage(), e);
        }
    }

    /**
     * Legacy method: Disconnect device (for DeviceController)
     * Maps to modern disconnectSession method
     */
    public boolean disconnectDevice(String imei) {
        return disconnectSession(imei);
    }

    // === MODERN SESSION LIFECYCLE MANAGEMENT ===

    /**
     * Create new device session or update existing one
     */
    public DeviceSession createSession(String imei, Channel channel) {
        try {
            // Validate inputs
            if (imei == null || imei.trim().isEmpty()) {
                throw new IllegalArgumentException("IMEI cannot be null or empty");
            }
            if (channel == null) {
                throw new IllegalArgumentException("Channel cannot be null");
            }

            // Check for existing session
            Optional<DeviceSession> existing = sessionRepository.findByImei(imei);
            if (existing.isPresent()) {
                log.debug("🔄 Updating existing session for IMEI: {}", imei);
                DeviceSession session = existing.get()
                        .setChannel(channel)
                        .setStatus(DeviceSession.SessionStatus.ACTIVE)
                        .touch();
                return sessionRepository.save(session);
            }

            // Create new session
            DeviceSession session = DeviceSession.create(imei, channel);
            DeviceSession savedSession = sessionRepository.save(session);

            log.info("✅ Created session {} for device: {}", savedSession.getId(), imei);
            return savedSession;

        } catch (Exception e) {
            log.error("❌ Failed to create session for IMEI {}: {}", imei, e.getMessage(), e);
            throw new RuntimeException("Failed to create device session", e);
        }
    }

    /**
     * Get session by ID with proper error handling
     */
    public Optional<DeviceSession> getSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findById(sessionId);
        } catch (Exception e) {
            log.error("❌ Error retrieving session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get session by IMEI
     */
    public Optional<DeviceSession> getSessionByImei(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            return sessionRepository.findByImei(imei);
        } catch (Exception e) {
            log.error("❌ Error retrieving session by IMEI {}: {}", imei, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Get session by channel
     */
    public Optional<DeviceSession> getSession(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }

        try {
            String channelId = channel.id().asShortText();
            return sessionRepository.findByChannelId(channelId);
        } catch (Exception e) {
            log.error("❌ Error retrieving session by channel: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    // === SESSION UPDATES ===

    /**
     * Update session position asynchronously
     */
    @Async
    public CompletableFuture<Boolean> updatePositionAsync(String imei, double latitude, double longitude, Instant timestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

                if (sessionOpt.isEmpty()) {
                    log.debug("📍 No session found for position update: {}", imei);
                    return false;
                }

                DeviceSession session = sessionOpt.get()
                        .updatePosition(latitude, longitude, timestamp)
                        .touch();

                sessionRepository.save(session);

                log.debug("📍 Updated position for {}: [{:.6f}, {:.6f}]", imei, latitude, longitude);
                return true;

            } catch (Exception e) {
                log.error("❌ Failed to update position for IMEI {}: {}", imei, e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * Update session status asynchronously
     */
    @Async  
    public CompletableFuture<Boolean> updateStatusAsync(String imei, int signalStrength, boolean isCharging, int batteryLevel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

                if (sessionOpt.isEmpty()) {
                    log.debug("📊 No session found for status update: {}", imei);
                    return false;
                }

                DeviceSession session = sessionOpt.get()
                        .updateStatus(signalStrength, isCharging, batteryLevel)
                        .touch();

                sessionRepository.save(session);

                log.debug("📊 Updated status for {}: signal={}, charging={}, battery={}%", 
                         imei, signalStrength, isCharging, batteryLevel);
                return true;

            } catch (Exception e) {
                log.error("❌ Failed to update status for IMEI {}: {}", imei, e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * Touch session to update last activity
     */
    public boolean touchSession(String imei) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

            if (sessionOpt.isEmpty()) {
                log.debug("👋 No session found for heartbeat update: {}", imei);
                return false;
            }

            DeviceSession session = sessionOpt.get().touch();
            sessionRepository.save(session);

            log.debug("👋 Session activity updated for: {}", imei);
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to touch session for IMEI {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark session as authenticated
     */
    public boolean authenticateSession(String imei) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

            if (sessionOpt.isEmpty()) {
                log.warn("🔐 No session found for authentication: {}", imei);
                return false;
            }

            DeviceSession session = sessionOpt.get()
                    .setAuthenticated(true)
                    .setStatus(DeviceSession.SessionStatus.ACTIVE)
                    .touch();

            sessionRepository.save(session);

            log.info("🔐 Session authenticated for: {}", imei);
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to authenticate session for IMEI {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Mark session as disconnected
     */
    public boolean disconnectSession(String imei) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

            if (sessionOpt.isEmpty()) {
                log.debug("📵 No session found for disconnect: {}", imei);
                return false;
            }

            DeviceSession session = sessionOpt.get().markDisconnected();

            // Remove session completely for clean disconnect
            sessionRepository.deleteById(session.getId());

            log.info("📵 Session disconnected and removed for: {}", imei);
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to disconnect session for IMEI {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }

    // === SESSION QUERIES ===

    /**
     * Get all active sessions
     */
    public List<DeviceSession> getAllSessions() {
        try {
            return sessionRepository.findAll();
        } catch (Exception e) {
            log.error("❌ Error retrieving all sessions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get session count
     */
    public long getSessionCount() {
        try {
            return sessionRepository.count();
        } catch (Exception e) {
            log.error("❌ Error counting sessions: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * Check if session exists and is active
     */
    public boolean isSessionActive(String imei) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
            return sessionOpt.map(DeviceSession::isActive).orElse(false);
        } catch (Exception e) {
            log.error("❌ Error checking session status for IMEI {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }

    // === CLEANUP OPERATIONS ===

    /**
     * Scheduled cleanup of idle sessions (every 5 minutes)
     */
    @Scheduled(fixedRate = 300_000) // 5 minutes
    public void cleanupIdleSessions() {
        try {
            Instant cutoffTime = Instant.now().minus(SESSION_IDLE_TIMEOUT);
            List<DeviceSession> idleSessions = sessionRepository.findByLastActivityAtBefore(cutoffTime);

            if (idleSessions.isEmpty()) {
                log.debug("🧹 No idle sessions found for cleanup");
                return;
            }

            int cleaned = 0;
            for (DeviceSession session : idleSessions) {
                try {
                    sessionRepository.deleteById(session.getId());
                    cleaned++;
                    log.debug("🧹 Cleaned up idle session: {} (IMEI: {})", session.getId(), session.getImei());
                } catch (Exception e) {
                    log.warn("⚠️ Failed to cleanup session {}: {}", session.getId(), e.getMessage());
                }
            }

            log.info("🧹 Cleaned up {} idle sessions", cleaned);

        } catch (Exception e) {
            log.error("❌ Error during scheduled cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual cleanup of sessions older than max age
     */
    public int cleanupOldSessions() {
        try {
            Instant cutoffTime = Instant.now().minus(SESSION_MAX_AGE);
            List<DeviceSession> oldSessions = sessionRepository.findByLastActivityAtBefore(cutoffTime);

            int cleaned = 0;
            for (DeviceSession session : oldSessions) {
                try {
                    sessionRepository.deleteById(session.getId());
                    cleaned++;
                } catch (Exception e) {
                    log.warn("⚠️ Failed to cleanup old session {}: {}", session.getId(), e.getMessage());
                }
            }

            if (cleaned > 0) {
                log.info("🧹 Manually cleaned up {} old sessions", cleaned);
            }

            return cleaned;

        } catch (Exception e) {
            log.error("❌ Error during manual cleanup: {}", e.getMessage(), e);
            return 0;
        }
    }

    // === STATISTICS ===

    /**
     * Get comprehensive session statistics
     */
    public SessionStatistics getStatistics() {
        try {
            List<DeviceSession> allSessions = getAllSessions();

            long total = allSessions.size();
            long authenticated = allSessions.stream().filter(DeviceSession::isAuthenticated).count();
            long withLocation = allSessions.stream().filter(DeviceSession::hasValidLocation).count();
            long active = allSessions.stream().filter(DeviceSession::isChannelActive).count();

            return new SessionStatistics(total, authenticated, withLocation, active, 
                                       sessionRepository.isHealthy(), Instant.now());

        } catch (Exception e) {
            log.error("❌ Error getting session statistics: {}", e.getMessage(), e);
            return new SessionStatistics(0, 0, 0, 0, false, Instant.now());
        }
    }

    /**
     * Get session stats in legacy format for DeviceController compatibility
     */
    public SessionStats getSessionStats() {
        SessionStatistics stats = getStatistics();
        return new SessionStats(
            (int) stats.total(),
            (int) stats.authenticated(),
            (int) (stats.total() - stats.authenticated()),
            (int) stats.active(),
            (int) stats.withLocation(),
            0L, // cleanupRunsCount
            stats.timestamp()
        );
    }

    // === RECORDS AND CLASSES ===

    /**
     * Modern session statistics record
     */
    public record SessionStatistics(
            long total,
            long authenticated,
            long withLocation,
            long active,
            boolean healthy,
            Instant timestamp
    ) {}

    /**
     * Legacy session statistics (for backward compatibility)
     */
    @lombok.Value
    @lombok.Builder
    public static class SessionStats {
        int totalSessions;
        int authenticatedSessions;
        int unauthenticatedSessions;
        int activeChannels;
        int sessionsWithLocation;
        long cleanupRunsCount;
        Instant lastUpdateTime;

        public int getTotalSessions() { return totalSessions; }
        public int getAuthenticatedSessions() { return authenticatedSessions; }
        public int getUnauthenticatedSessions() { return unauthenticatedSessions; }
        public int getActiveChannels() { return activeChannels; }
        public int getSessionsWithLocation() { return sessionsWithLocation; }
        public long getCleanupRunsCount() { return cleanupRunsCount; }
        public Instant getLastUpdateTime() { return lastUpdateTime; }
    }
}