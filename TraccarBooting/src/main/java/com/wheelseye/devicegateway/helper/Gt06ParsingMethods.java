package com.wheelseye.devicegateway.helper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.wheelseye.devicegateway.dto.DeviceStatusDto;
import com.wheelseye.devicegateway.dto.AlarmStatusDto;
import com.wheelseye.devicegateway.dto.DeviceExtendedFeatureDto;
import com.wheelseye.devicegateway.dto.DeviceIOPortsDto;
import com.wheelseye.devicegateway.dto.DeviceLbsDataDto;
import com.wheelseye.devicegateway.dto.LocationDto;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.model.MessageFrame;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

@Component
public class Gt06ParsingMethods {

    private static final int HEADER_78 = 0x7878;
    private static final int HEADER_79 = 0x7979;

    // Offsets
    private static final int COURSE_STATUS_OFFSET = 17;
    private static final int IO_OFFSET = 20;

    // Battery calculation
    private static final int VOLTAGE_MIN = 3400;
    private static final int VOLTAGE_MAX = 4200;

    @Autowired
    private DeviceSessionService sessionService;

    private static final Logger logger = LoggerFactory.getLogger(Gt06ParsingMethods.class);

    public MessageFrame parseFrame(ByteBuf buffer) {
        try {
            if (buffer.readableBytes() < 5) {
                logger.debug("Insufficient bytes for frame parsing: {}", buffer.readableBytes());
                return null;
            }

            // Store original reader index
            int originalIndex = buffer.readerIndex();

            // Read header
            int startBits = buffer.readUnsignedShort();
            boolean isExtended = (startBits == HEADER_79);

            // Read length
            int length;
            if (isExtended) {
                if (buffer.readableBytes() < 2) {
                    buffer.readerIndex(originalIndex);
                    return null;
                }
                length = buffer.readUnsignedShort();
            } else {
                if (buffer.readableBytes() < 1) {
                    buffer.readerIndex(originalIndex);
                    return null;
                }
                length = buffer.readUnsignedByte();
            }

            // Validate length
            if (length < 1 || length > 1000) {
                logger.debug("Invalid data length: {}", length);
                buffer.readerIndex(originalIndex);
                return null;
            }

            // Check if we have enough data
            int remainingForContent = length - 4; // length includes protocol, serial, and CRC
            if (buffer.readableBytes() < remainingForContent + 4) { // +4 for serial(2) + crc(2)
                buffer.readerIndex(originalIndex);
                return null;
            }

            // Read protocol number
            int protocolNumber = buffer.readUnsignedByte();

            // Read content (remaining data except serial and CRC)
            ByteBuf content = Unpooled.buffer();
            int contentLength = remainingForContent - 1; // -1 for protocol number
            if (contentLength > 0) {
                content.writeBytes(buffer, contentLength);
            }

            // Read serial number
            int serialNumber = buffer.readUnsignedShort();

            // Read CRC
            int crc = buffer.readUnsignedShort();

            // Read stop bits (if available)
            int stopBits = 0x0D0A; // Default
            if (buffer.readableBytes() >= 2) {
                stopBits = buffer.readUnsignedShort();
            }

            // Create hex dump for debugging
            buffer.readerIndex(originalIndex);
            String rawHex = "";
            if (buffer.readableBytes() >= 8) {
                byte[] hexBytes = new byte[Math.min(buffer.readableBytes(), 32)];
                buffer.getBytes(buffer.readerIndex(), hexBytes);
                rawHex = bytesToHex(hexBytes);
            }

            logger.debug("Parsed frame: startBits=0x{:04X}, length={}, protocol=0x{:02X}, serial={}, crc=0x{:04X}",
                    startBits, length, protocolNumber, serialNumber, crc);

            // return new MessageFrame(startBits, length, protocolNumber, content,
            // serialNumber, crc, stopBits, rawHex);

            return new MessageFrame(
                    startBits,
                    length,
                    protocolNumber,
                    content,
                    serialNumber,
                    crc,
                    stopBits,
                    rawHex,
                    Instant.now(), // receivedAt
                    null // imei
            );

        } catch (Exception e) {
            logger.error("Error parsing GT06 frame: {}", e.getMessage(), e);
            return null;
        }
    }

