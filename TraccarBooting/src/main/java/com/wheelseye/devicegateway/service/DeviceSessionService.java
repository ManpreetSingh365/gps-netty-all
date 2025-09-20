package com.wheelseye.devicegateway.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.repository.RedisSessionRepository;

import io.netty.channel.Channel;

@Service
public class DeviceSessionService {

    private static final Logger logger = LoggerFactory.getLogger(DeviceSessionService.class);

    private final RedisSessionRepository sessionRepository;

    @Value("${device-gateway.session.idle-timeout:600}")
    private int sessionIdleTimeoutSeconds;

    @Value("${device-gateway.session.cleanup-interval:60}")
    private int sessionCleanupIntervalSeconds;

    public DeviceSessionService(RedisSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public DeviceSession createSession(IMEI imei, Channel channel) {
        try {
            Optional<DeviceSession> existingSession = sessionRepository.findByImei(imei);

            if (existingSession.isPresent()) {
                DeviceSession existing = existingSession.get();
                logger.info("📱 Reusing existing session for IMEI: {} -> Session: {} (authenticated: {})",
                        imei.value(), existing.getId(), existing.isAuthenticated());
                updateSessionChannel(existing, channel);
                return existing;
            }

            // DeviceSession session = new DeviceSession(UUID.randomUUID().toString(),
            // imei);
            DeviceSession session = DeviceSession.create(IMEI.of(imei.value()),
                    channel.id().asShortText(),
                    channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown");

            if (channel != null) {
                var newChannelId = channel.id().asShortText();
                var newRemoteAddress = channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";

                session.setChannel(newChannelId, newRemoteAddress);

            }

            sessionRepository.save(session);

            logger.info("✨ Created new session - IMEI: {} -> Session: {} -> Channel: {} (authenticated: {})",
                    imei.value(), session.getId(), session.getChannelId(), session.isAuthenticated());

            return session;
        } catch (Exception e) {
            logger.error("💥 Failed to create session for IMEI: {}", imei.value(), e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    public void saveSession(DeviceSession session) {
        try {
            if (session == null) {
                logger.warn("⚠️ Attempt to save null session");
                return;
            }
            sessionRepository.save(session);
            logger.debug("💾 Session saved: {} (IMEI: {}, authenticated: {}, channel: {})",
                    session.getId(),
                    session.getImei() != null ? session.getImei().value() : "unknown",
                    session.isAuthenticated(),
                    session.getChannelId());
        } catch (Exception e) {
            logger.error("💥 Failed to save session {}: {}", session.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to save session", e);
        }
    }

    public Optional<DeviceSession> getSession(Channel channel) {
        if (channel == null) {
            logger.warn("⚠️ Attempt to get session with null channel");
            return Optional.empty();
        }

        try {
            String channelId = channel.id().asShortText();
            String remoteAddress = channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown";

            Optional<DeviceSession> sessionOpt = sessionRepository.findByChannel(channel);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                String imei = session.getImei() != null ? session.getImei().value() : "unknown";

                logger.debug("✅ Found session for channel {} ({}): {} (IMEI: {}, authenticated: {})",
                        channelId, remoteAddress, session.getId(), imei, session.isAuthenticated());

                return sessionOpt;
            } else {
                logger.debug("📭 No session found for channel: {} ({})", channelId, remoteAddress);
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.error("💥 Error getting session for channel {}: {}", channel.id().asShortText(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    public Optional<DeviceSession> getSession(IMEI imei) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                logger.debug("✅ Found session for IMEI {}: {} (authenticated: {}, channel: {})",
                        imei.value(), session.getId(), session.isAuthenticated(), session.getChannelId());
                return sessionOpt;
            } else {
                logger.debug("📭 No session found for IMEI: {}", imei.value());
                return Optional.empty();
            }
        } catch (Exception e) {
            logger.error("💥 Error getting session for IMEI {}: {}", imei.value(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    public void removeSession(Channel channel) {
        if (channel == null) {
            logger.warn("⚠️ Attempt to remove session with null channel");
            return;
        }

        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findByChannel(channel);

            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                sessionRepository.delete(session.getId());
                String imei = session.getImei() != null ? session.getImei().value() : "unknown";

                logger.info("🗑️ Removed session for channel {}: {} (IMEI: {}, was authenticated: {})",
                        channel.id().asShortText(), session.getId(), imei, session.isAuthenticated());
            } else {
                logger.debug("📭 No session found to remove for channel: {}", channel.id().asShortText());
            }
        } catch (Exception e) {
            logger.error("💥 Error removing session for channel {}: {}", channel.id().asShortText(), e.getMessage(), e);
        }
    }

    public void removeSession(String sessionId) {
        try {
            Optional<DeviceSession> sessionOpt = sessionRepository.findById(sessionId);
            if (sessionOpt.isPresent()) {
                DeviceSession session = sessionOpt.get();
                sessionRepository.delete(sessionId);
                String imei = session.getImei() != null ? session.getImei().value() : "unknown";

                logger.info("🗑️ Removed session: {} (IMEI: {}, was authenticated: {})",
                        sessionId, imei, session.isAuthenticated());
            }
        } catch (Exception e) {
            logger.error("💥 Error removing session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    @Scheduled(fixedRateString = "${device-gateway.session.cleanup-interval:60000}")
    public void cleanupIdleSessions() {
        try {
            List<DeviceSession> idleSessions = sessionRepository.findIdleSessions(sessionIdleTimeoutSeconds);
            int cleanedUp = 0;
            for (var session : idleSessions) {
                try {
                    sessionRepository.delete(session.getId());
                    cleanedUp++;
                    String imei = session.getImei() != null ? session.getImei().value() : "unknown";
                    logger.debug("🧹 Cleaned up idle session: {} (IMEI: {}, was authenticated: {}, idle: {}s)",
                            session.getId(), imei, session.isAuthenticated(), session.getIdleTimeSeconds());
                } catch (Exception e) {
                    logger.error("💥 Error cleaning up session {}: {}", session.getId(), e.getMessage());
                }
            }
            if (cleanedUp > 0) {
                logger.info("🧹 Cleaned up {} idle sessions", cleanedUp);
            }
        } catch (Exception e) {
            logger.error("💥 Error during session cleanup: {}", e.getMessage(), e);
        }
    }

    private void updateSessionChannel(DeviceSession session, Channel channel) {
        try {
            if (channel != null) {
                String newChannelId = channel.id().asShortText();
                String newRemoteAddress = channel.remoteAddress() != null ? channel.remoteAddress().toString()
                        : "unknown";

                session.setChannel(newChannelId, newRemoteAddress);
                session.touch();

                sessionRepository.save(session);
                logger.debug("🔄 Updated channel info for session {}: {} -> {} (IMEI: {}, authenticated: {})",
                        session.getId(), newChannelId, newRemoteAddress,
                        session.getImei() != null ? session.getImei().value() : "unknown",
                        session.isAuthenticated());
            }
        } catch (Exception e) {
            logger.error("💥 Error updating channel for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }
    

    public List<DeviceSession> getAllSessions() {
        try {
            List<DeviceSession> sessions = sessionRepository.findAll();
            long authenticatedCount = sessions.stream().filter(DeviceSession::isAuthenticated).count();
            logger.debug("📊 Retrieved {} active sessions ({} authenticated, {} unauthenticated)",
                    sessions.size(), authenticatedCount, sessions.size() - authenticatedCount);
            return sessions;
        } catch (Exception e) {
            logger.error("💥 Error getting all sessions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    public record SessionStats(int totalSessions, int authenticatedSessions, int unauthenticatedSessions) {
    }

    public SessionStats getSessionStats() {
        try {
            List<DeviceSession> sessions = getAllSessions();
            long authenticatedCount = sessions.stream().filter(DeviceSession::isAuthenticated).count();
            return new SessionStats(sessions.size(), (int) authenticatedCount,
                    sessions.size() - (int) authenticatedCount);
        } catch (Exception e) {
            logger.error("💥 Error getting session stats: {}", e.getMessage(), e);
            return new SessionStats(0, 0, 0);
        }
    }
}
