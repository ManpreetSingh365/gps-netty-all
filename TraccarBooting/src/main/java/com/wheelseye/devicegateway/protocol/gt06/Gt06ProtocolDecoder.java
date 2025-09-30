package com.wheelseye.devicegateway.protocol.gt06;

import com.wheelseye.devicegateway.dto.LocationDto;
import com.wheelseye.devicegateway.model.DeviceMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GT06 Protocol Decoder - Concise Lombok Implementation
 * 
 * Decodes GT06 GPS protocol messages with proper coordinate parsing.
 * Fixed ClassCastException issues with explicit type handling.
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class Gt06ProtocolDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final AttributeKey<String> IMEI_ATTR = AttributeKey.valueOf("DEVICE_IMEI");
    private static final AttributeKey<Instant> LAST_SEEN_ATTR = AttributeKey.valueOf("LAST_SEEN");
    private static final int MIN_FRAME_LENGTH = 10;
    private static final int START_BYTES = 2;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) {
        try {
            var message = parseGT06Frame(frame, ctx);
            if (message != null) {
                ctx.channel().attr(LAST_SEEN_ATTR).set(Instant.now());
                log.debug("✅ Decoded GT06: type={}, imei={}", message.type(), message.imei());
                out.add(message);
            }
        } catch (Exception e) {
            log.error("❌ Failed to decode GT06 frame from {}: {}", 
                    ctx.channel().remoteAddress(), e.getMessage(), e);
        }
    }

    private DeviceMessage parseGT06Frame(ByteBuf frame, ChannelHandlerContext ctx) {
        int readableBytes = frame.readableBytes();
        if (readableBytes < MIN_FRAME_LENGTH) {
            log.debug("📦 Insufficient frame data: {} bytes", readableBytes);
            return null;
        }

        // Log raw data at debug level to avoid flooding logs
        log.debug(
            "📥 RAW DATA RECEIVED | 🌐 From: {} | 📏 Length: {} bytes | 🗃️ Data: {}",
            ctx.channel().remoteAddress(), // IP:port of device
            readableBytes,     // length of ByteBuf
            ByteBufUtil.hexDump(frame) // logs as hex string
        );

        int originalIndex = frame.readerIndex();

        try {
            // Skip start bytes (0x78 0x78)
            frame.skipBytes(START_BYTES);

            int packetLength = frame.readUnsignedByte();
            if (frame.readableBytes() < packetLength) {
                log.warn("⚠️ Incomplete packet received: expected {} bytes, available {}", packetLength, frame.readableBytes());
                return null;
            }

            int protocolNumber = frame.readUnsignedByte();
            Map<String, Object> data = new HashMap<>();

            return switch (protocolNumber) {
                case 0x01 -> parseLogin(frame, data, ctx);
                case 0x12, 0x22 -> parseLocation(frame, data, ctx, "gps");
                case 0x16, 0x26 -> parseLocation(frame, data, ctx, "alarm");
                case 0x13 -> parseHeartbeat(frame, data, ctx);
                case 0x15, 0x21 -> parseString(frame, data, ctx);
                case 0x1A, 0x2A -> parseAddressRequest(frame, data, ctx);
                default -> parseUnknown(protocolNumber, data, ctx);
            };

        } catch (Exception e) {
            log.error("❌ Error parsing GT06 frame from {}: {}", ctx.channel().remoteAddress(), e.getMessage(), e);
            return null;
        } finally {
            frame.readerIndex(originalIndex);
        }
    }


    private DeviceMessage parseLogin(ByteBuf content, Map<String, Object> data, ChannelHandlerContext ctx) {
        if (content.readableBytes() < 8) return null;

        var terminalIdBytes = new byte[8];
        content.readBytes(terminalIdBytes);
        var imei = extractIMEI(terminalIdBytes);

        if (imei == null || imei.length() != 15) {
            log.error("❌ Invalid IMEI: {}", imei);
            return null;
        }

        ctx.channel().attr(IMEI_ATTR).set(imei);

        if (content.readableBytes() >= 2) {
            data.put("deviceType", String.format("0x%04X", content.readUnsignedShort()));
        }

        data.put("loginImei", imei);
        data.put("loginTime", Instant.now());

        log.info("🔐 Device login: IMEI={} from {}", imei, ctx.channel().remoteAddress());

        return DeviceMessage.builder()
                .imei(imei)
                .type("login")
                .data(data)
                .build();
    }

    private DeviceMessage parseLocation(ByteBuf content, Map<String, Object> data, 
                                      ChannelHandlerContext ctx, String messageType) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        if (imei == null || content.readableBytes() < 20) return null;

        // Parse GPS timestamp
        var gpsTime = parseDateTime(content);
        var timestamp = gpsTime.toInstant(ZoneOffset.UTC);
        data.put("gpsTimestamp", timestamp);

        // Parse GPS info
        var gpsInfo = content.readUnsignedByte();
        var satelliteCount = gpsInfo & 0x0F;
        var gpsPositioned = (gpsInfo & 0x10) != 0;

        // FIXED: Correct coordinate parsing according to GT06 specification
        var latRaw = content.readInt();
        var lonRaw = content.readInt();
        var latitude = parseCoordinate(latRaw);
        var longitude = parseCoordinate(lonRaw);

        // FIXED: Explicit Integer boxing to prevent ClassCastException
        var speedRaw = content.readUnsignedByte();
        var courseStatus = content.readUnsignedShort();
        var courseRaw = courseStatus & 0x3FF;
        var gpsValid = (courseStatus & 0x1000) != 0;

        // Store with explicit types
        data.put("latitude", latitude);
        data.put("longitude", longitude);
        data.put("speed", Integer.valueOf(speedRaw));        // Explicit Integer boxing
        data.put("course", Integer.valueOf(courseRaw));      // Explicit Integer boxing
        data.put("satelliteCount", Integer.valueOf(satelliteCount));
        data.put("gpsPositioned", gpsPositioned);
        data.put("gpsValid", gpsValid);

        // gpsValid → GPS says “I have a proper lock with enough satellites” → location is valid and reliable | Use gpsValid for real tracking.
        // gpsPositioned → GPS says “I see satellites, I think I have a location” → but it might be weak/inaccurate | Use gpsPositioned only to know GPS is trying but not yet stable.
        
        // Parse status info for alarm messages
        if ("alarm".equals(messageType) && content.readableBytes() >= 5) {
            parseStatusInfo(content, data);
        }
        
        return DeviceMessage.builder()
                .imei(imei)
                .type(messageType)
                .timestamp(timestamp)
                .data(data)
                .build();
    }

    private DeviceMessage parseHeartbeat(ByteBuf content, Map<String, Object> data, ChannelHandlerContext ctx) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        if (imei == null) return null;

        if (content.readableBytes() >= 5) {
            parseStatusInfo(content, data);
        }

        log.debug("💓 Heartbeat from: {}", imei);

        return DeviceMessage.builder()
                .imei(imei)
                .type("heartbeat")
                .data(data)
                .build();
    }

    private DeviceMessage parseString(ByteBuf content, Map<String, Object> data, ChannelHandlerContext ctx) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        if (imei == null) return null;

        if (content.readableBytes() > 0) {
            var stringBytes = new byte[content.readableBytes()];
            content.readBytes(stringBytes);
            data.put("content", new String(stringBytes).trim());
        }

        return DeviceMessage.builder()
                .imei(imei)
                .type("string")
                .data(data)
                .build();
    }

    private DeviceMessage parseAddressRequest(ByteBuf content, Map<String, Object> data, ChannelHandlerContext ctx) {
        var locationMsg = parseLocation(content, data, ctx, "gps_address");
        if (locationMsg == null) return null;

        if (content.readableBytes() >= 21) {
            var phoneBytes = new byte[21];
            content.readBytes(phoneBytes);
            data.put("phoneNumber", new String(phoneBytes).trim());
        }

        return locationMsg.toBuilder()
                .type("gps_address")
                .data(data)
                .build();
    }

    private DeviceMessage parseUnknown(int protocolNumber, Map<String, Object> data, ChannelHandlerContext ctx) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        if (imei == null) {
            imei = "UNKNOWN_" + ctx.channel().id().asShortText();
        }

        data.put("protocolNumber", String.format("0x%02X", protocolNumber));
        log.warn("❓ Unknown protocol: 0x{:02X} from {}", protocolNumber, imei);

        return DeviceMessage.builder()
                .imei(imei)
                .type("unknown")
                .data(data)
                .build();
    }

    // Helper methods

    private String extractIMEI(byte[] imeiBytes) {
        if (imeiBytes == null || imeiBytes.length != 8) return null;

        var imei = new StringBuilder(15);

        for (var b : imeiBytes) {
            var unsignedByte = b & 0xFF;
            var high = (unsignedByte >>> 4) & 0x0F;
            var low = unsignedByte & 0x0F;

            if (high <= 9) imei.append((char)('0' + high));
            else if (high != 0x0F) return null;

            if (low <= 9) imei.append((char)('0' + low));
            else if (low != 0x0F) return null;
        }

        var result = imei.toString();

        // Handle 16-digit case
        if (result.length() == 16 && result.charAt(0) == '0') {
            result = result.substring(1);
        }

        return (result.length() == 15 && result.matches("\\d{15}")) ? result : null;
    }

    private LocalDateTime parseDateTime(ByteBuf content) {
        return LocalDateTime.of(
                2000 + content.readUnsignedByte(),
                content.readUnsignedByte(),
                content.readUnsignedByte(),
                content.readUnsignedByte(),
                content.readUnsignedByte(),
                content.readUnsignedByte()
        );
    }

    /**
     * FIXED: Correct GT06 coordinate parsing
     * GT06 format: coordinate = GPS decimal minutes * 30000
     * To get decimal degrees: rawValue / 1800000.0
     */
    private double parseCoordinate(int rawValue) {
        return rawValue / 1800000.0;
    }

    private void parseStatusInfo(ByteBuf content, Map<String, Object> data) {
        if (content.readableBytes() < 5) return;

        var terminalInfo = content.readUnsignedByte();
        var voltageLevel = content.readUnsignedByte();
        var gsmSignal = content.readUnsignedByte();
        var alarmLanguage = content.readUnsignedShort();

        data.put("charging", (terminalInfo & 0x04) != 0);
        data.put("ignition", (terminalInfo & 0x02) != 0);
        data.put("voltageLevel", Integer.valueOf(voltageLevel));
        data.put("gsmSignalStrength", Integer.valueOf(gsmSignal));
        data.put("alarmStatus", Integer.valueOf((alarmLanguage >> 8) & 0xFF));
        data.put("language", (alarmLanguage & 0xFF) == 1 ? "Chinese" : "English");
    }

    // private String maskImei(String imei) {
    //     return imei != null && imei.length() >= 4 ? 
    //            "*".repeat(11) + imei.substring(11) : "****";
    // }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("❌ GT06 decoder error: {}", cause.getMessage(), cause);
        // super.exceptionCaught(ctx, cause);
    }
}