    public IMEI extractIMEI(MessageFrame frame) {
        try {
            ByteBuf content = frame.content();

            if (content.readableBytes() < 8) {
                logger.warn("Insufficient bytes for IMEI extraction: {}", content.readableBytes());
                return null;
            }

            // Read 8 bytes for BCD-encoded IMEI
            byte[] imeiBytes = new byte[8];
            content.readBytes(imeiBytes);

            // PROPER BCD DECODING
            StringBuilder imei = new StringBuilder();
            for (byte b : imeiBytes) {
                // Each byte contains two BCD digits
                int highNibble = (b >> 4) & 0x0F;
                int lowNibble = b & 0x0F;

                // Validate BCD digits (0-9)
                if (highNibble > 9 || lowNibble > 9) {
                    logger.warn("Invalid BCD digit in IMEI: high={}, low={}", highNibble, lowNibble);
                    return null;
                }

                imei.append(highNibble).append(lowNibble);
            }

            // Process the decoded IMEI string
            String imeiStr = imei.toString();
            logger.debug("Raw BCD decoded IMEI: '{}' (length: {})", imeiStr, imeiStr.length());

            // Handle leading zero (common in GT06 protocol)
            if (imeiStr.startsWith("0") && imeiStr.length() == 16) {
                imeiStr = imeiStr.substring(1);
                logger.debug("Removed leading zero: '{}'", imeiStr);
            }

            // Validate final IMEI format
            if (imeiStr.length() != 15) {
                logger.warn("Invalid IMEI length after processing: {} (expected 15)", imeiStr.length());
                return null;
            }

            if (!imeiStr.matches("\\d{15}")) {
                logger.warn("IMEI contains non-digit characters: '{}'", imeiStr);
                return null;
            }

            logger.info("Successfully extracted IMEI: {}", imeiStr);
            return new IMEI(imeiStr);

        } catch (Exception e) {
            logger.error("Error extracting IMEI: {}", e.getMessage(), e);
            return null;
        }
    }

    public ByteBuf buildGenericAck(int protocolNumber, int serialNumber) {
        try {
            ByteBuf response = Unpooled.buffer();

            // Header (0x7878)
            response.writeShort(HEADER_78);

            // Length (5 bytes total content)
            response.writeByte(0x05);

            // Echo back the protocol number
            response.writeByte(protocolNumber);

            // Serial number (2 bytes)
            response.writeShort(serialNumber);

            // Calculate and write CRC16
            int crc = calculateCRC16(response, 2, response.writerIndex() - 2);
            response.writeShort(crc);

            // Stop bits
            response.writeByte(0x0D);
            response.writeByte(0x0A);

            logger.debug("Built generic ACK: protocol=0x{:02X}, serial={}, crc=0x{:04X}",
                    protocolNumber, serialNumber, crc);
            return response;

        } catch (Exception e) {
            logger.error("Error building generic ACK: {}", e.getMessage(), e);
            return Unpooled.buffer();
        }
    }

