package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Device Message Model - Production Implementation
 * 
 * Immutable data class representing a parsed GPS tracking device message.
 * Supports JSON serialization and validation following Spring Boot 3.5.5 standards.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DeviceMessage {
    
    @JsonProperty("imei")
    @NotBlank(message = "IMEI cannot be blank")
    private final String imei;
    
    @JsonProperty("protocol")
    @NotBlank(message = "Protocol cannot be blank")
    private final String protocol;
    
    @JsonProperty("type")
    @NotBlank(message = "Message type cannot be blank")
    private final String type;
    
    @JsonProperty("timestamp")
    @NotNull(message = "Timestamp cannot be null")
    private final Instant timestamp;
    
    @JsonProperty("data")
    private final Map<String, Object> data;
    
    @JsonProperty("rawData")
    private final String rawData;

    // Constructors
    public DeviceMessage(String imei, String protocol, String type, Instant timestamp, Map<String, Object> data) {
        this(imei, protocol, type, timestamp, data, null);
    }
    
    public DeviceMessage(String imei, String protocol, String type, Instant timestamp, 
                        Map<String, Object> data, String rawData) {
        this.imei = Objects.requireNonNull(imei, "IMEI cannot be null");
        this.protocol = Objects.requireNonNull(protocol, "Protocol cannot be null");
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "Timestamp cannot be null");
        this.data = data != null ? Map.copyOf(data) : Map.of(); // Immutable copy
        this.rawData = rawData;
    }

    // Getters
    public String getImei() { 
        return imei; 
    }
    
    public String getProtocol() { 
        return protocol; 
    }
    
    public String getType() { 
        return type; 
    }
    
    public Instant getTimestamp() { 
        return timestamp; 
    }
    
    public Map<String, Object> getData() { 
        return data; 
    }
    
    public String getRawData() { 
        return rawData; 
    }

    // Validation and utility methods
    
    /**
     * Check if this message represents a valid device message
     */
    public boolean isValid() {
        return imei != null && !imei.isBlank() && 
               !"UNKNOWN_IMEI".equals(imei) &&
               protocol != null && !protocol.isBlank() &&
               type != null && !type.isBlank() &&
               timestamp != null;
    }
    
    /**
     * Check if this message contains GPS location data
     */
    public boolean hasGpsData() {
        return data != null && 
               data.containsKey("latitude") && 
               data.containsKey("longitude") &&
               data.get("latitude") instanceof Number &&
               data.get("longitude") instanceof Number;
    }
    
    /**
     * Check if this is a login message
     */
    public boolean isLoginMessage() {
        return "login".equalsIgnoreCase(type);
    }
    
    /**
     * Check if this is an alarm message
     */
    public boolean isAlarmMessage() {
        return "alarm".equalsIgnoreCase(type);
    }

    // GPS data accessors
    
    public Double getLatitude() {
        return getDataValue("latitude", Double.class);
    }
    
    public Double getLongitude() {
        return getDataValue("longitude", Double.class);
    }
    
    public Integer getSpeed() {
        return getDataValue("speed", Integer.class);
    }
    
    public Integer getCourse() {
        return getDataValue("course", Integer.class);
    }
    
    public Integer getSatelliteCount() {
        return getDataValue("satelliteCount", Integer.class);
    }
    
    public Boolean isGpsPositioned() {
        return getDataValue("gpsPositioned", Boolean.class);
    }

    // Status data accessors
    
    public Integer getVoltageLevel() {
        return getDataValue("voltageLevel", Integer.class);
    }
    
    public String getBatteryStatus() {
        return getDataValue("batteryStatus", String.class);
    }
    
    public Integer getSignalStrength() {
        return getDataValue("gsmSignalStrength", Integer.class);
    }
    
    public String getSignalStatus() {
        return getDataValue("signalStatus", String.class);
    }
    
    public Boolean isCharging() {
        return getDataValue("charging", Boolean.class);
    }
    
    public String getAlarmType() {
        return getDataValue("alarmType", String.class);
    }
    
    public Integer getSerialNumber() {
        return getDataValue("serialNumber", Integer.class);
    }

    // Helper method for type-safe data access
    private <T> T getDataValue(String key, Class<T> type) {
        if (data != null && data.containsKey(key)) {
            Object value = data.get(key);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
        }
        return null;
    }

    // Builder pattern for complex message creation
    public static Builder builder() {
        return new Builder();
    }
    
    public static final class Builder {
        private String imei;
        private String protocol = "GT06";
        private String type;
        private Instant timestamp = Instant.now();
        private Map<String, Object> data;
        private String rawData;
        
        private Builder() {}
        
        public Builder imei(String imei) {
            this.imei = imei;
            return this;
        }
        
        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }
        
        public Builder type(String type) {
            this.type = type;
            return this;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }
        
        public Builder rawData(String rawData) {
            this.rawData = rawData;
            return this;
        }
        
        public DeviceMessage build() {
            return new DeviceMessage(imei, protocol, type, timestamp, data, rawData);
        }
    }

    // Object methods
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceMessage that)) return false;
        return Objects.equals(imei, that.imei) &&
               Objects.equals(protocol, that.protocol) &&
               Objects.equals(type, that.type) &&
               Objects.equals(timestamp, that.timestamp) &&
               Objects.equals(data, that.data) &&
               Objects.equals(rawData, that.rawData);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(imei, protocol, type, timestamp, data, rawData);
    }
    
    @Override
    public String toString() {
        return String.format("DeviceMessage{imei='%s', protocol='%s', type='%s', timestamp=%s, dataFields=%d, hasRawData=%s}",
            imei, protocol, type, timestamp, 
            data != null ? data.size() : 0, 
            rawData != null && !rawData.isBlank());
    }
}