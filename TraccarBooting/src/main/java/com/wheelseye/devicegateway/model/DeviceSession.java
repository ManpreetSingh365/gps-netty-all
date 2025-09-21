package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeviceSession - Represents an active device connection session
 * 
 * Enhanced for Java 21 and Spring Boot 3.5.5 with:
 * - Modern immutability patterns
 * - Comprehensive session management
 * - Thread-safe operations
 * - Proper JSON serialization support
 * - Builder pattern for flexible construction
 * 
 * @since Spring Boot 3.5.5
 */
public class DeviceSession {

    public static final AttributeKey<DeviceSession> DEVICE_SESSION_KEY = AttributeKey.valueOf("deviceSession");

    // Session identification
    private final String id;
    private final IMEI imei;
    private final String channelId;
    private final String remoteAddress;

    // Connection details
    @JsonIgnore // Don't serialize Netty Channel
    private volatile Channel channel;
    private volatile String protocolVersion;
    private volatile String deviceVariant;
    private volatile DeviceStatus status;

    // Session state
    private volatile boolean authenticated;
    private final Instant createdAt;
    private volatile Instant lastActivityAt;
    private volatile Instant lastLoginAt;

    // Device location and status
    private volatile Double lastLatitude;
    private volatile Double lastLongitude;
    private volatile Instant lastPositionTime;
    private volatile String deviceModel;
    private volatile String firmwareVersion;

    // Additional attributes (thread-safe)
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * Constructor for basic session creation (compatible with existing code)
     */
    public DeviceSession(String imei, Channel channel) {
        this(IMEI.of(imei, false), channel);
    }

    /**
     * Constructor with IMEI object
     */
    public DeviceSession(IMEI imei, Channel channel) {
        this.id = UUID.randomUUID().toString();
        this.imei = Objects.requireNonNull(imei, "IMEI cannot be null");
        this.channel = Objects.requireNonNull(channel, "Channel cannot be null");
        this.channelId = channel.id().asShortText();
        this.remoteAddress = extractRemoteAddress(channel);

        final Instant now = Instant.now();
        this.createdAt = now;
        this.lastActivityAt = now;
        this.lastLoginAt = now;
        this.authenticated = false;
        this.status = DeviceStatus.CONNECTED;
    }

    /**
     * Full constructor for Redis deserialization
     */
    public DeviceSession(
            String id,
            IMEI imei,
            String channelId,
            String remoteAddress,
            Channel channel,
            String protocolVersion,
            String deviceVariant,
            DeviceStatus status,
            boolean authenticated,
            Instant createdAt,
            Instant lastActivityAt,
            Instant lastLoginAt,
            Map<String, Object> attributes) {

        this.id = Objects.requireNonNullElse(id, UUID.randomUUID().toString());
        this.imei = Objects.requireNonNull(imei, "IMEI cannot be null");
        this.channelId = Objects.requireNonNullElse(channelId, "unknown");
        this.remoteAddress = Objects.requireNonNullElse(remoteAddress, "unknown");
        this.channel = channel;
        this.protocolVersion = protocolVersion;
        this.deviceVariant = deviceVariant;
        this.status = Objects.requireNonNullElse(status, DeviceStatus.CONNECTED);
        this.authenticated = authenticated;
        this.createdAt = Objects.requireNonNullElse(createdAt, Instant.now());
        this.lastActivityAt = Objects.requireNonNullElse(lastActivityAt, this.createdAt);
        this.lastLoginAt = Objects.requireNonNullElse(lastLoginAt, this.createdAt);

        if (attributes != null) {
            this.attributes.putAll(attributes);
        }
    }

    /**
     * Factory method for creating sessions (used by DeviceSessionService)
     */
    public static DeviceSession create(IMEI imei, String channelId, String remoteAddress) {
        final String sessionId = UUID.randomUUID().toString();
        final Instant now = Instant.now();

        return new DeviceSession(
                sessionId,
                imei,
                channelId,
                remoteAddress,
                null, // channel will be set later
                null, // protocolVersion
                null, // deviceVariant
                DeviceStatus.CONNECTED,
                false, // authenticated
                now, // createdAt
                now, // lastActivityAt
                now, // lastLoginAt
                new ConcurrentHashMap<>());
    }

    // Getters
    public String getId() {
        return id;
    }

