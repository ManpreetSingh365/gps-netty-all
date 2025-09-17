package com.wheelseye.devicegateway.dto;

public record DeviceStatusDto(
                boolean ignition,
                int accRaw,
                boolean gpsFixed,
                int direction,
                int satellites,
                boolean externalPower,
                boolean charging,
                int batteryVoltage,
                int batteryPercent,
                String batteryLevelText,
                String voltageLevelText,
                int gsmSignal,
                int signalLevel,
                int statusBits) {
                        
        public static DeviceStatusDto getDefaultDeviceStatus() {
                return new DeviceStatusDto(false, 0, false, 0, 0, false, false, 0, 0, "Critical", "Very Weak", -95, 1, 0);
        }
}
