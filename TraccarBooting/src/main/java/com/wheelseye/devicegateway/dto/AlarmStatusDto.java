package com.wheelseye.devicegateway.dto;

public record AlarmStatusDto(
        String alarmHex,
        boolean sosAlarm,
        boolean vibrationAlarm,
        boolean tamperAlarm,
        boolean lowBatteryAlarm,
        boolean overSpeedAlarm,
        boolean idleAlarm
) {
    public static AlarmStatusDto getDefaultStatus() {
        return new AlarmStatusDto("0000", false, false, false, false, false, false);
    }
}
