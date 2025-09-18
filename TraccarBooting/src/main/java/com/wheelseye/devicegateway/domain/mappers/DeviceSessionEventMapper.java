package com.wheelseye.devicegateway.domain.mappers;

import com.google.protobuf.Timestamp;
import com.wheelseye.devicegateway.domain.events.DeviceSessionEvent;
import com.wheelseye.devicegateway.domain.valueobjects.IMEI;

import java.time.Instant;

public class DeviceSessionEventMapper {

    // Domain -> Proto
    public static com.wheelseye.devicegateway.protobuf.DeviceSessionEvent toProto(DeviceSessionEvent event) {
        return com.wheelseye.devicegateway.protobuf.DeviceSessionEvent.newBuilder()
                .setEventType(event.eventType())
                .setSessionId(event.sessionId())
                .setImei(
                        com.wheelseye.devicegateway.protobuf.IMEI.newBuilder()
                                .setValue(event.imei().getValue())
                                .build()
                )
                .setDeviceModel(event.deviceModel())
                .setIpAddress(event.ipAddress())
                .setProtocolVersion(event.protocolVersion())
                .setTimestamp(toProtoTimestamp(event.timestamp()))
                .build();
    }

    // Proto -> Domain
    public static DeviceSessionEvent fromProto(com.wheelseye.devicegateway.protobuf.DeviceSessionEvent proto) {
        Instant timestamp = Instant.ofEpochSecond(
                proto.getTimestamp().getSeconds(),
                proto.getTimestamp().getNanos()
        );

        // Use private constructor via factory method, since record is immutable
        return new DeviceSessionEvent(
                proto.getEventType(),
                proto.getSessionId(),
                new IMEI(proto.getImei().getValue()),
                proto.getDeviceModel(),
                proto.getIpAddress(),
                proto.getProtocolVersion(),
                timestamp
        );
    }

    // Helper: Instant -> Protobuf Timestamp
    private static Timestamp toProtoTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
