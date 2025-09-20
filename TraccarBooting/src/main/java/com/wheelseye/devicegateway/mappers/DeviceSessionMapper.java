// // DeviceSessionMapper.java
// package com.wheelseye.devicegateway.mappers;

// import com.google.protobuf.Timestamp;
// import com.wheelseye.devicegateway.model.DeviceSession;
// import com.wheelseye.devicegateway.model.IMEI;

// import java.time.Instant;
// import java.util.Map;
// import java.util.stream.Collectors;

// public class DeviceSessionMapper {

//     // Helper proto constructor for embedding in DeviceSessionEvent
//     public static com.wheelseye.devicegateway.protobuf.DeviceSession toProto(
//         String id,
//         IMEI imei,
//         String deviceModel,
//         String ipAddress,
//         String protocolVersion,
//         Instant timestamp
//     ) {
//         return com.wheelseye.devicegateway.protobuf.DeviceSession.newBuilder()
//             .setId(id)
//             .setImei(
//                 com.wheelseye.devicegateway.protobuf.IMEI.newBuilder()
//                     .setValue(imei.value())
//                     .build())
//             .setDeviceModel(deviceModel)
//             .setIpAddress(ipAddress)
//             .setProtocolVersion(protocolVersion)
//             .setLastActivityAt(toProtoTimestamp(timestamp))
//             .build();
//     }

//     // Domain -> Proto
//     public static com.wheelseye.devicegateway.protobuf.DeviceSession toProto(DeviceSession entity) {
//         if (entity == null) return null;

//         var builder = com.wheelseye.devicegateway.protobuf.DeviceSession.newBuilder()
//             .setId(entity.getId())
//             .setImei(
//                 com.wheelseye.devicegateway.protobuf.IMEI.newBuilder()
//                     .setValue(entity.getImei().value())
//                     .build())
//             .setChannelId(entity.getChannelId())
//             .setCreatedAt(toProtoTimestamp(entity.getCreatedAt()))
//             .setLastActivityAt(toProtoTimestamp(entity.getLastActivityAt()))
//             .setAuthenticated(entity.isAuthenticated())
//             .setRemoteAddress(entity.getRemoteAddress());

//         if (entity.getAttributes() != null) {
//             Map<String, String> attrs = entity.getAttributes().entrySet().stream()
//                 .filter(e -> e.getValue() != null)
//                 .collect(Collectors.toMap(
//                     Map.Entry::getKey,
//                     e -> e.getValue().toString()
//                 ));
//             builder.putAllAttributes(attrs);
//         }

//         return builder.build();
//     }

//     // Proto -> Domain
//     public static DeviceSession fromProto(com.wheelseye.devicegateway.protobuf.DeviceSession proto) {
//         if (proto == null) return null;

//         IMEI imei = IMEI.of(proto.getImei().getValue());

//         return new DeviceSession(
//             proto.getId(),
//             imei,
//             proto.getChannelId(),
//             proto.getRemoteAddress(),
//             proto.getProtocolVersion(),
//             proto.getStatus(),
//             proto.getAuthenticated(),
//             fromProtoTimestamp(proto.getCreatedAt()),
//             fromProtoTimestamp(proto.getLastActivityAt()),
//             proto.hasLastLoginAt() ? fromProtoTimestamp(proto.getLastLoginAt()) : null,
//             proto.getAttributesMap()
//         );
//     }

//     private static Timestamp toProtoTimestamp(Instant instant) {
//         if (instant == null) return null;
//         return Timestamp.newBuilder()
//             .setSeconds(instant.getEpochSecond())
//             .setNanos(instant.getNano())
//             .build();
//     }

//     private static Instant fromProtoTimestamp(Timestamp ts) {
//         if (ts == null) return null;
//         return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
//     }
// }
