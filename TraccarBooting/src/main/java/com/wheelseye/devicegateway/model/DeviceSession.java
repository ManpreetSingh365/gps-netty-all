package com.wheelseye.devicegateway.model;

import io.netty.channel.Channel;
import lombok.*;
import lombok.experimental.Accessors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Simple DeviceSession Model - CORRECTED for Redis Compatibility
 * 
 * This simplified version eliminates all Redis serialization issues by:
 * - Removing complex type annotations that caused @class requirements
 * - Using simple field mappings that work with standard JSON
 * - Clean serialization without type validation complications
 * 
 * @author WheelsEye Development Team
 * @version 2.2.0 - CORRECTED & SIMPLIFIED
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 3L; // Updated for compatibility

    /**
     * Unique session identifier
     */
    @NotNull
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

    /**
     * Timestamps with simple serialization
     */
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    private Instant lastPositionTime;

    @Builder.Default
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    private Instant lastActivityAt = Instant.now();

    @Builder.Default
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    private Instant createdAt = Instant.now();

    /**
     * Session status as simple string (avoids enum serialization issues)
     */
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * Protocol information (optional)
     */
    private String protocolVersion;
    private String deviceModel;
    private String firmwareVersion;

    /**
     * Device status information
     */
    @Builder.Default
    private Integer signalStrength = 0;

    @Builder.Default
    private Boolean isCharging = false;

    @Builder.Default
    private Integer batteryLevel = 0;

    /**
     * Transient field for Netty channel (not serialized)
     */
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient Channel channel;

    // === FACTORY METHODS ===

    /**
     * Create new device session with IMEI and channel
     */
    public static DeviceSession create(@NonNull String imei, @NonNull Channel channel) {
        Objects.requireNonNull(imei, "IMEI cannot be null");
        Objects.requireNonNull(channel, "Channel cannot be null");

        validateImei(imei);

        return DeviceSession.builder()
                .id(generateSessionId())
                .imei(imei)
                .channelId(channel.id().asShortText())
                .remoteAddress(extractRemoteAddress(channel))
                .channel(channel)
                .authenticated(false)
                .lastActivityAt(Instant.now())
                .createdAt(Instant.now())
                .status("ACTIVE")
                .build();
    }

    /**
     * Create session for Redis deserialization
     */
    public static DeviceSession create(@NonNull String imei, @NonNull String channelId, @NonNull String remoteAddress) {
        Objects.requireNonNull(imei, "IMEI cannot be null");
        Objects.requireNonNull(channelId, "Channel ID cannot be null");
        Objects.requireNonNull(remoteAddress, "Remote address cannot be null");

        validateImei(imei);

        return DeviceSession.builder()
                .id(generateSessionId())
                .imei(imei)
                .channelId(channelId)
                .remoteAddress(remoteAddress)
                .authenticated(false)
                .lastActivityAt(Instant.now())
                .createdAt(Instant.now())
                .status("ACTIVE")
                .build();
    }

    // === BUSINESS METHODS ===

    /**
     * Update last activity timestamp (thread-safe)
     */
    public synchronized DeviceSession touch() {
        this.lastActivityAt = Instant.now();
        return this;
    }

    /**
     * Set transient channel reference
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
     * Update GPS position with validation
     */
    public synchronized DeviceSession updatePosition(double latitude, double longitude, Instant timestamp) {
        validateCoordinates(latitude, longitude);

        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastPositionTime = timestamp != null ? timestamp : Instant.now();
        touch();

        return this;
    }

    /**
     * Update device status information
     */
    public synchronized DeviceSession updateStatus(int signalStrength, boolean isCharging, int batteryLevel) {
        this.signalStrength = Math.max(0, Math.min(100, signalStrength));
        this.isCharging = isCharging;
        this.batteryLevel = Math.max(0, Math.min(100, batteryLevel));
        touch();

        return this;
    }

    /**
     * Set authentication status
     */
    public synchronized DeviceSession setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        if (authenticated) {
            touch();
        }
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

    /**
     * Mark session as disconnected
     */
    public synchronized DeviceSession markDisconnected() {
        this.status = "DISCONNECTED";
        this.channel = null;
        touch();
        return this;
    }

    /**
     * Set session status with string (avoids enum issues)
     */
    public DeviceSession setStatus(String status) {
        this.status = status != null ? status : "ACTIVE";
        touch();
        return this;
    }

    // Alternative method for enum compatibility
    public DeviceSession setStatus(SessionStatus status) {
        this.status = status != null ? status.name() : "ACTIVE";
        touch();
        return this;
    }

    // === STATUS CHECKS ===

    /**
     * Check if channel is active and writable
     */
    public boolean isChannelActive() {
        return channel != null && channel.isActive() && channel.isWritable();
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
     * Check if has valid GPS coordinates
     */
    public boolean hasValidLocation() {
        return lastLatitude != null && lastLongitude != null &&
               lastLatitude >= -90.0 && lastLatitude <= 90.0 &&
               lastLongitude >= -180.0 && lastLongitude <= 180.0;
    }

    /**
     * Check if session is active
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    // === VALIDATION HELPERS ===

    private static void validateImei(String imei) {
        if (imei == null || !imei.matches("\\d{15}")) {
            throw new IllegalArgumentException("IMEI must be exactly 15 digits");
        }
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90 degrees");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180 degrees");
        }
    }

    // === UTILITY METHODS ===

    private static String generateSessionId() {
        return "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String extractRemoteAddress(Channel channel) {
        if (channel == null || channel.remoteAddress() == null) {
            return "UNKNOWN";
        }
        return channel.remoteAddress().toString();
    }

    // === SESSION STATUS ENUM (for compatibility) ===

    public enum SessionStatus {
        ACTIVE("Session is active and communicating"),
        INACTIVE("Session is inactive but may reconnect"),
        DISCONNECTED("Session is disconnected"),
        EXPIRED("Session has expired due to inactivity"),
        ERROR("Session encountered an error");

        private final String description;

        SessionStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // === OBJECT METHODS ===

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
            "DeviceSession{id='%s', imei='%s', authenticated=%s, active=%s, duration=%ds, location=%s, status=%s}",
            id,
            imei != null ? "*".repeat(11) + imei.substring(Math.max(0, imei.length() - 4)) : "null",
            authenticated,
            isChannelActive(),
            getSessionDurationSeconds(),
            hasValidLocation() ? String.format("[%.6f, %.6f]", lastLatitude, lastLongitude) : "unknown",
            status
        );
    }
}