    public IMEI getImei() {
        return imei;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public String getDeviceVariant() {
        return deviceVariant;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Double getLastLatitude() {
        return lastLatitude;
    }

    public Double getLastLongitude() {
        return lastLongitude;
    }

    public Instant getLastPositionTime() {
        return lastPositionTime;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public Map<String, Object> getAttributes() {
        return Map.copyOf(attributes);
    }

    // Setters with activity tracking
    public void setChannel(Channel channel) {
        this.channel = channel;
        touch();
    }

    public void setChannel(String channelId, String remoteAddress) {
        // For cases where we only have string information
        touch();
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
        touch();
    }

    public void setDeviceVariant(String deviceVariant) {
        this.deviceVariant = deviceVariant;
        touch();
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
        touch();
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        if (authenticated) {
            this.lastLoginAt = Instant.now();
        }
        touch();
    }

    public void setLastLatitude(Double lastLatitude) {
        this.lastLatitude = lastLatitude;
        touch();
    }

    public void setLastLongitude(Double lastLongitude) {
        this.lastLongitude = lastLongitude;
        touch();
    }

    public void setLastPositionTime(Instant lastPositionTime) {
        this.lastPositionTime = lastPositionTime;
        touch();
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
        touch();
    }

    public void setFirmwareVersion(String firmwareVersion) {
        this.firmwareVersion = firmwareVersion;
        touch();
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        touch();
    }

    public void setLastMessage(Instant lastMessage) {
        touch();
    }

    // Compatibility methods for existing code
    public Instant getLoginTime() {
        return createdAt;
    }

    public Instant getLastHeartbeat() {
        return lastActivityAt;
    }

    public Instant getLastMessage() {
        return lastActivityAt;
    }

    /**
     * Update device position with timestamp
     */
    public void updatePosition(double latitude, double longitude, Instant timestamp) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastPositionTime = timestamp;
        touch();
    }

    /**
     * Update activity timestamp
     */
    public void touch() {
        this.lastActivityAt = Instant.now();
    }

    /**
     * Check if session is active based on timeout
     */
    public boolean isActive(Duration timeout) {
        return Duration.between(lastActivityAt, Instant.now()).compareTo(timeout) < 0;
    }

    public boolean isActive(long timeoutMillis) {
        return isActive(Duration.ofMillis(timeoutMillis));
    }

    /**
     * Get or create attribute
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }

    /**
     * Set attribute
     */
    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
        touch();
    }

    /**
     * Remove attribute
     */
    public void removeAttribute(String key) {
        attributes.remove(key);
        touch();
    }

    private String extractRemoteAddress(Channel channel) {
        return channel != null && channel.remoteAddress() != null
                ? channel.remoteAddress().toString()
                : "unknown";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DeviceSession other && Objects.equals(this.id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
                "DeviceSession{id='%s', imei=%s, channel='%s', remoteAddress='%s', authenticated=%s, status=%s}",
                id, imei.masked(), channelId, remoteAddress, authenticated, status);
    }

    // Add this to DeviceSession.java
    public static class Builder {
        private String id;
        private IMEI imei;
        private String channelId;
        private String remoteAddress;
        private Channel channel;
        private boolean authenticated;
        private Instant createdAt;
        private Instant lastActivityAt;
        private Double lastLatitude;
        private Double lastLongitude;
        private Map<String, Object> attributes = new ConcurrentHashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder imei(IMEI imei) {
            this.imei = imei;
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        public Builder channel(Channel channel) {
            this.channel = channel;
            return this;
        }

        public Builder authenticated(boolean authenticated) {
            this.authenticated = authenticated;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder lastActivityAt(Instant lastActivityAt) {
            this.lastActivityAt = lastActivityAt;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null)
                this.attributes.putAll(attributes);
            return this;
        }

        public DeviceSession build() {
            return new DeviceSession(
                    Objects.requireNonNullElse(id, UUID.randomUUID().toString()),
                    imei,
                    Objects.requireNonNullElse(channelId, "unknown"),
                    Objects.requireNonNullElse(remoteAddress, "unknown"),
                    channel,
                    null, null,
                    DeviceStatus.CONNECTED,
                    authenticated,
                    Objects.requireNonNullElse(createdAt, Instant.now()),
                    Objects.requireNonNullElse(lastActivityAt, Instant.now()),
                    Objects.requireNonNullElse(lastActivityAt, Instant.now()),
                    attributes);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Device status enumeration
     */
    public enum DeviceStatus {
        CONNECTED,
        AUTHENTICATED,
        ACTIVE,
        IDLE,
        DISCONNECTED,
        ERROR
    }
}