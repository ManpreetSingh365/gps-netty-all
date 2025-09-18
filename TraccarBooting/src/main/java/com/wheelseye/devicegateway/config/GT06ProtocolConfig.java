package com.wheelseye.devicegateway.config;

import java.util.Set;

public final class GT06ProtocolConfig {

    private GT06ProtocolConfig() {}

    // Default GT06/GT06N start/stop bits
    public static final int DEFAULT_START_BITS = 0x7878;
    public static final int ALTERNATE_START_BITS = 0x7979;
    public static final int DEFAULT_STOP_BITS = 0x0D0A;

    // Common GT06 protocol numbers
    public static final int PROTOCOL_LOGIN = 0x01;
    public static final int PROTOCOL_LOCATION = 0x12;
    public static final int PROTOCOL_HEARTBEAT = 0x13;

    private static final Set<Integer> VALID_START_BITS = Set.of(DEFAULT_START_BITS, ALTERNATE_START_BITS);

    public static boolean isValidStartBits(int startBits) {
        return VALID_START_BITS.contains(startBits);
    }
}
