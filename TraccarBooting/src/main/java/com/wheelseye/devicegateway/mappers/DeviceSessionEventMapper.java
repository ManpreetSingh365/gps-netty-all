package com.wheelseye.devicegateway.mappers;

import com.google.protobuf.Timestamp;
import com.wheelseye.devicegateway.model.DeviceSessionEvent;
import com.wheelseye.devicegateway.model.DeviceSessionEvent.EventType;
import com.wheelseye.devicegateway.model.IMEI;

import java.time.Instant;

public final class DeviceSessionEventMapper {

    private DeviceSessionEventMapper() { /* utility */ }

    // Domain -> Proto
    public static com.wheelseye.devicegateway.protobuf.DeviceSessionEvent toProto(DeviceSessionEvent event) {
        return com.wheelseye.devicegateway.protobuf.DeviceSessionEvent.newBuilder()
            .setEventType(event.eventType().name())
            .setSessionId(event.sessionId())
            .setImei(com.wheelseye.devicegateway.protobuf.IMEI.newBuilder()
                .setValue(event.imei().value())
                .build())
            .setDeviceModel(event.deviceModel())
            .setIpAddress(event.ipAddress())
            .setProtocolVersion(event.protocolVersion())
            .setTimestamp(toProtoTimestamp(event.timestamp()))
            .build();
    }

    // Proto -> Domain
    public static DeviceSessionEvent fromProto(com.wheelseye.devicegateway.protobuf.DeviceSessionEvent proto) {
        var imei = new IMEI(proto.getImei().getValue());
        var model = proto.getDeviceModel();
        var ip    = proto.getIpAddress();

        return switch (EventType.valueOf(proto.getEventType())) {
            case CONNECTED ->
                DeviceSessionEvent.connected(proto.getSessionId(), imei, model, ip);
            case DISCONNECTED ->
                DeviceSessionEvent.disconnected(proto.getSessionId(), imei, model, ip);
        };
    }

    // Helper: Instant -> Protobuf Timestamp
    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
            .setSeconds(instant.getEpochSecond())
            .setNanos(instant.getNano())
            .build();
    }
}