    private Optional<DeviceSession> getAuthenticatedSession(ChannelHandlerContext ctx) {
        try {
            Optional<DeviceSession> sessionOpt = sessionService.getSession(ctx.channel());

            if (sessionOpt.isEmpty()) {
                logger.debug("📭 No session found for channel");
                return Optional.empty();
            }

            DeviceSession session = sessionOpt.get();
            if (!session.isAuthenticated()) {
                String imei = session.getImei() != null ? session.getImei().value() : "unknown";
                logger.warn("🔐 Session NOT authenticated for IMEI: {}", imei);
                return Optional.empty();
            }

            return sessionOpt;

        } catch (Exception e) {
            logger.error("💥 Error getting authenticated session: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    public ByteBuf buildLoginAck(int serialNumber) {
        try {
            ByteBuf response = Unpooled.buffer();

            // Header (0x7878)
            response.writeShort(HEADER_78);

            // Length (5 bytes total content)
            response.writeByte(0x05);

            // Protocol number (0x01 for login ACK)
            response.writeByte(0x01);

            // Serial number (2 bytes)
            response.writeShort(serialNumber);

            // Calculate and write CRC16
            int crc = calculateCRC16(response, 2, response.writerIndex() - 2);
            response.writeShort(crc);

            // Stop bits
            response.writeByte(0x0D);
            response.writeByte(0x0A);

            logger.debug("Built login ACK: serial={}, crc=0x{:04X}", serialNumber, crc);
            return response;

        } catch (Exception e) {
            logger.error("Error building login ACK: {}", e.getMessage(), e);
            return Unpooled.buffer();
        }
    }

    private int calculateCRC16(ByteBuf buffer, int offset, int length) {
        int crc = 0xFFFF;

        for (int i = offset; i < offset + length; i++) {
            int data = buffer.getByte(i) & 0xFF;
            crc ^= data;

            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0x8408;
                } else {
                    crc >>= 1;
                }
            }
        }

        return (~crc) & 0xFFFF;
    }

    public DeviceStatusDto parseDeviceStatus(ByteBuf content) {
        try {
            content.resetReaderIndex();

            if (content.readableBytes() < COURSE_STATUS_OFFSET + 2) {
                return DeviceStatusDto.getDefaultDeviceStatus();
            }

            // Satellites
            content.skipBytes(7);
            int satellites = content.readableBytes() > 0 ? content.readUnsignedByte() : 0;

            // Course/status word
            content.resetReaderIndex();
            content.skipBytes(COURSE_STATUS_OFFSET);
            int courseStatus = content.readableBytes() >= 2 ? content.readUnsignedShort() : 0;

            boolean ignition = (courseStatus & 0x2000) != 0;
            boolean gpsFixed = (courseStatus & 0x1000) != 0;
            int direction = courseStatus & 0x03FF;
            boolean externalPower = (courseStatus & 0x4000) != 0;

            int batteryVoltage = externalPower ? (ignition ? 4200 : 4100) : (ignition ? 3900 : 3700);
            int batteryPercent = Math.max(0,
                    Math.min(100, (batteryVoltage - VOLTAGE_MIN) * 100 / (VOLTAGE_MAX - VOLTAGE_MIN)));
            boolean charging = externalPower && batteryVoltage > 4000;

            int gsmSignal = gpsFixed ? (satellites > 6 ? -65 : satellites > 4 ? -75 : -85) : -95;
            int signalLevel = Math.max(1, Math.min(5, (gsmSignal + 110) / 20));

            String batteryLevelText = getBatteryLevelText(batteryPercent);
            String voltageLevelText = getVoltageLevelText(batteryVoltage);

            return new DeviceStatusDto(ignition, ignition ? 1 : 0, gpsFixed, direction, satellites, externalPower,
                    charging, batteryVoltage, batteryPercent, batteryLevelText, voltageLevelText, gsmSignal,
                    signalLevel, courseStatus);

        } catch (Exception e) {
            logger.error("Error parsing device status: {}", e.getMessage(), e);
            return DeviceStatusDto.getDefaultDeviceStatus();
        }
    }

