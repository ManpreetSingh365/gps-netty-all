package com.wheelseye.devicegateway.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.Map;

/**
 * Modern Device Session DTO - Fixed and corrected version using Java 21
 * 
 * Features:
 * - Java 21 record with proper syntax
 * - Fixed validation annotations
 * - Corrected builder pattern
 * - Minimal and focused functionality
 */
@Schema(description = "Device session information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceSessionDto(
    
    @Schema(description = "Unique session identifier", example = "sess_abc123def456")
    @NotNull
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "Session ID must contain only alphanumeric characters, hyphens, and underscores")
    String sessionId,
    
    @Schema(description = "Device IMEI (15 digits)", example = "123456789012345")
    @Pattern(regexp = "\\d{15}", message = "IMEI must be exactly 15 digits")
    String imei,
    
    @Schema(description = "Netty channel identifier", example = "ch_789xyz")
    String channelId,
    
    @Schema(description = "Remote IP address and port", example = "192.168.1.100:45678")
    String remoteAddress,
    
    @Schema(description = "Protocol version used by device", example = "GT06v2.1")
    String protocolVersion,
    
    @Schema(description = "Device model/variant", example = "GT06N")
    String deviceVariant,
    
    @Schema(description = "Current device status", allowableValues = {"ONLINE", "OFFLINE", "IDLE", "ERROR"})
    String status,
    
    @Schema(description = "Whether device is authenticated", example = "true")
    @NotNull
    boolean authenticated,
    
    @Schema(description = "Session creation timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @NotNull
    Instant createdAt,
    
    @Schema(description = "Last activity timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    @NotNull
    Instant lastActivityAt,
    
    @Schema(description = "Last login timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant lastLoginAt,
    
    @Schema(description = "Current GPS latitude", example = "40.7589")
    Double currentLatitude,
    
    @Schema(description = "Current GPS longitude", example = "-73.9851")
    Double currentLongitude,
    
    @Schema(description = "Last position update timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant lastPositionTime,
    
    @Schema(description = "Last heartbeat timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    Instant lastHeartbeat,
    
    @Schema(description = "Additional session attributes")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, Object> attributes
    
) {
    
    /**
     * Static factory method for creating basic DTO
     */
    public static DeviceSessionDto create(String sessionId, String imei, boolean authenticated,
                                        Instant createdAt, Instant lastActivityAt) {
        return new DeviceSessionDto(
            sessionId, imei, null, null, null, null, null, authenticated,
            createdAt, lastActivityAt, null, null, null, null, null, Map.of()
        );
    }
    
    /**
     * Check if session has valid position data
     */
    public boolean hasValidPosition() {
        return currentLatitude != null && currentLongitude != null && 
               currentLatitude >= -90.0 && currentLatitude <= 90.0 &&
               currentLongitude >= -180.0 && currentLongitude <= 180.0;
    }
    
    /**
     * Get session duration in seconds
     */
    public long getSessionDurationSeconds() {
        if (createdAt == null || lastActivityAt == null) {
            return 0;
        }
        return java.time.Duration.between(createdAt, lastActivityAt).getSeconds();
    }
    
    /**
     * Check if session is considered idle
     */
    public boolean isIdle(java.time.Duration idleThreshold) {
        if (lastActivityAt == null) {
            return true;
        }
        return java.time.Duration.between(lastActivityAt, Instant.now()).compareTo(idleThreshold) > 0;
    }
    
    /**
     * Get display-friendly status
     */
    public String getDisplayStatus() {
        return switch (status) {
            case null -> authenticated ? "ONLINE" : "CONNECTING";
            case String s when s.isBlank() -> authenticated ? "ONLINE" : "CONNECTING";  
            case String s -> s.toUpperCase();
        };
    }
}