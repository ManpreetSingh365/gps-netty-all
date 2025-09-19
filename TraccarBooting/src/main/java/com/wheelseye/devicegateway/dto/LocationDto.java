package com.wheelseye.devicegateway.dto;

import java.time.Instant;

public record LocationDto(
                Instant timestamp,
                boolean gpsValid,
                double latitude,
                double longitude,
                double speed, // change to float later
                double course, // change to float later
                double accuracy, // change to float later
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
