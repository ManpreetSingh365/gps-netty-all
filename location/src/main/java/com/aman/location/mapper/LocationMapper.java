package com.aman.location.mapper;

import com.google.protobuf.Timestamp;
import com.aman.location.entity.Location;

import java.time.Instant;

public class LocationMapper {

    public static  com.aman.location.protobuf.Location toProto(Location location) {
        if (location == null) return null;

        com.aman.location.protobuf.Location.Builder builder =
                com.aman.location.protobuf.Location.newBuilder()
                        .setLatitude(location.getLatitude())
                        .setLongitude(location.getLongitude())
                        .setAltitude(location.getAltitude())
                        .setSpeed(location.getSpeed())
                        .setCourse(location.getCourse())
                        .setValid(location.isValid())
                        .setSatellites(location.getSatellites());

        Instant ts = location.getTimestamp();
        if (ts != null) {
            builder.setTimestamp(
                    Timestamp.newBuilder()
                            .setSeconds(ts.getEpochSecond())
                            .setNanos(ts.getNano())
                            .build()
            );
        }

        return builder.build();
    }

    public static Location fromProto(com.aman.location.protobuf.Location proto) {
        Instant timestamp = Instant.ofEpochSecond(
                proto.getTimestamp().getSeconds(),
                proto.getTimestamp().getNanos()
        );

        return new Location(
                proto.getLatitude(),
                proto.getLongitude(),
                proto.getAltitude(),
                proto.getSpeed(),
                proto.getCourse(),
                proto.getValid(),
                timestamp,
                proto.getSatellites()
        );
    }
}

