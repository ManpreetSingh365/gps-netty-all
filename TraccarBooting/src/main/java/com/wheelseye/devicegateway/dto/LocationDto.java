package com.wheelseye.devicegateway.dto;

import java.time.Instant;

public record LocationDto(
                Instant timestamp,
                boolean gpsValid,
                double latitude,
                double longitude,
                double speed,
                double course,
                double accuracy,
                int satellites) {

        public static LocationDto getDefaultLocation() {
                return new LocationDto(
                                Instant.EPOCH,
                                false,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0);
        }
}
