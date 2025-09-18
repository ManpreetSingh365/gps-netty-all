package com.wheelseye.devicegateway.model;

import java.time.Instant;

/**
 * Represents a device session event (connected/disconnected).
 */
public record DeviceSessionEvent(
    EventType eventType,
    String sessionId,
    IMEI imei,
    String deviceModel,
    String ipAddress,
    String protocolVersion,
    Instant timestamp
) {
    public enum EventType { CONNECTED, DISCONNECTED }

    public DeviceSessionEvent {
        // Ensure timestamp is set if null
        timestamp = (timestamp == null) ? Instant.now() : timestamp;
    }

    /** Creates a CONNECTED event with default protocol version. */
    public static DeviceSessionEvent connected(
        String sessionId, IMEI imei, String deviceModel, String ipAddress
    ) {
        return new DeviceSessionEvent(
            EventType.CONNECTED, sessionId, imei, deviceModel, ipAddress, "1.2", Instant.now()
        );
    }

    /** Creates a DISCONNECTED event with default protocol version. */
    public static DeviceSessionEvent disconnected(
        String sessionId, IMEI imei, String deviceModel, String ipAddress
    ) {
        return new DeviceSessionEvent(
            EventType.DISCONNECTED, sessionId, imei, deviceModel, ipAddress, "1.2", Instant.now()
        );
    }
}
