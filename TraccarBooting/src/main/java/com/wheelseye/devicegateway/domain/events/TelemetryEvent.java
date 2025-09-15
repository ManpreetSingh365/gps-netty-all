// package com.wheelseye.devicegateway.domain.events;

// import com.wheelseye.devicegateway.dto.LocationDto;
// import com.wheelseye.devicegateway.domain.valueobjects.IMEI;

// import java.time.Instant;
// import java.util.Map;

// public class TelemetryEvent {
//     private final IMEI imei;
//     private final String messageType;
//     private final LocationDto location;
//     private final Integer batteryLevel;
//     private final String gpsSignalStrength;
//     private final Map<String, Object> attributes;
//     private final String rawFrame;
//     private final Instant timestamp;

//     public TelemetryEvent(
//             IMEI imei,
//             String messageType,
//             LocationDto location,
//             Integer batteryLevel,
//             String gpsSignalStrength,
//             Map<String, Object> attributes,
//             String rawFrame,
//             Instant timestamp
//     ) {
//         this.imei = imei;
//         this.messageType = messageType;
//         this.location = location;
//         this.batteryLevel = batteryLevel;
//         this.gpsSignalStrength = gpsSignalStrength;
//         this.attributes = attributes;
//         this.rawFrame = rawFrame;
//         this.timestamp = timestamp;
//     }

//     // --- getters ---
//     public IMEI getImei() { return imei; }
//     public String getMessageType() { return messageType; }
//     public LocationDto getLocation() { return location; }
//     public Integer getBatteryLevel() { return batteryLevel; }
//     public String getGpsSignalStrength() { return gpsSignalStrength; }
//     public Map<String, Object> getAttributes() { return attributes; }
//     public String getRawFrame() { return rawFrame; }
//     public Instant getTimestamp() { return timestamp; }
// }
