package com.wheelseye.devicegateway.protocol.gt06;

import com.wheelseye.devicegateway.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced GT06 Protocol Encoder - Integrated with Existing Codebase
 * 
 * Maintains compatibility with existing DeviceMessage model while adding
 * proper GT06 command encoding capability based on official protocol v1.8.1
 * 
 * Supports both response acknowledgments (existing) and server commands (new)
 * 
 * @author WheelsEye Team - Enhanced for Command Support
 * @version 2.1.0 - Official GT06 Command Integration
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class Gt06ProtocolEncoder extends MessageToByteEncoder<DeviceMessage> {

    // GT06 Protocol Constants (Official Specification)
    private static final byte[] START_BITS = {0x78, 0x78};
    private static final byte[] END_BITS = {0x0D, 0x0A};
    
    // Protocol Numbers
    private static final byte PROTOCOL_LOGIN_RESPONSE = 0x01;
    private static final byte PROTOCOL_GPS_RESPONSE = 0x02; 
    private static final byte PROTOCOL_HEARTBEAT_RESPONSE = 0x03;
    private static final byte PROTOCOL_STRING_RESPONSE = 0x04;
    private static final byte PROTOCOL_COMMAND = (byte) 0x80;  // Server to device commands
    
    // Language Constants
    private static final int LANGUAGE_CHINESE = 0x0001;
    private static final int LANGUAGE_ENGLISH = 0x0002;
    
    // CRC-ITU Lookup Table (from official GT06 documentation Appendix A)
    private static final int[] CRC_TABLE = {
        0x0000, 0x1189, 0x2312, 0x329B, 0x4624, 0x57AD, 0x6536, 0x74BF,
        0x8C48, 0x9DC1, 0xAF5A, 0xBED3, 0xCA6C, 0xDBE5, 0xE97E, 0xF8F7,
        0x1081, 0x0108, 0x3393, 0x221A, 0x56A5, 0x472C, 0x75B7, 0x643E,
        0x9CC9, 0x8D40, 0xBFDB, 0xAE52, 0xDAED, 0xCB64, 0xF9FF, 0xE876,
        0x2102, 0x308B, 0x0210, 0x1399, 0x6726, 0x76AF, 0x4434, 0x55BD,
        0xAD4A, 0xBCC3, 0x8E58, 0x9FD1, 0xEB6E, 0xFAE7, 0xC87C, 0xD9F5,
        0x3183, 0x200A, 0x1291, 0x0318, 0x77A7, 0x662E, 0x54B5, 0x453C,
        0xBDCB, 0xAC42, 0x9ED9, 0x8F50, 0xFBEF, 0xEA66, 0xD8FD, 0xC974,
        0x4204, 0x538D, 0x6116, 0x709F, 0x0420, 0x15A9, 0x2732, 0x36BB,
        0xCE4C, 0xDFC5, 0xED5E, 0xFCD7, 0x8868, 0x99E1, 0xAB7A, 0xBAF3,
        0x5285, 0x430C, 0x7197, 0x601E, 0x14A1, 0x0528, 0x37B3, 0x263A,
        0xDECD, 0xCF44, 0xFDDF, 0xEC56, 0x98E9, 0x8960, 0xBBFB, 0xAA72,
        0x6306, 0x728F, 0x4014, 0x519D, 0x2522, 0x34AB, 0x0630, 0x17B9,
        0xEF4E, 0xFEC7, 0xCC5C, 0xDDD5, 0xA96A, 0xB8E3, 0x8A78, 0x9BF1,
        0x7387, 0x620E, 0x5095, 0x411C, 0x35A3, 0x242A, 0x16B1, 0x0738,
        0xFFCF, 0xEE46, 0xDCDD, 0xCD54, 0xB9EB, 0xA862, 0x9AF9, 0x8B70,
        0x8408, 0x9581, 0xA71A, 0xB693, 0xC22C, 0xD3A5, 0xE13E, 0xF0B7,
        0x0840, 0x19C9, 0x2B52, 0x3ADB, 0x4E64, 0x5FED, 0x6D76, 0x7CFF,
        0x9489, 0x8500, 0xB79B, 0xA612, 0xD2AD, 0xC324, 0xF1BF, 0xE036,
        0x18C1, 0x0948, 0x3BD3, 0x2A5A, 0x5EE5, 0x4F6C, 0x7DF7, 0x6C7E,
        0xA50A, 0xB483, 0x8618, 0x9791, 0xE32E, 0xF2A7, 0xC03C, 0xD1B5,
        0x2942, 0x38CB, 0x0A50, 0x1BD9, 0x6F66, 0x7EEF, 0x4C74, 0x5DFD,
        0xB58B, 0xA402, 0x9699, 0x8710, 0xF3AF, 0xE226, 0xD0BD, 0xC134,
        0x39C3, 0x284A, 0x1AD1, 0x0B58, 0x7FE7, 0x6E6E, 0x5CF5, 0x4D7C,
        0xC60C, 0xD785, 0xE51E, 0xF497, 0x8028, 0x91A1, 0xA33A, 0xB2B3,
        0x4A44, 0x5BCD, 0x6956, 0x78DF, 0x0C60, 0x1DE9, 0x2F72, 0x3EFB,
        0xD68D, 0xC704, 0xF59F, 0xE416, 0x90A9, 0x8120, 0xB3BB, 0xA232,
        0x5AC5, 0x4B4C, 0x79D7, 0x685E, 0x1CE1, 0x0D68, 0x3FF3, 0x2E7A,
        0xE70E, 0xF687, 0xC41C, 0xD595, 0xA12A, 0xB0A3, 0x8238, 0x93B1,
        0x6B46, 0x7ACF, 0x4854, 0x59DD, 0x2D62, 0x3CEB, 0x0E70, 0x1FF9,
        0xF78F, 0xE606, 0xD49D, 0xC514, 0xB1AB, 0xA022, 0x92B9, 0x8330,
        0x7BC7, 0x6A4E, 0x58D5, 0x495C, 0x3DE3, 0x2C6A, 0x1EF1, 0x0F78
    };

    
    // Thread-safe serial number generator
    private final AtomicInteger serialNumber = new AtomicInteger(1);

    @Override
    protected void encode(ChannelHandlerContext ctx, DeviceMessage message, ByteBuf out) throws Exception {
        try {
            log.info("📤 ===> Encoding message type: {} for device: {}", message.type(), message.imei());

            switch (message.type().toLowerCase()) {
                
                // Existing response handling (maintain compatibility)
                case "login_ack" -> encodeLoginResponse(message, out);
                case "gps_ack" -> encodeGpsResponse(message, out);
                case "heartbeat_ack" -> encodeHeartbeatResponse(message, out);
                case "string_ack" -> encodeStringResponse(message, out);
                
                // NEW: GT06 Command Support
                case "gt06_command" -> encodeGt06Command(message, out);
                case "engine_cut_off" -> encodeEngineCommand(message, out, "DYD");
                case "engine_restore" -> encodeEngineCommand(message, out, "HFYD");
                case "location_request" -> encodeLocationRequest(message, out);
                case "device_reset" -> encodeDeviceReset(message, out);
                case "status_query" -> encodeStatusQuery(message, out);
                case "timer_config" -> encodeTimerConfig(message, out);
                case "server_config" -> encodeServerConfig(message, out);
                
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
     * NEW: Encode generic GT06 command (official packet structure)
     */
    private void encodeGt06Command(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var command = getStringValue(data, "command", "");
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        var useEnglish = getBooleanValue(data, "useEnglish", true);
        
        if (command.isEmpty()) {
            log.warn("⚠️ Empty GT06 command for device: {}", message.imei());
            return;
        }
        
        encodeOfficialGt06Command(out, command, password, serverFlag, useEnglish);
        log.info("encodeGt06Command: 📡 Sent GT06 command to device {}: {}", message.imei(), command);
    }

    /**
     * NEW: Engine control commands (DYD/HFYD)
     */
    private void encodeEngineCommand(DeviceMessage message, ByteBuf out, String commandPrefix) {
        var data = message.data();
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        String command = password != null ? String.format("%s,%s#", commandPrefix, password) : commandPrefix + "#";
            
        encodeOfficialGt06Command(out, command, null, serverFlag, true);
        log.info("encodeEngineCommand: 📡 Sent {} command to device {}: {}", commandPrefix, message.imei(), command);
    }

    /**
     * NEW: Location request (DWXX#)
     */
    private void encodeLocationRequest(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        String command = password != null ? 
            String.format("DWXX,%s#", password) : "DWXX#";
            
        encodeOfficialGt06Command(out, command, null, serverFlag, true);
        log.info("encodeLocationRequest: 📡 Sent location request to device {}: {}", message.imei(), command);
    }

    /**
     * NEW: Device reset (RESET#)
     */
    private void encodeDeviceReset(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        String command = password != null ? 
            String.format("RESET,%s#", password) : "RESET#";
            
        encodeOfficialGt06Command(out, command, null, serverFlag, true);
        log.info("encodeDeviceReset: 📡 Sent reset command to device {}: {}", message.imei(), command);
    }

    /**
     * NEW: Status query (STATUS#)
     */
    private void encodeStatusQuery(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        encodeOfficialGt06Command(out, "STATUS#", null, serverFlag, true);
        log.info("encodeStatusQuery: 📡 Sent status query to device {}", message.imei());
    }

    /**
     * NEW: Timer configuration (TIMER,T1,T2#)
     */
    private void encodeTimerConfig(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var accOnInterval = getIntValue(data, "accOnInterval", 30);
        var accOffInterval = getIntValue(data, "accOffInterval", 300);
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        String command = String.format("TIMER,%d,%d#", accOnInterval, accOffInterval);
        if (password != null) {
            command = String.format("TIMER,%d,%d,%s#", accOnInterval, accOffInterval, password);
        }
        
        encodeOfficialGt06Command(out, command, null, serverFlag, true);
        log.info("encodeTimerConfig: 📡 Sent timer config to device {}: ACC_ON={}s, ACC_OFF={}s", 
                message.imei(), accOnInterval, accOffInterval);
    }

    /**
     * NEW: Server configuration (SERVER,0,IP,PORT,0#)
     */
    private void encodeServerConfig(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serverIp = getStringValue(data, "serverIp", "");
        var serverPort = getIntValue(data, "serverPort", 5023);
        var password = getStringValue(data, "password", null);
        var serverFlag = getIntValue(data, "serverFlag", 1);
        
        if (serverIp.isEmpty()) {
            log.warn("⚠️ Empty server IP for device: {}", message.imei());
            return;
        }
        
        String command = String.format("SERVER,0,%s,%d,0#", serverIp, serverPort);
        if (password != null) {
            command = String.format("SERVER,0,%s,%d,0,%s#", serverIp, serverPort, password);
        }
        
        encodeOfficialGt06Command(out, command, null, serverFlag, true);
        log.info("encodeServerConfig: 📡 Sent server config to device {}: {}:{}", message.imei(), serverIp, serverPort);
    }

    /**
     * CORE: Official GT06 command encoding with proper packet structure
     * [Start][Length][Protocol][CmdLen][ServerFlag][Command][Language][Serial][CRC][Stop]
     */
    private void encodeOfficialGt06Command(ByteBuf out, String command, String password, int serverFlag, boolean useEnglish) {
        try {
            // Prepare command content (password already embedded if needed)
            byte[] commandBytes = command.getBytes(StandardCharsets.US_ASCII);
            int languageFlag = useEnglish ? LANGUAGE_ENGLISH : LANGUAGE_CHINESE;
            
            // Calculate packet structure lengths
            int commandLength = 4 + commandBytes.length + 2; // ServerFlag(4) + Command + Language(2)
            int packetLength = 1 + 1 + commandLength + 2 + 2; // Protocol(1) + CmdLen(1) + Content + Serial(2) + CRC(2)
            
            log.debug("Encoding GT06 command: '{}', ServerFlag: 0x{}, Language: {}, PacketLen: {}", command, Integer.toHexString(serverFlag), useEnglish ? "EN" : "CN", packetLength);
            
            // Write packet following official GT06 structure
            out.writeBytes(START_BITS);                     // Start Bit: 0x7878
            out.writeByte(packetLength);                    // Packet Length
            out.writeByte(PROTOCOL_COMMAND);                // Protocol Number: 0x80
            out.writeByte(commandLength);                   // Length of Command
            out.writeInt(serverFlag);                       // Server Flag Bit (4 bytes)
            out.writeBytes(commandBytes);                   // Command Content (ASCII)
            out.writeShort(languageFlag);                   // Language: 0x0001/0x0002
            
            int currentSerial = getNextSerial();
            out.writeShort(currentSerial);                  // Information Serial Number
            
            // Calculate CRC-ITU for packet (from Packet Length to Serial Number inclusive)
            int crcStartPos = 2; // Start after start bits
            int crcLength = packetLength - 2; // Exclude CRC bytes themselves
            int crc = calculateCrcItu(out, crcStartPos, crcLength);
            
            out.writeShort(crc);                            // Error Check: CRC-ITU
            out.writeBytes(END_BITS);                       // Stop Bit: 0x0D0A
            
            log.debug("encodeOfficialGt06Command: GT06 packet encoded. Size: {} bytes, Serial: {}, CRC: 0x{}", out.readableBytes(), currentSerial, Integer.toHexString(crc).toUpperCase());
                     
        } catch (Exception e) {
            log.error("Failed to encode GT06 command '{}': {}", command, e.getMessage(), e);
            throw new RuntimeException("GT06 command encoding failed", e);
        }
    }

    /**
     * Calculate CRC-ITU checksum using official lookup table
     */
    private int calculateCrcItu(ByteBuf buffer, int start, int length) {
        int fcs = 0xFFFF; // Initialize to 0xFFFF as per GT06 specification
        int savedReaderIndex = buffer.readerIndex();
        
        try {
            buffer.readerIndex(start);
            for (int i = 0; i < length; i++) {
                int data = buffer.readUnsignedByte();
                fcs = (fcs >> 8) ^ CRC_TABLE[(fcs ^ data) & 0xFF];
            }
            return (~fcs) & 0xFFFF; // Return negated result
            
        } finally {
            buffer.readerIndex(savedReaderIndex);
        }
    }

    /**
     * Get next serial number in thread-safe manner
     */
    private int getNextSerial() {
        int current = serialNumber.getAndIncrement();
        if (current > 0xFFFF) {
            serialNumber.set(1);
            return 1;
        }
        return current;
    }

    // ===== EXISTING METHODS (Maintain Compatibility) =====

    private void encodeLoginResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0001);
        writeFrame(out, PROTOCOL_LOGIN_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    private void encodeGpsResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0002);
        writeFrame(out, PROTOCOL_GPS_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    private void encodeHeartbeatResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0003);
        writeFrame(out, PROTOCOL_HEARTBEAT_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    private void encodeStringResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0004);
        writeFrame(out, PROTOCOL_STRING_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    private void encodeGenericResponse(DeviceMessage message, ByteBuf out) {
        var data = message.data();
        var serialNumber = getIntValue(data, "serialNumber", 0x0000);
        writeFrame(out, PROTOCOL_LOGIN_RESPONSE, buffer -> {
            buffer.writeShort(serialNumber);
        });
    }

    /**
     * Write GT06 frame with proper structure and CRC calculation (existing method)
     */
    private void writeFrame(ByteBuf out, byte protocolNumber, FrameContentWriter contentWriter) {
        ByteBuf contentBuffer = out.alloc().buffer();
        try {
            contentWriter.writeContent(contentBuffer);
            int contentLength = contentBuffer.readableBytes();
            int totalLength = contentLength + 2;

            out.writeBytes(START_BITS);
            out.writeByte(totalLength);
            out.writeByte(protocolNumber);
            out.writeBytes(contentBuffer);

            int crc = calculateCRC16(out, 2, totalLength + 1);
            out.writeShort(crc);
            out.writeBytes(END_BITS);
            
        } finally {
            contentBuffer.release();
        }
    }

    /**
     * Legacy CRC-16 calculation (for existing response messages)
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
            buffer.readerIndex(savedReaderIndex);
        }
    }

    // ===== UTILITY METHODS =====

    private int getIntValue(Map<String, Object> data, String key, int defaultValue) {
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

    private String getStringValue(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBooleanValue(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @FunctionalInterface
    private interface FrameContentWriter {
        void writeContent(ByteBuf buffer);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("❌ GT06 encoder error for {}: {}", 
                 ctx.channel().remoteAddress(), cause.getMessage(), cause);
        super.exceptionCaught(ctx, cause);
    }
}