    public DeviceIOPortsDto parseIOPorts(ByteBuf content, double gpsSpeed) {
        try {
            content.resetReaderIndex();

            // --- Defaults ---
            String ioHex = "N/A";
            boolean input2 = false;
            String out1 = "OFF";
            String out2 = "OFF";
            Double adc1Voltage = null;
            Double adc2Voltage = null;

            // --- I/O bytes (usually at offset 20) ---
            if (content.readableBytes() > 20) {
                content.skipBytes(20);
                if (content.readableBytes() >= 4) {
                    byte[] ioBytes = new byte[4];
                    content.readBytes(ioBytes);
                    ioHex = ByteBufUtil.hexDump(ioBytes);

                    // Digital inputs
                    input2 = (ioBytes[0] & 0x02) != 0;

                    // Relay outputs
                    out1 = (ioBytes[0] & 0x04) != 0 ? "ON" : "OFF";
                    out2 = (ioBytes[0] & 0x08) != 0 ? "ON" : "OFF";

                    // ADCs scaled 0–5V
                    adc1Voltage = (ioBytes[1] & 0xFF) * 5.0 / 255.0;
                    adc2Voltage = (ioBytes[2] & 0xFF) * 5.0 / 255.0;
                }
            }

            // --- Ignition / IN1 from course/status word ---
            content.resetReaderIndex();
            boolean ignition = false;
            if (content.readableBytes() >= 19) {
                content.skipBytes(17);
                int courseStatus = content.readUnsignedShort();
                ignition = (courseStatus & 0x2000) != 0;
            }

            // --- Motion / Vibration derived from GPS speed ---
            String motion = gpsSpeed > 1.0 ? "MOVING" : "STATIONARY";

            // --- Build DTO ---
            return new DeviceIOPortsDto(ioHex, ignition, motion, input2 ? "ON" : "OFF", out1, out2, adc1Voltage,
                    adc2Voltage);

        } catch (Exception e) {
            logger.error("Error parsing I/O ports: {}", e.getMessage(), e);
            return DeviceIOPortsDto.getDefaultIOPorts();
        }
    }

    public DeviceLbsDataDto parseLBSData(ByteBuf content) {
        try {
            content.markReaderIndex();

            String lbsHex = "N/A";
            int mcc = 0;
            int mnc = 0;
            int lac = 0;
            int cid = 0;

            // LBS data starts after GPS location data (18 bytes from start in GT06)
            if (content.readableBytes() > 18) {
                content.skipBytes(18);

                if (content.readableBytes() >= 8) { // GT06 LBS is 8 bytes
                    byte[] lbsBytes = new byte[8];
                    content.readBytes(lbsBytes);
                    lbsHex = ByteBufUtil.hexDump(lbsBytes);

                    // ✅ GT06 LBS format: [MCC:2][MNC:1][LAC:2][CID:3]
                    mcc = ((lbsBytes[0] & 0xFF) << 8) | (lbsBytes[1] & 0xFF);
                    mnc = (lbsBytes[2] & 0xFF);
                    lac = ((lbsBytes[3] & 0xFF) << 8) | (lbsBytes[4] & 0xFF);
                    cid = ((lbsBytes[5] & 0xFF) << 16) | ((lbsBytes[6] & 0xFF) << 8) | (lbsBytes[7] & 0xFF);
                }
            }

            // Reset to re-read for satellites
            content.resetReaderIndex();
            int satellites = 0;
            if (content.readableBytes() > 7) {
                content.skipBytes(7);
                satellites = content.readUnsignedByte();
            }

            // Fake RSSI estimation (you may refine this later)
            int rssi = satellites > 4 ? -65 : satellites > 2 ? -75 : -85;

            return new DeviceLbsDataDto(lbsHex, mcc, mnc, lac, cid, rssi);

        } catch (Exception e) {
            logger.error("Error parsing LBS data: {}", e.getMessage(), e);
            return DeviceLbsDataDto.getDefaultDeviceLbsData();
        }
    }

