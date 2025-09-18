package com.wheelseye.devicegateway.domain.events;

import com.wheelseye.devicegateway.domain.valueobjects.IMEI;
import java.time.Instant;

public record DeviceSessionEvent(
        String eventType,
        String sessionId,
        IMEI imei,
        String deviceModel,
        String ipAddress,
        String protocolVersion,
        Instant timestamp
) {
    // Private constructor for factory methods, auto-sets timestamp
    private DeviceSessionEvent(String eventType, String sessionId, IMEI imei,
                               String deviceModel, String ipAddress, String protocolVersion) {
        this(eventType, sessionId, imei, deviceModel, ipAddress, protocolVersion, Instant.now());
    }

    // Factory methods
    public static DeviceSessionEvent connected(String sessionId, IMEI imei, String deviceModel, String ipAddress) {
        return new DeviceSessionEvent("SESSION_CONNECTED", sessionId, imei, deviceModel, ipAddress, "1.2");
    }

    public static DeviceSessionEvent disconnected(String sessionId, IMEI imei, String deviceModel, String ipAddress) {
        return new DeviceSessionEvent("SESSION_DISCONNECTED", sessionId, imei, deviceModel, ipAddress, "1.2");
    }
}
