package com.wheelseye.devicegateway.dto;

public record DeviceExtendedFeatureDto(
        String featureHex,
        boolean smsCommands,
        int uploadInterval,
        int distanceUpload,
        int heartbeatInterval,
        int cellScanCount,
        String backupMode,
        boolean sleepMode) {

    public static DeviceExtendedFeatureDto getDefaultFeatures() {
        return new DeviceExtendedFeatureDto(
                "0000",
                true,
                30,
                200,
                300,
                1,
                "SMS",
                false);
    }
}
