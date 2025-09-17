package com.wheelseye.devicegateway.dto;

public record DeviceLbsDataDto(
        String lbsHex,
        int mcc,
        int mnc,
        int lac,
        int cid,
        int rssi) {
    public static DeviceLbsDataDto getDefaultDeviceLbsData() {
        return new DeviceLbsDataDto("N/A", 0, 0, 0, 0, -95);
    }
}
