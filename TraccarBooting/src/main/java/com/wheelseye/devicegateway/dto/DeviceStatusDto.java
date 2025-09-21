package com.wheelseye.devicegateway.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Minimal Device Status DTO - Modern and concise
 * 
 * Represents the current operational status of a device
 */
@Schema(description = "Device operational status information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStatusDto(
    
    @Schema(description = "Device IMEI", example = "123456789012345")
    @NotNull
    String imei,
    
    @Schema(description = "Current device status", 
           allowableValues = {"ONLINE", "OFFLINE", "IDLE", "ERROR", "CONNECTING"})
    @NotNull
    String status,
    
    @Schema(description = "Whether device is authenticated")
    boolean authenticated,
    
    @Schema(description = "Last communication timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant lastSeen,
    
    @Schema(description = "Current GPS latitude", example = "40.7589")
    Double latitude,
    
    @Schema(description = "Current GPS longitude", example = "-73.9851")
    Double longitude,
    
    @Schema(description = "Battery level percentage", example = "85")
    Integer batteryLevel,
    
    @Schema(description = "Signal strength (0-100)", example = "75")
    Integer signalStrength,
    
    @Schema(description = "Device temperature in Celsius", example = "45")
    Integer temperature,
    
    @Schema(description = "Whether device is moving")
    Boolean isMoving,
    
    @Schema(description = "Current speed in km/h", example = "60")
    Integer speed,
    
    @Schema(description = "Last error message if any")
    String lastError
    
) {
    
    /**
     * Create basic status DTO
     */
    public static DeviceStatusDto create(String imei, String status, boolean authenticated, Instant lastSeen) {
        return new DeviceStatusDto(
            imei, status, authenticated, lastSeen, 
            null, null, null, null, null, null, null, null
        );
    }
    
    /**
     * Check if device has valid GPS position
     */
    public boolean hasValidPosition() {
        return latitude != null && longitude != null && 
               latitude >= -90.0 && latitude <= 90.0 &&
               longitude >= -180.0 && longitude <= 180.0;
    }
    
    /**
     * Check if device is considered online (seen recently)
     */
    public boolean isOnline() {
        if (lastSeen == null) return false;
        
        // Consider online if seen within last 5 minutes
        long fiveMinutesAgo = Instant.now().minusSeconds(300).toEpochMilli();
        return lastSeen.toEpochMilli() > fiveMinutesAgo;
    }
    
    /**
     * Get battery status description
     */
    public String getBatteryStatus() {
        if (batteryLevel == null) return "UNKNOWN";
        
        return switch (batteryLevel) {
            case Integer level when level > 80 -> "HIGH";
            case Integer level when level > 50 -> "MEDIUM";
            case Integer level when level > 20 -> "LOW";
            case Integer level when level >= 0 -> "CRITICAL";
            default -> "INVALID";
        };
    }
    
    /**
     * Get signal quality description
     */
    public String getSignalQuality() {
        if (signalStrength == null) return "UNKNOWN";
        
        return switch (signalStrength) {
            case Integer strength when strength > 80 -> "EXCELLENT";
            case Integer strength when strength > 60 -> "GOOD";
            case Integer strength when strength > 40 -> "FAIR";
            case Integer strength when strength > 20 -> "POOR";
            case Integer strength when strength >= 0 -> "NO_SIGNAL";
            default -> "INVALID";
        };
    }
    
    /**
     * Check if device has any alerts/issues
     */
    public boolean hasAlerts() {
        return lastError != null ||
               (batteryLevel != null && batteryLevel < 20) ||
               (signalStrength != null && signalStrength < 20) ||
               (temperature != null && (temperature < -10 || temperature > 70));
    }
}