    public AlarmStatusDto parseAlarms(ByteBuf content) {
        // Configurable thresholds
        final int OVERSPEED_THRESHOLD = 80; // km/h

        try {
            // 1. Mark reader index to reset after reading course/status
            content.markReaderIndex();

            // 2. Read course/status word (contains SOS, vibration, tamper, low-battery
            // bits)
            String alarmHex = "0000";
            int courseStatus = 0;
            if (content.readableBytes() >= 19) {
                content.skipBytes(17);
                courseStatus = content.readUnsignedShort();
                alarmHex = String.format("%04X", courseStatus);
            }

            // 3. Decode supported alarm flags
            boolean sosAlarm = (courseStatus & 0x0004) != 0;
            boolean vibrationAlarm = (courseStatus & 0x0008) != 0;
            boolean tamperAlarm = (courseStatus & 0x0020) != 0;
            boolean lowBatteryAlarm = (courseStatus & 0x0040) != 0;

            // 4. Reset and read speed for overspeed and idle detection
            content.resetReaderIndex();
            int speed = 0;
            if (content.readableBytes() >= 17) {
                content.skipBytes(16);
                speed = content.readUnsignedByte();
            }
            boolean overSpeedAlarm = speed > OVERSPEED_THRESHOLD;
            boolean ignition = (courseStatus & 0x2000) != 0;
            boolean idleAlarm = ignition && speed == 0;

            // 5. Build and return DTO with only GT06-supported alarms
            return new AlarmStatusDto(alarmHex, sosAlarm, vibrationAlarm, tamperAlarm, lowBatteryAlarm, overSpeedAlarm,
                    idleAlarm);
        } catch (Exception e) {
            logger.error("Error parsing GT06 alarm data: {}", e.getMessage(), e);
            return AlarmStatusDto.getDefaultStatus();
        }
    }

    public DeviceExtendedFeatureDto parseExtendedFeatures(ByteBuf content) {
        try {
            // 1. Reset reader index and skip to feature field
            content.resetReaderIndex();
            String featureHex = "0000";
            if (content.readableBytes() > 24) {
                content.skipBytes(24);
                if (content.readableBytes() >= 2) {
                    byte[] featureBytes = new byte[2];
                    content.readBytes(featureBytes);
                    featureHex = ByteBufUtil.hexDump(Unpooled.wrappedBuffer(featureBytes));
                }
            }

            // 2. Default/configurable values for supported GT06/GT06N features
            boolean smsCommands = true; // Always supported
            int uploadInterval = 30; // seconds
            int distanceUpload = 200; // meters
            int heartbeatInterval = 300; // seconds
            int cellScanCount = 1; // basic model
            String backupMode = "SMS"; // fallback mode
            boolean sleepMode = false; // basic model does not sleep

            // 3. Construct and return DTO with only supported fields
            return new DeviceExtendedFeatureDto(featureHex, smsCommands, uploadInterval, distanceUpload,
                    heartbeatInterval, cellScanCount, backupMode, sleepMode);
        } catch (Exception e) {
            logger.error("Error parsing GT06/GT06N extended features: {}", e.getMessage(), e);
            return DeviceExtendedFeatureDto.getDefaultFeatures();
        }
    }

    private String getBatteryLevelText(int batteryPercent) {
        if (batteryPercent > 80)
            return "Full";
        if (batteryPercent > 60)
            return "High";
        if (batteryPercent > 40)
            return "Medium";
        if (batteryPercent > 20)
            return "Low";
        return "Critical";
    }

    private String getVoltageLevelText(int voltage) {
        if (voltage > 4100)
            return "Excellent";
        if (voltage > 3900)
            return "Good";
        if (voltage > 3700)
            return "Normal";
        if (voltage > 3500)
            return "Weak";
        return "Very Weak";
    }

    public String getOperatorName(int mcc, int mnc) {
        if (mcc == 404) { // India
            return switch (mnc) {
                case 1, 2, 3 -> "Airtel";
                case 10, 11, 12 -> "Airtel";
                case 20, 21, 22 -> "Vodafone";
                case 27, 28, 29 -> "Vodafone";
                case 40, 41, 42 -> "Jio";
                case 43, 44, 45 -> "Jio";
                case 70, 71, 72 -> "BSNL";
                default -> "Unknown IN";
            };
        }
        return "Unknown";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

}
