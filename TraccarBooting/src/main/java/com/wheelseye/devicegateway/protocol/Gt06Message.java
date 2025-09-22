package com.wheelseye.devicegateway.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * FIXED GT06 Protocol Message Model - Complete GPS data representation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Gt06Message {
    
    private MessageType type;
    private String imei;
    private LocalDateTime timestamp;
    
    // Location data
    private Double latitude;
    private Double longitude;
    private Double speed;      // km/h
    private Double course;     // degrees
    private Integer satellites;
    private Boolean gpsFixed;
    
    // Device status
    private Boolean charging;
    private Integer gsmSignal;  // 0-3
    private Integer batteryLevel; // percentage
    private String alarmType;
    
    // Protocol data
    private Integer serialNumber;
    private String rawData;     // Hex representation
    
    public enum MessageType {
        LOGIN, GPS, HEARTBEAT, ALARM, STATUS, UNKNOWN
    }
    
    public String getLocationString() {
        if (latitude != null && longitude != null) {
            return String.format("[%.6f, %.6f]", latitude, longitude);
        }
        return "[No Location]";
    }
    
    public String getGoogleMapsUrl() {
        if (latitude != null && longitude != null) {
            return String.format("https://www.google.com/maps?q=%.6f,%.6f", latitude, longitude);
        }
        return null;
    }
    
    public boolean hasValidLocation() {
        return latitude != null && longitude != null && 
               latitude != 0.0 && longitude != 0.0 &&
               Boolean.TRUE.equals(gpsFixed);
    }
}