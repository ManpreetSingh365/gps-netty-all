package com.wheelseye.devicegateway.model;

import io.netty.buffer.ByteBuf;
import java.time.Instant;

import com.wheelseye.devicegateway.config.GT06ProtocolConfig;

/**
 * Immutable MessageFrame for GT06/GT06N devices.
 * Fully configurable via constants from GT06ProtocolConfig.
 */
public record MessageFrame(
        int startBits,
        int length,
        int protocolNumber,
        ByteBuf content,
        int serialNumber,
        int crc,
        int stopBits,
        String rawHex,
        Instant receivedAt,
        String imei) {

    // Factory method using configurable defaults from GT06ProtocolConfig
    public static MessageFrame of(int protocolNumber, int serialNumber, ByteBuf content, int crc) {
        return new MessageFrame(
                GT06ProtocolConfig.DEFAULT_START_BITS,
                content != null ? content.readableBytes() : 0,
                protocolNumber,
                content,
                serialNumber,
                crc,
                GT06ProtocolConfig.DEFAULT_STOP_BITS,
                "",
                Instant.now(),
                null);
    }

    // Ensure defaults for nullable fields
    public MessageFrame {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (rawHex == null) {
            rawHex = "";
        }
    }

    // Validation method using configurable protocol numbers
    public boolean isValid() {
        return GT06ProtocolConfig.isValidStartBits(startBits) &&
                stopBits == GT06ProtocolConfig.DEFAULT_STOP_BITS;
    }

    public boolean isLogin() {
        return protocolNumber == GT06ProtocolConfig.PROTOCOL_LOGIN;
    }

    public boolean isHeartbeat() {
        return protocolNumber == GT06ProtocolConfig.PROTOCOL_HEARTBEAT;
    }

    public boolean isLocation() {
        return protocolNumber == GT06ProtocolConfig.PROTOCOL_LOCATION;
    }

    @Override
    public String toString() {
        return String.format(
                "MessageFrame{startBits=0x%04X, length=%d, protocol=0x%02X, serial=%d, crc=0x%04X, receivedAt=%s}",
                startBits, length, protocolNumber, serialNumber, crc, receivedAt);
    }
}
