package com.wheelseye.devicegateway.model;

import io.netty.channel.Channel;
import lombok.*;
import lombok.experimental.Accessors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DeviceSession Model - Production-Ready Redis Implementation
 * 
 * Enhanced POJO for Redis storage with proper serialization annotations
 * and comprehensive validation. Includes all methods for service compatibility.
 * 
 * @author WheelsEye Development Team
 * @since 1.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Accessors(chain = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonTypeName("DeviceSession")
public class DeviceSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Unique session identifier
     */
    @NotNull
    @Pattern(regexp = "^session_[a-f0-9]{16}$", message = "Invalid session ID format")
    private String id;

    /**
     * Device IMEI (15 digits)
     */
    @NotNull
    @Pattern(regexp = "^\\d{15}$", message = "IMEI must be exactly 15 digits")
    private String imei;

    /**
     * Netty channel identifier
     */
    @NotNull
    private String channelId;

    /**
     * Remote IP address and port
     */
    @NotNull
    private String remoteAddress;

    /**
     * Authentication status
     */
    @Builder.Default
    private boolean authenticated = false;

    /**
     * Last known GPS coordinates
     */
    private Double lastLatitude;
    private Double lastLongitude;
    private Instant lastPositionTime;

    /**
     * Session activity tracking
     */
    @Builder.Default
    @JsonSerialize
    @JsonDeserialize
    private Instant lastActivityAt = Instant.now();

    /**
     * Session creation timestamp
     */
    @Builder.Default
    @JsonSerialize
    @JsonDeserialize
    private Instant createdAt = Instant.now();

    /**
     * Session status
     */
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * Protocol-specific data (optional)
     */
    private String protocolVersion;
    private String deviceModel;
    private String firmwareVersion;

    /**
     * Transient field for Netty channel (not serialized to Redis)
     * This field is excluded from JSON serialization and Redis storage
     */
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient Channel channel;

    // Factory Methods

    /**
     * Create new device session with IMEI object and channel
     */
    public static DeviceSession create(@NonNull IMEI imei, @NonNull Channel channel) {
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

    /**
     * Create new device session with string parameters (for Redis deserialization)
     */
    public static DeviceSession create(@NonNull IMEI imei, @NonNull String channelId, @NonNull String remoteAddress) {
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

    // Service Methods

    /**
     * Update last activity timestamp
     */
    public DeviceSession touch() {
        this.lastActivityAt = Instant.now();
        return this;
    }

    /**
     * Set the transient channel reference (not persisted to Redis)
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
     * Update GPS coordinates with validation
     */
    public DeviceSession setLastLatitude(double latitude) {
        if (latitude >= -90.0 && latitude <= 90.0) {
            this.lastLatitude = latitude;
        } else {
            throw new IllegalArgumentException("Latitude must be between -90 and 90 degrees");
        }
        return this;
    }

    public DeviceSession setLastLongitude(double longitude) {
        if (longitude >= -180.0 && longitude <= 180.0) {
            this.lastLongitude = longitude;
        } else {
            throw new IllegalArgumentException("Longitude must be between -180 and 180 degrees");
        }
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
        setLastLatitude(latitude);
        setLastLongitude(longitude);
        this.lastPositionTime = timestamp;
        touch();
        return this;
    }

    /**
     * Set authentication status
     */
    public DeviceSession setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        if (authenticated) {
            touch();
        }
        return this;
    }

    /**
     * Authentication status getter
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Check if channel is active and connected
     */
    public boolean isChannelActive() {
        return channel != null && channel.isActive() && channel.isWritable();
    }

    /**
     * Check if session is idle based on last activity
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
     * Check if session has valid GPS location data
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
        return IMEI.of(imei, false); // Skip validation for existing data
    }

    /**
     * Get masked IMEI for logging
     */
    public String getMaskedImei() {
        return getImeiObject().masked();
    }

    /**
     * Update session status
     */
    public DeviceSession setStatus(SessionStatus status) {
        this.status = status;
        touch();
        return this;
    }

    /**
     * Check if session is in active status
     */
    public boolean isActive() {
        return SessionStatus.ACTIVE.equals(status);
    }

    /**
     * Mark session as disconnected
     */
    public DeviceSession markDisconnected() {
        this.status = SessionStatus.DISCONNECTED;
        this.channel = null; // Clear transient channel reference
        touch();
        return this;
    }

    /**
     * Update protocol information
     */
    public DeviceSession updateProtocolInfo(String version, String model, String firmware) {
        this.protocolVersion = version;
        this.deviceModel = model;
        this.firmwareVersion = firmware;
        touch();
        return this;
    }

    // Helper Methods

    private static String generateSessionId() {
        return "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String extractRemoteAddress(Channel channel) {
        if (channel == null || channel.remoteAddress() == null) {
            return "UNKNOWN";
        }
        return channel.remoteAddress().toString();
    }

    // Session Status Enum

    /**
     * Session status enumeration
     */
    public enum SessionStatus {
        /**
         * Session is active and communicating
         */
        ACTIVE,

        /**
         * Session is inactive but may reconnect
         */
        INACTIVE,

        /**
         * Session is disconnected
         */
        DISCONNECTED,

        /**
         * Session has expired due to inactivity
         */
        EXPIRED,

        /**
         * Session encountered an error
         */
        ERROR
    }

    // Object Methods

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        DeviceSession that = (DeviceSession) obj;
        return Objects.equals(id, that.id) && Objects.equals(imei, that.imei);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, imei);
    }

    @Override
    public String toString() {
        return String.format(
            "DeviceSession{id='%s', imei='%s', authenticated=%s, active=%s, duration=%ds, location=%s}",
            id,
            imei != null ? "*".repeat(11) + imei.substring(Math.max(0, imei.length() - 4)) : "null",
            authenticated,
            isChannelActive(),
            getSessionDurationSeconds(),
            hasValidLocation() ? String.format("[%.6f, %.6f]", lastLatitude, lastLongitude) : "unknown"
        );
    }
}