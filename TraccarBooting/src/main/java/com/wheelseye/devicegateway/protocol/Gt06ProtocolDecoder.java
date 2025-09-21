package com.wheelseye.devicegateway.protocol;

import com.wheelseye.devicegateway.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GT06 Protocol Decoder - FINAL CORRECT IMPLEMENTATION
 * 
 * Based on official GT06 specification section 5.1.1.4:
 * "The terminal ID applies IMEI number of 15 bits. Example: if the IMEI is 123456789012345, 
 * the terminal ID is 0x01 0x23 0x45 0x67 0x89 0x01 0x23 0x45."
 * 
 * Fixed the BCD decoding to exactly match the specification format.
 */
@ChannelHandler.Sharable
public class Gt06ProtocolDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger logger = LoggerFactory.getLogger(Gt06ProtocolDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) throws Exception {
        try {
            final DeviceMessage message = parseGT06Frame(frame, ctx);
            if (message != null) {
                logger.debug("Decoded GT06 message: type={}, imei={} from {}",
                    message.getType(), message.getImei(), ctx.channel().remoteAddress());
                out.add(message);
            }
        } catch (Exception e) {
            logger.error("Failed to decode GT06 frame from {}: {}", 
                ctx.channel().remoteAddress(), e.getMessage(), e);
        }
    }

    private DeviceMessage parseGT06Frame(ByteBuf frame, ChannelHandlerContext ctx) {
        if (frame.readableBytes() < 10) {
            return null;
        }

        final int originalIndex = frame.readerIndex();
        
        try {
            // Skip start bits (already validated by frame decoder)
            frame.skipBytes(2);
            
            // Read packet length
            final int packetLength = frame.readUnsignedByte();
            
            // Read protocol number
            final int protocolNumber = frame.readUnsignedByte();
            
            String imei = null;
            String type = "unknown";
            Instant timestamp = Instant.now();
            Map<String, Object> data = new HashMap<>();

            // Parse based on protocol number
            switch (protocolNumber) {
                case 0x01 -> {
                    // Login message - extract IMEI from 8-byte BCD Terminal ID
                    type = "login";
                    imei = parseLoginMessage(frame, data);
                }
                case 0x12, 0x22 -> {
                    // GPS location data  
                    type = "gps";
                    imei = "GPS_DEVICE";
                    parseLocationData(frame, data, timestamp);
                }
                case 0x13 -> {
                    // Status/Heartbeat
                    type = "heartbeat"; 
                    imei = "STATUS_DEVICE";
                    parseStatusData(frame, data);
                }
                case 0x15, 0x21 -> {
                    // String information
                    type = "string";
                    imei = "STRING_DEVICE";
                    parseStringInfo(frame, data);
                }
                case 0x16, 0x26 -> {
                    // Alarm data (location + status)
                    type = "alarm";
                    imei = "ALARM_DEVICE";
                    parseAlarmData(frame, data, timestamp);
                }
                case 0x1A, 0x2A -> {
                    // GPS address query
                    type = "gps_address";
                    imei = "ADDRESS_DEVICE";
                    parseGpsAddressRequest(frame, data);
                }
                default -> {
                    type = "unknown";
                    imei = "UNKNOWN_DEVICE";
                    data.put("protocolNumber", String.format("0x%02X", protocolNumber));
                }
            }

            // Use extracted IMEI if valid, otherwise use placeholder
            if (imei == null || imei.isEmpty() || imei.startsWith("DEVICE_") || imei.startsWith("UNKNOWN")) {
                imei = "DEVICE_" + String.format("%02X", protocolNumber);
            }

            return new DeviceMessage(imei, "GT06", type, timestamp, data);

        } catch (Exception e) {
            logger.error("Error parsing GT06 frame from {}: {}", 
                ctx.channel().remoteAddress(), e.getMessage(), e);
            return null;
        } finally {
            frame.readerIndex(originalIndex);
        }
    }

    /**
     * Parse login message and extract IMEI from BCD format Terminal ID
     * 
     * Based on GT06 specification section 5.1.1.4
     */
    private String parseLoginMessage(ByteBuf content, Map<String, Object> data) {
        try {
            if (content.readableBytes() < 8) {
                logger.warn("Insufficient data for login message: {} bytes", content.readableBytes());
                return null;
            }

            // Read 8-byte Terminal ID (IMEI in BCD format)
            final byte[] terminalIdBytes = new byte[8];
            content.readBytes(terminalIdBytes);
            
            // Extract IMEI using corrected BCD decoding based on GT06 specification
            final String imei = extractIMEI(terminalIdBytes);
            
            if (imei != null) {
                data.put("loginImei", imei);
                data.put("terminalId", bytesToHex(terminalIdBytes));
                
                logger.info("📱 Successfully extracted IMEI from login: {}", imei);
                return imei;
            } else {
                logger.warn("Failed to extract IMEI from terminal ID: {}", bytesToHex(terminalIdBytes));
            }
            
            // Read additional login data if available
            if (content.readableBytes() >= 2) {
                final int typeId = content.readUnsignedShort();
                data.put("typeId", String.format("0x%04X", typeId));
            }
            
            if (content.readableBytes() >= 2) {
                final int timezoneInfo = content.readUnsignedShort();
                data.put("timezoneInfo", String.format("0x%04X", timezoneInfo));
            }

            return imei;
            
        } catch (Exception e) {
            logger.error("Error parsing login message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract IMEI from 8-byte BCD format according to GT06 specification
     * 
     * From GT06 spec: "The terminal ID applies IMEI number of 15 bits. 
     * Example: if the IMEI is 123456789012345, the terminal ID is 0x01 0x23 0x45 0x67 0x89 0x01 0x23 0x45."
     * 
     * This shows that BCD encoding packs 2 digits per byte with proper nibble order.
     */
    private String extractIMEI(byte[] imeiBytes) {
        try {
            if (imeiBytes == null || imeiBytes.length != 8) {
                logger.warn("Invalid IMEI bytes: length={}", imeiBytes != null ? imeiBytes.length : 0);
                return null;
            }

            // Log the raw bytes for debugging
            logger.debug("Raw IMEI bytes: {}", bytesToHex(imeiBytes));

            final StringBuilder imei = new StringBuilder(15);
            
            // Process each byte to extract BCD digits according to GT06 specification
            for (int i = 0; i < imeiBytes.length; i++) {
                final int b = imeiBytes[i] & 0xFF; // Convert to unsigned
                final int high = (b >>> 4) & 0x0F;  // High nibble (first digit)
                final int low = b & 0x0F;           // Low nibble (second digit)
                
                logger.trace("Byte {}: 0x{:02X} -> high={}, low={}", i, b, high, low);
                
                // Add high nibble digit if valid (0x0F is padding in some formats)
                if (high <= 9) {
                    imei.append((char)('0' + high));
                } else if (high == 0x0F) {
                    // 0x0F is padding - skip it
                    logger.trace("Skipping high nibble padding 0x0F at byte {}", i);
                } else {
                    logger.warn("Invalid high nibble {} at byte {}", high, i);
                    return null;
                }
                
                // Add low nibble digit if valid (0x0F is padding in some formats)
                if (low <= 9) {
                    imei.append((char)('0' + low));
                } else if (low == 0x0F) {
                    // 0x0F is padding - skip it
                    logger.trace("Skipping low nibble padding 0x0F at byte {}", i);
                } else {
                    logger.warn("Invalid low nibble {} at byte {}", low, i);
                    return null;
                }
            }

            String result = imei.toString();
            logger.debug("Decoded IMEI before validation: '{}' (length={})", result, result.length());

            // Handle 16-digit case (remove leading zero) as per GT06 examples
            if (result.length() == 16 && result.charAt(0) == '0') {
                result = result.substring(1);
                logger.debug("Removed leading zero: '{}' (length={})", result, result.length());
            }

            // Validate final IMEI - must be exactly 15 digits, all numeric
            if (result.length() == 15) {
                // Check each character is a digit
                for (int i = 0; i < result.length(); i++) {
                    char c = result.charAt(i);
                    if (c < '0' || c > '9') {
                        logger.error("Invalid character '{}' (code={}) at position {} in IMEI: '{}'", 
                            c, (int)c, i, result);
                        return null;
                    }
                }
                
                logger.info("✅ Successfully decoded and validated IMEI: {} from BCD: {}", 
                    result, bytesToHex(imeiBytes));
                return result;
            } else {
                logger.error("Invalid IMEI length after BCD decoding: {} (expected 15), IMEI: '{}', raw: {}", 
                    result.length(), result, bytesToHex(imeiBytes));
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Exception during IMEI extraction from {}: {}", 
                bytesToHex(imeiBytes), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse GPS location data
     */
    private void parseLocationData(ByteBuf content, Map<String, Object> data, Instant timestamp) {
        try {
            if (content.readableBytes() < 20) {
                return;
            }

            // Parse date/time (6 bytes: YY MM DD HH MM SS)
            final LocalDateTime dateTime = parseDateTime(content);
            data.put("timestamp", dateTime.toInstant(ZoneOffset.UTC));
            
            // Parse GPS info and satellite count (1 byte)
            final int gpsInfo = content.readUnsignedByte();
            final int satelliteCount = gpsInfo & 0x0F;
            data.put("satelliteCount", satelliteCount);
            
            // Parse coordinates (4 bytes each)
            final double latitude = parseCoordinate(content.readInt());
            final double longitude = parseCoordinate(content.readInt());
            data.put("latitude", latitude);
            data.put("longitude", longitude);
            
            // Parse speed and course
            final int speed = content.readUnsignedByte();
            final int courseStatus = content.readUnsignedShort();
            data.put("speed", speed);
            data.put("course", courseStatus & 0x3FF);
            data.put("gpsPositioned", (courseStatus & 0x1000) != 0);
            
            logger.debug("GPS parsed: lat={}, lon={}, speed={} km/h", latitude, longitude, speed);
            
        } catch (Exception e) {
            logger.error("Error parsing GPS data: {}", e.getMessage());
        }
    }

    /**
     * Parse status/heartbeat data
     */
    private void parseStatusData(ByteBuf content, Map<String, Object> data) {
        try {
            if (content.readableBytes() >= 5) {
                final int terminalInfo = content.readUnsignedByte();
                final int voltageLevel = content.readUnsignedByte(); 
                final int gsmSignal = content.readUnsignedByte();
                final int alarmLanguage = content.readUnsignedShort();
                
                data.put("terminalInfo", terminalInfo);
                data.put("voltageLevel", voltageLevel);
                data.put("gsmSignalStrength", gsmSignal);
                data.put("charging", (terminalInfo & 0x04) != 0);
                data.put("alarmStatus", (alarmLanguage >> 8) & 0xFF);
                data.put("language", (alarmLanguage & 0xFF) == 1 ? "Chinese" : "English");
                
                logger.debug("Status parsed: voltage={}, gsm={}, charging={}", 
                    voltageLevel, gsmSignal, (terminalInfo & 0x04) != 0);
            }
        } catch (Exception e) {
            logger.error("Error parsing status data: {}", e.getMessage());
        }
    }

    /**
     * Parse alarm data (GPS + status information)
     */
    private void parseAlarmData(ByteBuf content, Map<String, Object> data, Instant timestamp) {
        try {
            // Parse GPS data first
            parseLocationData(content, data, timestamp);
            
            // Then parse status information
            parseStatusData(content, data);
            
            data.put("isAlarm", true);
            logger.warn("Alarm data received");
            
        } catch (Exception e) {
            logger.error("Error parsing alarm data: {}", e.getMessage());
        }
    }

    /**
     * Parse string information
     */
    private void parseStringInfo(ByteBuf content, Map<String, Object> data) {
        try {
            if (content.readableBytes() > 0) {
                final byte[] stringBytes = new byte[content.readableBytes()];
                content.readBytes(stringBytes);
                final String stringContent = new String(stringBytes, java.nio.charset.StandardCharsets.UTF_8);
                data.put("stringContent", stringContent.trim());
            }
        } catch (Exception e) {
            logger.error("Error parsing string info: {}", e.getMessage());
        }
    }

    /**
     * Parse GPS address request
     */
    private void parseGpsAddressRequest(ByteBuf content, Map<String, Object> data) {
        try {
            // Contains GPS data + phone number
            parseLocationData(content, data, Instant.now());
            
            if (content.readableBytes() >= 21) {
                final byte[] phoneBytes = new byte[21];
                content.readBytes(phoneBytes);
                final String phoneNumber = new String(phoneBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                data.put("phoneNumber", phoneNumber);
            }
        } catch (Exception e) {
            logger.error("Error parsing GPS address request: {}", e.getMessage());
        }
    }

    // Helper methods
    
    private LocalDateTime parseDateTime(ByteBuf content) {
        final int year = 2000 + content.readUnsignedByte();
        final int month = content.readUnsignedByte();
        final int day = content.readUnsignedByte();
        final int hour = content.readUnsignedByte();
        final int minute = content.readUnsignedByte();
        final int second = content.readUnsignedByte();
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    private double parseCoordinate(int rawValue) {
        // GT06 coordinate conversion: GPS decimal minutes * 30000
        return rawValue / 1800000.0;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        final StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b & 0xFF));
        }
        return result.toString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("GT06 protocol decoder error from {}: {}", 
            ctx.channel().remoteAddress(), cause.getMessage(), cause);
        super.exceptionCaught(ctx, cause);
    }
}