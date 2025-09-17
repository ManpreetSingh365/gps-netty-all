package com.wheelseye.devicegateway.dto;

public record DeviceIOPortsDto(
        String ioHex,       // raw 4-byte I/O
        boolean ignition,   // IN1 / ACC
        String motion,      // MOVING / STATIONARY (derived)
        String input2,      // IN2 digital input
        String out1,        // OUT1 relay
        String out2,        // OUT2 relay (optional)
        Double adc1Voltage, // ADC1 (0-5V)
        Double adc2Voltage  // ADC2 (0-5V)
) {
    public static DeviceIOPortsDto getDefaultIOPorts() {
        return new DeviceIOPortsDto(
                "N/A", false, "STATIONARY", "OFF", "OFF", "OFF", null, null
        );
    }
}
