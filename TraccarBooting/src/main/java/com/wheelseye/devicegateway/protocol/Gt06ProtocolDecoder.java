package com.wheelseye.devicegateway.protocol;

import com.wheelseye.devicegateway.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production-ready GT06 Protocol Decoder following modern Java 21 and Spring Boot 3.5.5 practices.
 * 
 * Key improvements:
 * - Proper IMEI extraction and session association
 * - Comprehensive error handling and logging
 * - Modern Java patterns and clean code principles
 * - Performance optimizations with channel context caching
 */
@Component
@ChannelHandler.Sharable
public class Gt06ProtocolDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(Gt06ProtocolDecoder.class);

    // Channel attribute keys for caching
    private static final AttributeKey<String> IMEI_ATTR = AttributeKey.valueOf("DEVICE_IMEI");
    private static final AttributeKey<Instant> LAST_SEEN_ATTR = AttributeKey.valueOf("LAST_SEEN");

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) throws Exception {
        try {
            var message = parseGT06Frame(frame, ctx);
            if (message != null) {
                // Update last seen timestamp
                ctx.channel().attr(LAST_SEEN_ATTR).set(Instant.now());
                
                log.debug("✅ Decoded GT06 message: type={}, imei={} from {}", 
                        message.getType(), message.getImei(), ctx.channel().remoteAddress());
                out.add(message);
            }
        } catch (Exception e) {
            log.error("❌ Failed to decode GT06 frame from {}: {}", 
                    ctx.channel().remoteAddress(), e.getMessage(), e);
            // Don't propagate - allow connection to continue
        }
    }

    private DeviceMessage parseGT06Frame(ByteBuf frame, ChannelHandlerContext ctx) {
        if (frame.readableBytes() < 10) {
            log.debug("📦 Insufficient frame data: {} bytes", frame.readableBytes());
            return null;
        }

        var originalIndex = frame.readerIndex();
        
        try {
            // Skip start bits (0x78 0x78)
            frame.skipBytes(2);
            
            // Read packet length and protocol number
            var packetLength = frame.readUnsignedByte();
            var protocolNumber = frame.readUnsignedByte();
            
            var data = new HashMap<String, Object>();
            var timestamp = Instant.now();
            
            return switch (protocolNumber) {
                case 0x01 -> parseLoginMessage(frame, data, ctx);
                case 0x12, 0x22 -> parseLocationMessage(frame, data, ctx, "gps");
                case 0x16, 0x26 -> parseLocationMessage(frame, data, ctx, "alarm");  
                case 0x13 -> parseHeartbeatMessage(frame, data, ctx);
                case 0x15, 0x21 -> parseStringMessage(frame, data, ctx);
                case 0x1A, 0x2A -> parseAddressRequestMessage(frame, data, ctx);
                default -> parseUnknownMessage(protocolNumber, data, ctx);
            };

        } catch (Exception e) {
            log.error("❌ Error parsing GT06 frame from {}: {}", 
                    ctx.channel().remoteAddress(), e.getMessage(), e);
            return null;
        } finally {
            frame.readerIndex(originalIndex);
        }
    }

    /**
     * Parse login message and establish device session context.
     */
    private DeviceMessage parseLoginMessage(ByteBuf content, Map<String, Object> data, 
                                          ChannelHandlerContext ctx) {
        try {
            if (content.readableBytes() < 8) {
                log.warn("⚠️ Insufficient login data: {} bytes", content.readableBytes());
                return null;
            }

            // Read 8-byte Terminal ID (IMEI in BCD format)
            var terminalIdBytes = new byte[8];
            content.readBytes(terminalIdBytes);
            
            // Extract and validate IMEI
            var imei = extractIMEI(terminalIdBytes);
            if (imei == null || imei.length() != 15) {
                log.error("❌ Invalid IMEI extracted from login: {}", imei);
                return null;
            }

            // Cache IMEI in channel context for subsequent messages
            ctx.channel().attr(IMEI_ATTR).set(imei);
            
            // Parse additional login data
            if (content.readableBytes() >= 2) {
                var typeId = content.readUnsignedShort();
                data.put("deviceType", String.format("0x%04X", typeId));
            }
            
            if (content.readableBytes() >= 2) {
                var timezoneInfo = content.readUnsignedShort();  
                data.put("timezone", parseTimezone(timezoneInfo));
            }

            data.put("loginImei", imei);
            data.put("terminalId", bytesToHex(terminalIdBytes));
            data.put("loginTime", Instant.now());

            log.info("🔐 Device login: IMEI={} from {}", imei, ctx.channel().remoteAddress());
            
            return new DeviceMessage(imei, "GT06", "login", Instant.now(), data);

        } catch (Exception e) {
            log.error("❌ Error parsing login message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse location messages with proper IMEI association.
     */
    private DeviceMessage parseLocationMessage(ByteBuf content, Map<String, Object> data,
                                             ChannelHandlerContext ctx, String messageType) {
        try {
            var imei = getImeiFromContext(ctx);
            if (imei == null) {
                log.warn("⚠️ No IMEI in context for location message from {}", 
                        ctx.channel().remoteAddress());
                return null;
            }

            if (content.readableBytes() < 20) {
                log.warn("⚠️ Insufficient location data: {} bytes", content.readableBytes());
                return null;
            }

            // Parse GPS timestamp (6 bytes: YY MM DD HH MM SS)
            var gpsTime = parseDateTime(content);
            var timestamp = gpsTime.toInstant(ZoneOffset.UTC);
            data.put("gpsTimestamp", timestamp);

            // Parse GPS info and satellite count
            var gpsInfo = content.readUnsignedByte();
            var satelliteCount = gpsInfo & 0x0F;
            var gpsPositioned = (gpsInfo & 0x10) != 0;
            
            // Parse coordinates (4 bytes each) 
            var latitude = parseCoordinate(content.readInt());
            var longitude = parseCoordinate(content.readInt());

            // Parse speed and course
            var speed = content.readUnsignedByte(); // km/h
            var courseStatus = content.readUnsignedShort();
            var course = courseStatus & 0x3FF; // 10 bits for course
            var gpsFixed = (courseStatus & 0x1000) != 0;

            // Populate location data
            data.put("latitude", latitude);
            data.put("longitude", longitude);
            data.put("speed", speed);
            data.put("course", course);
            data.put("satelliteCount", satelliteCount);
            data.put("gpsPositioned", gpsPositioned);
            data.put("gpsFixed", gpsFixed);
            data.put("altitude", 0.0); // GT06 doesn't provide altitude

            // Parse additional status data if available (for alarm messages)
            if ("alarm".equals(messageType) && content.readableBytes() >= 5) {
                parseStatusInfo(content, data);
            }

            log.info("📍 Location data: {} -> [{:.6f}, {:.6f}] speed={}km/h satellites={}", 
                    imei, latitude, longitude, speed, satelliteCount);

            return new DeviceMessage(imei, "GT06", messageType, timestamp, data);

        } catch (Exception e) {
            log.error("❌ Error parsing location message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse heartbeat/status messages.
     */
    private DeviceMessage parseHeartbeatMessage(ByteBuf content, Map<String, Object> data,
                                              ChannelHandlerContext ctx) {
        try {
            var imei = getImeiFromContext(ctx);
            if (imei == null) {
                log.warn("⚠️ No IMEI in context for heartbeat from {}", 
                        ctx.channel().remoteAddress());
                return null;
            }

            // Parse status information if available
            if (content.readableBytes() >= 5) {
                parseStatusInfo(content, data);
            }

            log.debug("💓 Heartbeat from device: {}", imei);
            
            return new DeviceMessage(imei, "GT06", "heartbeat", Instant.now(), data);

        } catch (Exception e) {
            log.error("❌ Error parsing heartbeat message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse string information messages.
     */
    private DeviceMessage parseStringMessage(ByteBuf content, Map<String, Object> data,
                                           ChannelHandlerContext ctx) {
        try {
            var imei = getImeiFromContext(ctx);
            if (imei == null) {
                return null;
            }

            if (content.readableBytes() > 0) {
                var stringBytes = new byte[content.readableBytes()];
                content.readBytes(stringBytes);
                var stringContent = new String(stringBytes, java.nio.charset.StandardCharsets.UTF_8);
                data.put("content", stringContent.trim());
            }

            return new DeviceMessage(imei, "GT06", "string", Instant.now(), data);

        } catch (Exception e) {
            log.error("❌ Error parsing string message: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse GPS address request messages.
     */
    private DeviceMessage parseAddressRequestMessage(ByteBuf content, Map<String, Object> data,
                                                   ChannelHandlerContext ctx) {
        try {
            var imei = getImeiFromContext(ctx);
            if (imei == null) {
                return null;
            }

            // Parse location data first
            parseLocationMessage(content, data, ctx, "gps_address");

            // Parse phone number if available
            if (content.readableBytes() >= 21) {
                var phoneBytes = new byte[21];
                content.readBytes(phoneBytes);
                var phoneNumber = new String(phoneBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
                data.put("phoneNumber", phoneNumber);
            }

            return new DeviceMessage(imei, "GT06", "gps_address", Instant.now(), data);

        } catch (Exception e) {
            log.error("❌ Error parsing address request: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Handle unknown protocol messages.
     */
    private DeviceMessage parseUnknownMessage(int protocolNumber, Map<String, Object> data,
                                            ChannelHandlerContext ctx) {
        var imei = getImeiFromContext(ctx);
        if (imei == null) {
            imei = "UNKNOWN_" + ctx.channel().id().asShortText();
        }

        data.put("protocolNumber", String.format("0x%02X", protocolNumber));
        log.warn("❓ Unknown protocol message: 0x{:02X} from device {}", protocolNumber, imei);
        
        return new DeviceMessage(imei, "GT06", "unknown", Instant.now(), data);
    }

    // Helper methods

    /**
     * Get IMEI from channel context with fallback.
     */
    private String getImeiFromContext(ChannelHandlerContext ctx) {
        return ctx.channel().attr(IMEI_ATTR).get();
    }

    /**
     * Extract IMEI from 8-byte BCD format according to GT06 specification.
     */
    private String extractIMEI(byte[] imeiBytes) {
        try {
            if (imeiBytes == null || imeiBytes.length != 8) {
                return null;
            }

            var imei = new StringBuilder(15);
            
            for (var b : imeiBytes) {
                var unsignedByte = b & 0xFF;
                var high = (unsignedByte >>> 4) & 0x0F;
                var low = unsignedByte & 0x0F;
                
                // Add high nibble if valid
                if (high <= 9) {
                    imei.append((char)('0' + high));
                } else if (high != 0x0F) { // Skip padding
                    return null;
                }
                
                // Add low nibble if valid  
                if (low <= 9) {
                    imei.append((char)('0' + low));
                } else if (low != 0x0F) { // Skip padding
                    return null;
                }
            }

            var result = imei.toString();
            
            // Handle 16-digit case (remove leading zero)
            if (result.length() == 16 && result.charAt(0) == '0') {
                result = result.substring(1);
            }

            // Validate final IMEI
            if (result.length() == 15 && result.chars().allMatch(Character::isDigit)) {
                return result;
            }

            return null;

        } catch (Exception e) {
            log.error("❌ IMEI extraction failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Parse date/time from GPS data.
     */
    private LocalDateTime parseDateTime(ByteBuf content) {
        var year = 2000 + content.readUnsignedByte();
        var month = content.readUnsignedByte();
        var day = content.readUnsignedByte();
        var hour = content.readUnsignedByte();
        var minute = content.readUnsignedByte();
        var second = content.readUnsignedByte();
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    /**
     * Parse coordinate from GT06 format.
     */
    private double parseCoordinate(int rawValue) {
        // GT06 coordinate conversion: GPS decimal minutes * 30000
        return rawValue / 1800000.0;
    }

    /**
     * Parse status information from device.
     */
    private void parseStatusInfo(ByteBuf content, Map<String, Object> data) {
        if (content.readableBytes() >= 5) {
            var terminalInfo = content.readUnsignedByte();
            var voltageLevel = content.readUnsignedByte();
            var gsmSignal = content.readUnsignedByte();
            var alarmLanguage = content.readUnsignedShort();
            
            data.put("charging", (terminalInfo & 0x04) != 0);
            data.put("ignition", (terminalInfo & 0x02) != 0);
            data.put("voltageLevel", voltageLevel);
            data.put("gsmSignalStrength", gsmSignal);
            data.put("alarmStatus", (alarmLanguage >> 8) & 0xFF);
            data.put("language", (alarmLanguage & 0xFF) == 1 ? "Chinese" : "English");
        }
    }

    /**
     * Parse timezone information.
     */
    private String parseTimezone(int timezoneInfo) {
        // Simple timezone parsing - can be enhanced based on specific requirements
        var offsetHours = (timezoneInfo >> 8) & 0xFF;
        var offsetMinutes = timezoneInfo & 0xFF;
        return String.format("GMT%+03d:%02d", offsetHours - 12, offsetMinutes);
    }

    /**
     * Convert byte array to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        var result = new StringBuilder();
        for (var b : bytes) {
            result.append(String.format("%02X", b & 0xFF));
        }
        return result.toString();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("❌ GT06 decoder error from {}: {}", 
                ctx.channel().remoteAddress(), cause.getMessage(), cause);
        super.exceptionCaught(ctx, cause);
    }
}