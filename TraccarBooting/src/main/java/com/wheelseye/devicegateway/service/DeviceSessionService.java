package com.wheelseye.devicegateway.service;

import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.repository.RedisSessionRepository;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DeviceSessionService - Redis-Only Implementation
 * 
 * Complete service using Redis for session storage.
 * No database persistence, lightweight and fast.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceSessionService {

    private final RedisSessionRepository sessionRepository;

    // Configuration properties (can be injected from application.yml)
    private final int maxSessions = 10000;
    private final long sessionIdleTimeout = 1800; // 30 minutes

    // Metrics
    private final AtomicLong sessionCreatedCount = new AtomicLong(0);
    private final AtomicLong cleanupRunCount = new AtomicLong(0);

    /**
     * Create or update device session
     */
    @CacheEvict(value = {"device-sessions", "session-stats"}, allEntries = true)
    public DeviceSession createOrUpdateSession(IMEI imei, Channel channel) {
        Objects.requireNonNull(imei, "IMEI must not be null");
        Objects.requireNonNull(channel, "Channel must not be null");

        try {
            var existing = sessionRepository.findByImei(imei.value());

            if (existing.isPresent()) {
                var session = existing.get();
                log.info("🔄 Updating existing session for device: {}", imei.masked());

                // Update channel and activity
                session.setChannel(channel)
                       .touch()
                       .setAuthenticated(true);

                return sessionRepository.save(session);
            } else {
                return createNewSession(imei, channel);
            }

        } catch (Exception e) {
            log.error("❌ Error creating/updating session for {}: {}", 
                     imei.masked(), e.getMessage(), e);
            throw new RuntimeException("Failed to create/update session", e);
        }
    }

    /**
     * Update device position asynchronously
     */
    @Async
    public CompletableFuture<Void> updateLastPosition(String imei, double latitude, 
                                                     double longitude, Instant timestamp) {
        return CompletableFuture.runAsync(() -> {
            try {
                var imeiObj = IMEI.of(imei);
                var sessionOpt = sessionRepository.findByImei(imeiObj.value());

                if (sessionOpt.isPresent()) {
                    var session = sessionOpt.get();

                    session.setLastLatitude(latitude)
                           .setLastLongitude(longitude)
                           .setLastPositionTime(timestamp)
                           .touch();

                    sessionRepository.save(session);

                    log.debug("📍 Updated position for {}: [{:.6f}, {:.6f}]", 
                             imeiObj.masked(), latitude, longitude);
                } else {
                    log.warn("⚠️ No session found for position update: {}", imeiObj.masked());
                }

            } catch (Exception e) {
                log.error("❌ Error updating position for {}: {}", imei, e.getMessage(), e);
            }
        });
    }

    /**
     * Update last heartbeat timestamp
     */
    public void updateLastHeartbeat(String imei) {
        try {
            var imeiObj = IMEI.of(imei);
            var sessionOpt = sessionRepository.findByImei(imeiObj.value());

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                session.touch();
                sessionRepository.save(session);

                log.debug("💓 Updated heartbeat for: {}", imeiObj.masked());
            } else {
                log.warn("⚠️ No session found for heartbeat update: {}", imeiObj.masked());
            }

        } catch (Exception e) {
            log.error("❌ Error updating heartbeat for {}: {}", imei, e.getMessage(), e);
        }
    }

    /**
     * Get session by IMEI
     */
    @Cacheable(value = "session-by-imei", key = "#imei.value()")
    public Optional<DeviceSession> getSessionByImei(IMEI imei) {
        return sessionRepository.findByImei(imei.value());
    }

    /**
     * Get all active sessions
     */
    @Cacheable("device-sessions")
    public List<DeviceSession> getAllSessions() {
        return sessionRepository.findAll();
    }

    /**
     * Get session statistics
     */
    @Cacheable("session-stats")
    public SessionStats getSessionStats() {
        try {
            var stats = sessionRepository.getSessionStatistics();

            return SessionStats.builder()
                    .totalSessions(stats.getOrDefault("total", 0L))
                    .authenticatedSessions(stats.getOrDefault("authenticated", 0L))
                    .activeChannels(stats.getOrDefault("active", 0L))
                    .sessionsWithLocation(stats.getOrDefault("withLocation", 0L))
                    .createdSessionsCount(sessionCreatedCount.get())
                    .cleanupRunsCount(cleanupRunCount.get())
                    .lastUpdateTime(Instant.now())
                    .build();

        } catch (Exception e) {
            log.error("❌ Error getting session stats: {}", e.getMessage(), e);
            return SessionStats.empty();
        }
    }

    /**
     * Remove session by channel
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void removeSession(Channel channel) {
        if (channel == null) return;

        try {
            var channelId = channel.id().asShortText();
            var sessionOpt = sessionRepository.findByChannelId(channelId);

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();
                sessionRepository.deleteById(session.getId());
                log.info("🗑️ Removed session for channel: {}", channelId);
            }

        } catch (Exception e) {
            log.error("❌ Error removing session for channel {}: {}", 
                     channel.id().asShortText(), e.getMessage(), e);
        }
    }

    /**
     * Remove session by ID
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void removeSession(String sessionId) {
        try {
            sessionRepository.deleteById(sessionId);
            log.debug("🗑️ Removed session: {}", sessionId);
        } catch (Exception e) {
            log.error("❌ Error removing session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * Disconnect device by IMEI
     */
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public boolean disconnectDevice(IMEI imei) {
        try {
            var sessionOpt = sessionRepository.findByImei(imei.value());

            if (sessionOpt.isPresent()) {
                var session = sessionOpt.get();

                // Close channel if available
                if (session.getChannel() != null && session.getChannel().isActive()) {
                    session.getChannel().close();
                }

                // Remove session
                sessionRepository.deleteById(session.getId());

                log.info("📵 Disconnected device: {} (session: {})", 
                        imei.masked(), session.getId());
                return true;
            } else {
                log.warn("⚠️ No session found to disconnect: {}", imei.masked());
                return false;
            }

        } catch (Exception e) {
            log.error("❌ Error disconnecting device {}: {}", imei.masked(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Scheduled cleanup of idle sessions
     */
    @Scheduled(fixedDelay = 300000) // 5 minutes
    @CacheEvict(value = {"device-sessions", "session-stats", "session-by-imei"}, allEntries = true)
    public void cleanupIdleSessions() {
        CompletableFuture.runAsync(() -> {
            long runNumber = cleanupRunCount.incrementAndGet();

            try {
                var startTime = Instant.now();
                var cutoffTime = startTime.minusSeconds(sessionIdleTimeout);
                var idleSessions = sessionRepository.findByLastActivityAtBefore(cutoffTime);
                var cleanedUp = 0;

                for (var session : idleSessions) {
                    try {
                        // Close channel if active
                        if (session.getChannel() != null && session.getChannel().isActive()) {
                            session.getChannel().close();
                        }

                        sessionRepository.deleteById(session.getId());
                        cleanedUp++;
                    } catch (Exception e) {
                        log.error("❌ Error cleaning session {}: {}", 
                                session.getId(), e.getMessage(), e);
                    }
                }

                var duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();

                if (cleanedUp > 0) {
                    log.info("🧹 Cleanup #{}: Removed {} idle sessions in {}ms", 
                            runNumber, cleanedUp, duration);
                } else {
                    log.debug("🧹 Cleanup #{}: No idle sessions found ({}ms)", runNumber, duration);
                }

            } catch (Exception e) {
                log.error("❌ Error during session cleanup #{}: {}", runNumber, e.getMessage(), e);
            }
        });
    }

    /**
     * Create new session (private helper)
     */
    private DeviceSession createNewSession(IMEI imei, Channel channel) {
        var currentSessionCount = sessionRepository.count();

        if (currentSessionCount >= maxSessions) {
            throw new RuntimeException("Maximum session limit reached: " + maxSessions);
        }

        try {
            var session = DeviceSession.create(imei, channel)
                    .setAuthenticated(true);

            var saved = sessionRepository.save(session);
            sessionCreatedCount.incrementAndGet();

            log.info("✅ Created session {} for device: {}", saved.getId(), imei.masked());
            return saved;

        } catch (Exception e) {
            log.error("❌ Error creating new session for {}: {}", imei.masked(), e.getMessage(), e);
            throw new RuntimeException("Failed to create new session", e);
        }
    }

    /**
     * Session statistics DTO
     */
    @lombok.Value
    @lombok.Builder
    public static class SessionStats {
        long totalSessions;
        long authenticatedSessions;
        long unauthenticatedSessions;
        long activeChannels;
        long sessionsWithLocation;
        long createdSessionsCount;
        long cleanupRunsCount;
        Instant lastUpdateTime;

        public static SessionStats empty() {
            return SessionStats.builder()
                    .lastUpdateTime(Instant.now())
                    .build();
        }
    }
}
