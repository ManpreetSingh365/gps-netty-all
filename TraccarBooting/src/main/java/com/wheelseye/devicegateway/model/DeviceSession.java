package com.wheelseye.devicegateway.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GT06 Device Session – immutable core, thread-safe state, Redis-persisted.
 */
@RedisHash("device-sessions")
public final class DeviceSession {

    @Id
    private final String id;
    private final IMEI imei;
    private final Instant createdAt;

    // Mutable, thread-safe fields
    private volatile String channelId;
    private volatile String remoteAddress;
    private volatile String protocolVersion = "GT06_1.8.1";
    private final AtomicReference<DeviceVariant> deviceVariant =
        new AtomicReference<>(DeviceVariant.GT06_STANDARD);
    private final AtomicReference<DeviceStatus> status =
        new AtomicReference<>(DeviceStatus.OFFLINE);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private volatile Instant lastActivityAt;
    private volatile Instant lastLoginAt;

    // Arbitrary attributes
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    // === Constructors ===

    private DeviceSession(IMEI imei) {
        this.id = UUID.randomUUID().toString();
        this.imei = IMEI.of(imei.value());
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.lastLoginAt = this.createdAt;
    }

    @JsonCreator
    public DeviceSession(
        @JsonProperty("id") String id,
        @JsonProperty("imei") IMEI imei,
        @JsonProperty("channelId") String channelId,
        @JsonProperty("remoteAddress") String remoteAddress,
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("deviceVariant") DeviceVariant variant,
        @JsonProperty("status") DeviceStatus status,
        @JsonProperty("authenticated") boolean authenticated,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("lastActivityAt") Instant lastActivityAt,
        @JsonProperty("lastLoginAt") Instant lastLoginAt,
        @JsonProperty("attributes") Map<String, Object> attributes
    ) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.imei = IMEI.of(imei.value());
        this.channelId = channelId;
        this.remoteAddress = remoteAddress;
        this.protocolVersion = protocolVersion != null ? protocolVersion : this.protocolVersion;
        this.deviceVariant.set(variant != null ? variant : DeviceVariant.GT06_STANDARD);
        this.status.set(status != null ? status : DeviceStatus.OFFLINE);
        this.authenticated.set(authenticated);
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.lastActivityAt = lastActivityAt != null ? lastActivityAt : this.createdAt;
        this.lastLoginAt = lastLoginAt != null ? lastLoginAt : this.createdAt;
        if (attributes != null) this.attributes.putAll(attributes);
    }

    // Factory
    public static DeviceSession create(IMEI imei, String channelId, String remoteAddress) {
        var session = new DeviceSession(imei);
        session.channelId = channelId;
        session.remoteAddress = remoteAddress;
        return session;
    }

    // === Behavior ===

    public void authenticate() {
        if (authenticated.compareAndSet(false, true)) {
            lastLoginAt = Instant.now();
            status.set(DeviceStatus.ONLINE);
            touch();
        }
    }

    public void touch() {
        lastActivityAt = Instant.now();
    }

    public void disconnect() {
        authenticated.set(false);
        status.set(DeviceStatus.OFFLINE);
        channelId = null;
        touch();
    }

    public void setVariant(DeviceVariant variant) {
        deviceVariant.set(variant);
        touch();
    }

    public void setStatus(DeviceStatus newStatus) {
        status.set(newStatus);
        touch();
    }

    public void setChannel(String channelId, String remoteAddress) {
        this.channelId = channelId;
        this.remoteAddress = remoteAddress;
        touch();
    }

    public void setAttribute(String key, Object value) {
        if (value != null) attributes.put(key, value);
        else attributes.remove(key);
        touch();
    }

    public <T> T getAttribute(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }

    // === Queries ===

    public boolean isAuthenticated()            { return authenticated.get(); }
    public boolean isOnline()                   { return status.get() == DeviceStatus.ONLINE; }
    public boolean isIdle(long seconds)         { return Instant.now().isAfter(lastActivityAt.plusSeconds(seconds)); }
    public boolean isGt06()                     { return deviceVariant.get().isGt06Compatible(); }
    public long getSessionDurationSeconds()     { return Instant.now().getEpochSecond() - createdAt.getEpochSecond(); }
    public long getIdleTimeSeconds()            { return Instant.now().getEpochSecond() - lastActivityAt.getEpochSecond(); }

    // === Getters ===

    public String getId()           { return id; }
    public IMEI getImei()           { return imei; }
    public String getChannelId()    { return channelId; }
    public String getRemoteAddress(){ return remoteAddress; }
    public String getProtocolVersion() { return protocolVersion; }
    public DeviceVariant getVariant(){ return deviceVariant.get(); }
    public DeviceStatus getStatus()  { return status.get(); }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getLastActivityAt(){ return lastActivityAt; }
    public Instant getLastLoginAt()  { return lastLoginAt; }
    public Map<String,Object> getAttributes() { return Map.copyOf(attributes); }

    // === Enums ===

    public enum DeviceVariant {
        GT06_STANDARD(true),
        GT06_MINI(true),
        GT06_V1_8_1(true),
        UNKNOWN(false);

        private final boolean gt06Compatible;
        DeviceVariant(boolean ok) { this.gt06Compatible = ok; }
        public boolean isGt06Compatible() { return gt06Compatible; }
    }

    public enum DeviceStatus {
        OFFLINE, CONNECTING, ONLINE, IDLE, ERROR
    }
}
