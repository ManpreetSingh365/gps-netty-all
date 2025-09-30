package com.wheelseye.devicegateway.dto;

import java.time.Instant;

public record LocationDto(
                String imei,
                Instant timestamp,
                boolean gpsValid,
                double latitude,
                double longitude,
                double speed,
                double course,
                int satellites
) {

        /** Default location in case of missing data */
        public static LocationDto getDefaultLocation(String imei) {
                return new LocationDto(
                                imei,
                                Instant.EPOCH,
                                false,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                0);
        }
}
