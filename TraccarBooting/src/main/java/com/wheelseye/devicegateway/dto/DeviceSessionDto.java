package com.wheelseye.devicegateway.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;

/**
 * Device Session Data Transfer Object
 * 
 * DTO for exposing device session information via REST APIs with:
 * - JSON serialization optimization
 * - Sensitive data filtering
 * - Clean API response format
 * - Proper timestamp formatting
 * 
 * @author WheelsEye Development Team
 * @version 2.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceSessionDto {

    /**
     * Session identifier
     */
    private String id;

    /**
     * Masked IMEI for privacy (shows last 4 digits only)
     */
    private String imei;

    /**
     * Channel identifier
     */
    private String channelId;

    /**
     * Remote address
     */
    private String remoteAddress;

    /**
     * Authentication status
     */
    private boolean authenticated;

    /**
     * GPS coordinates
     */
    private Double lastLatitude;
    private Double lastLongitude;

    /**
     * Timestamps with proper JSON formatting
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Instant lastPositionTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Instant lastActivityAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private Instant createdAt;

    /**
     * Session status
     */
    private String status;

    /**
     * Protocol information
     */
    private String protocolVersion;
    private String deviceModel;
    private String firmwareVersion;

    /**
     * Device status information
     */
    private Integer signalStrength;
    private Boolean isCharging;
    private Integer batteryLevel;

    /**
     * Computed fields
     */
    private boolean active;
    private boolean channelActive;
    private boolean hasValidLocation;
    private long sessionDurationSeconds;

    /**
     * Location display string
     */
    public String getLocationDisplay() {
        if (lastLatitude != null && lastLongitude != null) {
            return String.format("%.6f°%s, %.6f°%s", 
                    Math.abs(lastLatitude), lastLatitude >= 0 ? "N" : "S",
                    Math.abs(lastLongitude), lastLongitude >= 0 ? "E" : "W");
        }
        return null;
    }

    /**
     * Google Maps link
     */
    public String getMapLink() {
        if (lastLatitude != null && lastLongitude != null) {
            return String.format("https://www.google.com/maps?q=%.6f,%.6f", lastLatitude, lastLongitude);
        }
        return null;
    }

    /**
     * Session age in human readable format
     */
    public String getSessionAge() {
        if (createdAt != null) {
            long seconds = Instant.now().getEpochSecond() - createdAt.getEpochSecond();
            if (seconds < 60) return seconds + "s";
            if (seconds < 3600) return (seconds / 60) + "m";
            return (seconds / 3600) + "h";
        }
        return "unknown";
    }
}