package com.aman.location.mapper;

import com.google.protobuf.Timestamp;
import com.aman.location.dto.LocationDto;
import com.aman.location.protobuf.Location;
import java.time.Instant;

public class LocationMapper {

    public static Location toProto(LocationDto dto) {
        if (dto == null) return null;

        Location.Builder builder = Location.newBuilder()
                .setGpsValid(dto.gpsValid())
                .setLatitude(dto.latitude())
                .setLongitude(dto.longitude())
                .setSpeed(dto.speed())
                .setCourse(dto.course())
                .setAccuracy(dto.accuracy())
                .setSatellites(dto.satellites());

        Instant ts = dto.timestamp();
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

    public static LocationDto fromProto(Location proto) {
        Instant timestamp = null;
        if (proto.hasTimestamp()) {
            timestamp = Instant.ofEpochSecond(
                    proto.getTimestamp().getSeconds(),
                    proto.getTimestamp().getNanos()
            );
        }

        return new LocationDto(
                timestamp,
                proto.getGpsValid(),
                proto.getLatitude(),
                proto.getLongitude(),
                proto.getSpeed(),
                proto.getCourse(),
                proto.getAccuracy(),
                proto.getSatellites()
        );
    }
}
