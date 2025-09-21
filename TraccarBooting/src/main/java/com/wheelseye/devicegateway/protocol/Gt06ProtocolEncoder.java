package com.wheelseye.devicegateway.protocol;

import com.wheelseye.devicegateway.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Production-ready GT06 Protocol Encoder for GPS device communication.
 * 
 * Updated to use modern, non-deprecated Netty APIs compatible with latest versions.
 * Supports all GT06 protocol message types with proper encoding and validation.
 */
@Component
@ChannelHandler.Sharable
public class Gt06ProtocolEncoder extends MessageToByteEncoder<DeviceMessage> {

    private static final Logger log = LoggerFactory.getLogger(Gt06ProtocolEncoder.class);

    // GT06 Protocol Constants
    private static final byte[] START_BITS = {0x78, 0x78};
    private static final byte[] END_BITS = {0x0D, 0x0A};

    // Protocol Numbers
    private static final byte PROTOCOL_LOGIN_RESPONSE = 0x01;
    private static final byte PROTOCOL_GPS_RESPONSE = 0x02;
    private static final byte PROTOCOL_HEARTBEAT_RESPONSE = 0x03;
    private static final byte PROTOCOL_STRING_RESPONSE = 0x04;
    private static final byte PROTOCOL_COMMAND = 0x08;

    @Override
    protected void encode(ChannelHandlerContext ctx, DeviceMessage message, ByteBuf out) throws Exception {
        try {
            switch (message.type().toLowerCase()) {
                case "login_ack" -> encodeLoginResponse(message, out);
                case "gps_ack" -> encodeGpsResponse(message, out);
                case "heartbeat_ack" -> encodeHeartbeatResponse(message, out);
                case "string_ack" -> encodeStringResponse(message, out);
                case "command" -> encodeCommand(message, out);
                default -> {
                    log.warn("⚠️ Unknown message type for encoding: {}", message.type());
                    encodeGenericResponse(message, out);
                }
            }

            log.debug("📤 Encoded {} message for device: {}", message.type(), message.imei());

        } catch (Exception e) {
            log.error("❌ Failed to encode message type {} for device {}: {}", 
                    message.type(), message.imei(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Encode login acknowledgment response.
     */
    private void encodeLoginResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0001);

        writeFrame(out, PROTOCOL_LOGIN_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Encode GPS data acknowledgment response.
     */
    private void encodeGpsResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0002);

        writeFrame(out, PROTOCOL_GPS_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Encode heartbeat acknowledgment response.
     */
    private void encodeHeartbeatResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0003);

        writeFrame(out, PROTOCOL_HEARTBEAT_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Encode string message acknowledgment.
     */
    private void encodeStringResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0004);

        writeFrame(out, PROTOCOL_STRING_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Encode command message to device.
     */
    private void encodeCommand(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var command = getStringValue(data, "command", "");
        var serialNumber = getIntValue(data, "serialNumber", 0x0001);

        if (command.isEmpty()) {
            log.warn("⚠️ Empty command for device: {}", message.imei());
            return;
        }

        writeFrame(out, PROTOCOL_COMMAND, buffer -> {
            // Write command string
            byte[] commandBytes = command.getBytes(StandardCharsets.UTF_8);
            buffer.writeBytes(commandBytes);
            buffer.writeShort(serialNumber);
        });

        log.info("📡 Sent command to device {}: {}", message.imei(), command);
    }

    /**
     * Encode generic response.
     */
    private void encodeGenericResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0000);

        writeFrame(out, PROTOCOL_LOGIN_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Write GT06 frame with proper structure and CRC calculation.
     * Updated to use modern ByteBuf operations (non-deprecated APIs).
     */
    private void writeFrame(ByteBuf out, byte protocolNumber, FrameContentWriter contentWriter) {
        // Create temporary buffer for content to calculate length
        ByteBuf contentBuffer = out.alloc().buffer();

        try {
            // Write content to temporary buffer
            contentWriter.writeContent(contentBuffer);

            int contentLength = contentBuffer.readableBytes();
            int totalLength = contentLength + 2; // +2 for protocol number and length byte

            // Write start bits
            out.writeBytes(START_BITS);

            // Write length
            out.writeByte(totalLength);

            // Write protocol number  
            out.writeByte(protocolNumber);

            // Write content
            out.writeBytes(contentBuffer);

            // Calculate and write CRC-16
            int crc = calculateCRC16(out, 2, totalLength + 1); // Skip start bits, include length+protocol+content
            out.writeShort(crc);

            // Write end bits
            out.writeBytes(END_BITS);

        } finally {
            // Release temporary buffer (proper resource management)
            contentBuffer.release();
        }
    }

    /**
     * Calculate CRC-16 for GT06 protocol.
     * Updated implementation using modern ByteBuf methods.
     */
    private int calculateCRC16(ByteBuf buffer, int start, int length) {
        int crc = 0xFFFF;
        int savedReaderIndex = buffer.readerIndex();

        try {
            buffer.readerIndex(start);

            for (int i = 0; i < length; i++) {
                int data = buffer.readUnsignedByte();
                crc ^= data;

                for (int j = 0; j < 8; j++) {
                    if ((crc & 0x0001) != 0) {
                        crc >>= 1;
                        crc ^= 0xA001;
                    } else {
                        crc >>= 1;
                    }
                }
            }

            return crc;

        } finally {
            // Restore original reader index
            buffer.readerIndex(savedReaderIndex);
        }
    }

    /**
     * Safely get integer value from message data.
     */
    private int getIntValue(java.util.Map<String, Object> data, String key, int defaultValue) {
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("⚠️ Invalid integer value for key '{}': {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Safely get string value from message data.
     */
    private String getStringValue(java.util.Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Functional interface for writing frame content.
     */
    @FunctionalInterface
    private interface FrameContentWriter {
        void writeContent(ByteBuf buffer);
    }

    /**
     * Create command message for device configuration.
     */
    // public static DeviceMessage createConfigurationCommand(String imei, String command) {
    //     var data = new java.util.HashMap<String, Object>();
    //     data.put("command", command);
    //     data.put("serialNumber", System.currentTimeMillis() & 0xFFFF);

    //     return new DeviceMessage(imei, "GT06", "command", java.time.Instant.now(), data);
    // }

    /**
     * Create location reporting configuration command.
     */
    // public static DeviceMessage createLocationReportingCommand(String imei, int intervalSeconds) {
    //     String command = String.format("**,imei:%s,C02,%ds", imei, intervalSeconds);
    //     return createConfigurationCommand(imei, command);
    // }

    // /**
    //  * Create device reset command.
    //  */
    // public static DeviceMessage createResetCommand(String imei) {
    //     String command = String.format("**,imei:%s,E00", imei);
    //     return createConfigurationCommand(imei, command);
    // }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("❌ GT06 encoder error for {}: {}", 
                ctx.channel().remoteAddress(), cause.getMessage(), cause);
        super.exceptionCaught(ctx, cause);
    }
}
