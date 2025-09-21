package com.wheelseye.devicegateway.model;

import io.netty.channel.Channel;
import lombok.*;
import lombok.experimental.Accessors;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DeviceSession Model - Redis-Only Implementation
 * 
 * Simple POJO for Redis storage without database persistence.
 * Contains all required methods for DeviceSessionService compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Accessors(chain = true)
public class DeviceSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String imei;
    private String channelId;
    private String remoteAddress;

    @Builder.Default
    private boolean authenticated = false;

    private Double lastLatitude;
    private Double lastLongitude;
    private Instant lastPositionTime;

    @Builder.Default
    private Instant lastActivityAt = Instant.now();

    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    // Transient field for Netty channel (not serialized to Redis)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient Channel channel;

    // Factory methods
    public static DeviceSession create(IMEI imei, Channel channel) {
        Objects.requireNonNull(imei, "IMEI cannot be null");
        Objects.requireNonNull(channel, "Channel cannot be null");

        return DeviceSession.builder()
                .id(generateSessionId())
                .imei(imei.value())
                .channelId(channel.id().asShortText())
                .remoteAddress(extractRemoteAddress(channel))
                .channel(channel)
                .authenticated(false)
                .lastActivityAt(Instant.now())
                .createdAt(Instant.now())
                .status(SessionStatus.ACTIVE)
                .build();
    }

    public static DeviceSession create(IMEI imei, String channelId, String remoteAddress) {
        Objects.requireNonNull(imei, "IMEI cannot be null");
        Objects.requireNonNull(channelId, "Channel ID cannot be null");
        Objects.requireNonNull(remoteAddress, "Remote address cannot be null");

        return DeviceSession.builder()
                .id(generateSessionId())
                .imei(imei.value())
                .channelId(channelId)
                .remoteAddress(remoteAddress)
                .authenticated(false)
                .lastActivityAt(Instant.now())
                .createdAt(Instant.now())
                .status(SessionStatus.ACTIVE)
                .build();
    }

    // Required service methods

    /**
     * Update last activity timestamp
     */
    public DeviceSession touch() {
        this.lastActivityAt = Instant.now();
        return this;
    }

    /**
     * Set the transient channel reference
     */
    public DeviceSession setChannel(Channel channel) {
        this.channel = channel;
        if (channel != null) {
            this.channelId = channel.id().asShortText();
            this.remoteAddress = extractRemoteAddress(channel);
        }
        return this;
    }

    /**
     * Update GPS coordinates
     */
    public DeviceSession setLastLatitude(double latitude) {
        this.lastLatitude = latitude;
        return this;
    }

    public DeviceSession setLastLongitude(double longitude) {
        this.lastLongitude = longitude;
        return this;
    }

    public DeviceSession setLastPositionTime(Instant timestamp) {
        this.lastPositionTime = timestamp;
        return this;
    }

    /**
     * Update position with all coordinates at once
     */
    public DeviceSession updatePosition(double latitude, double longitude, Instant timestamp) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastPositionTime = timestamp;
        touch();
        return this;
    }

    /**
     * Set authentication status
     */
    public DeviceSession setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        return this;
    }

    /**
     * Check if session is authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Check if channel is active
     */
    public boolean isChannelActive() {
        return channel != null && channel.isActive();
    }

    /**
     * Check if session is idle
     */
    public boolean isIdle(long maxIdleSeconds) {
        if (lastActivityAt == null) return true;
        return Instant.now().getEpochSecond() - lastActivityAt.getEpochSecond() > maxIdleSeconds;
    }

    /**
     * Get session duration in seconds
     */
    public long getSessionDurationSeconds() {
        if (createdAt == null) return 0;
        return Instant.now().getEpochSecond() - createdAt.getEpochSecond();
    }

    /**
     * Check if session has valid location data
     */
    public boolean hasValidLocation() {
        return lastLatitude != null && lastLongitude != null &&
               lastLatitude >= -90.0 && lastLatitude <= 90.0 &&
               lastLongitude >= -180.0 && lastLongitude <= 180.0;
    }

    /**
     * Get IMEI as IMEI object
     */
    public IMEI getImeiObject() {
        return IMEI.of(imei, false);
    }

    // Helper methods
    private static String generateSessionId() {
        return "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String extractRemoteAddress(Channel channel) {
        if (channel == null || channel.remoteAddress() == null) {
            return "UNKNOWN";
        }
        return channel.remoteAddress().toString();
    }

    // Session status enum
    public enum SessionStatus {
        ACTIVE, INACTIVE, DISCONNECTED, EXPIRED, ERROR
    }

    @Override
    public String toString() {
        return String.format("DeviceSession{id='%s', imei='%s', authenticated=%s, active=%s, duration=%ds}",
                id, 
                imei != null ? "*".repeat(11) + imei.substring(Math.max(0, imei.length() - 4)) : "null",
                authenticated, 
                isChannelActive(), 
                getSessionDurationSeconds());
    }
}
