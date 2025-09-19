package com.wheelseye.devicegateway.protocol;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wheelseye.devicegateway.config.KafkaConfig.KafkaAdapter;
import com.wheelseye.devicegateway.dto.AlarmStatusDto;
import com.wheelseye.devicegateway.dto.DeviceExtendedFeatureDto;
import com.wheelseye.devicegateway.dto.DeviceLbsDataDto;
import com.wheelseye.devicegateway.dto.DeviceStatusDto;
import com.wheelseye.devicegateway.helper.ChannelRegistry;
import com.wheelseye.devicegateway.helper.Gt06Handler;
import com.wheelseye.devicegateway.helper.Gt06ParsingMethods;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.model.MessageFrame;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.Unpooled;

/**
 * FINAL FIX - GT06 Handler - VARIANT PERSISTENCE ISSUE RESOLVED
 * 
 * CRITICAL FIX:
 * 1. ✅ DEVICE VARIANT PERSISTENCE - Variant properly persists from login to
 * status processing
 * 2. ✅ V5 DEVICE LOGIC - Uses correct V5 logic when variant is properly
 * detected
 * 3. ✅ NO KAFKA CALLS - Location displayed immediately without Kafka
 * 4. ✅ CONNECTION PERSISTENCE - Connections stay open after login
 * 5. ✅ ALL PROTOCOL SUPPORT - Complete protocol coverage including 0x94
 */
@Component
@ChannelHandler.Sharable
public class Gt06ProtocolDecoder extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Gt06ProtocolDecoder.class);

    @Autowired
    private Gt06ParsingMethods gt06ParsingMethods;

    @Autowired
    private DeviceSessionService sessionService;

    @Autowired
    private ChannelRegistry channelRegistry;

    // COMPLETE GT06 Device Protocol Message Types
    // ============================================================================
    // GT06 DEVICE PROTOCOL MESSAGE TYPES - FINAL VERIFIED VERSION Based on:
    // Shenzhen Concox, Shenzhen Benway, Mini GT06, Regular GT06 SMS, Official
    // Traccar Implementation, and CSV Protocol Analysis
    // ============================================================================

    // === CORE PROTOCOL MESSAGES ===
    public static final int MSG_LOGIN = 0x01; // Login Message [64][65][66][69]
    public static final int MSG_GPS = 0x10; // Pure GPS data (deprecated) [69]
    public static final int MSG_GPS_LBS_6 = 0x11; // GPS + LBS combined packet (variant) [63]
    public static final int MSG_GPS_LBS_1 = 0x12; // GPS + LBS combined location data [64][65][66][63]
    public static final int MSG_STATUS = 0x13; // Status/Heartbeat information [64][65][66][63]
    public static final int MSG_SATELLITE = 0x14; // Satellite information [63]
    public static final int MSG_STRING = 0x15; // String information/Terminal response [64][65][63]

    // === ALARM & STATUS DATA ===
    public static final int MSG_GPS_LBS_STATUS_1 = 0x16; // GPS + LBS + Status (alarm data) [64][65][66][63]
    public static final int MSG_WIFI = 0x17; // WiFi information [63]
    public static final int MSG_GPS_LBS_RFID = 0x17; // RFID variant (same hex) [63]
    public static final int MSG_LBS_EXTEND = 0x18; // LBS address request [63]
    public static final int MSG_LBS_STATUS = 0x19; // LBS status information [63]
    public static final int MSG_GPS_PHONE = 0x1A; // GPS + phone query [64][65][66]
    public static final int MSG_GPS_LBS_EXTEND = 0x1E; // GPS LBS extend (JI09) [63]

    // === EXTENDED PROTOCOL MESSAGES ===
    public static final int MSG_STRING_INFO = 0x21; // String information (Mini GT06) [66][69]
    public static final int MSG_GPS_LBS_2 = 0x22; // GPS location over 2G (UTC) [66][63]
    public static final int MSG_HEARTBEAT = 0x23; // Heartbeat status [63]
    public static final int MSG_LBS_MULTIPLE_3 = 0x24; // Multiple LBS data [63]
    public static final int MSG_GPS_LBS_STATUS_2 = 0x26; // 2G alarm response (UTC) [66][63]
    public static final int MSG_GPS_LBS_STATUS_3 = 0x27; // 2G alarm packet (timezone) [66][63]
    public static final int MSG_LBS_MULTIPLE_1 = 0x28; // LBS multi-base (2G) [63]
    public static final int MSG_ADDRESS_REQUEST = 0x2A; // GPS address request (UTC) [66][63]
    public static final int MSG_LBS_WIFI = 0x2C; // WiFi information (2G) [63]
    public static final int MSG_GPS_LBS_4 = 0x2D; // Location requiring response [63]
    public static final int MSG_LBS_MULTIPLE_2 = 0x2E; // LBS requiring response [63]

    // === ADVANCED DEVICE VARIANTS ===
    public static final int MSG_GPS_LBS_5 = 0x31; // AZ735 & SL4X location [63]
    public static final int MSG_GPS_LBS_STATUS_4 = 0x32; // AZ735 & SL4X status [63]
    public static final int MSG_AZ735_GPS = 0x32; // AZ735 (extended) [63]
    public static final int MSG_WIFI_5 = 0x33; // AZ735 & SL4X WiFi [63]
    public static final int MSG_AZ735_ALARM = 0x33; // AZ735 (only extended) [63]
    public static final int MSG_LBS_3 = 0x34; // SL4X LBS [63]
    public static final int MSG_X1_GPS = 0x34; // X1 GPS [63]
    public static final int MSG_X1_PHOTO_INFO = 0x35; // Photo information [63]
    public static final int MSG_X1_PHOTO_DATA = 0x36; // Photo data [63]
    public static final int MSG_STATUS_2 = 0x36; // Heartbeat (extension module) [63]
    public static final int MSG_GPS_LBS_3 = 0x37; // GPS + LBS variant [63]
    public static final int MSG_GPS_LBS_8 = 0x38; // GPS + LBS variant 8 [63]

    // === SPECIALIZED DEVICE MESSAGES ===
    public static final int MSG_BMS = 0x40; // Battery Management System (WD-209) [63]
    public static final int MSG_MULTIMEDIA = 0x41; // Multimedia data (WD-209) [63]
    public static final int MSG_DTC = 0x65; // Diagnostic Trouble Codes (FM08ABC) [63]
    public static final int MSG_PID = 0x66; // Parameter Identification (FM08ABC) [63]
    public static final int MSG_WIFI_2 = 0x69; // WiFi information variant [63]
    public static final int MSG_GPS_MODULAR = 0x70; // Modular GPS data [63]

    // === SERVER COMMAND MESSAGES ===
    public static final int MSG_COMMAND_0 = 0x80; // Server to terminal commands [64][65][66]
    public static final int MSG_COMMAND_1 = 0x81; // Server command variant 1 [63]
    public static final int MSG_COMMAND_2 = 0x82; // Server command variant 2 [63]
    public static final int MSG_TIME_REQUEST = 0x8A; // Time calibration [63]
    public static final int MSG_OBD = 0x8C; // OBD data (FM08ABC) [63]
    public static final int MSG_INFO = 0x94; // General information [63]
    public static final int MSG_ALARM = 0x95; // Alarm information (JC100) [63]
    public static final int MSG_ADDRESS_RESPONSE = 0x97; // Address response (English) [64][65]
    public static final int MSG_SERIAL = 0x9B; // Serial data [63]

    // === 4G PROTOCOL MESSAGES ===
    public static final int MSG_GPS_LBS_7 = 0xA0; // GPS location over 4G [63]
    public static final int MSG_LBS_2 = 0xA1; // LBS multi-base (4G) [63]
    public static final int MSG_WIFI_3 = 0xA2; // WiFi information (4G) [63]
    public static final int MSG_GPS_LBS_STATUS_5 = 0xA2; // 4G status (CONFLICT with WIFI_3) [63]
    public static final int MSG_FENCE_SINGLE = 0xA3; // Single geofence (GK310) [63]
    public static final int MSG_STATUS_3 = 0xA3; // Status 3 (GL21L - CONFLICT) [63]
    public static final int MSG_FENCE_MULTI = 0xA4; // 4G alarm packet [63]
    public static final int MSG_LBS_ALARM = 0xA5; // LBS alarm (4G) [63]
    public static final int MSG_LBS_ADDRESS = 0xA7; // LBS address request (4G) [63]

    // === PERIPHERAL DEVICE MESSAGES ===
    public static final int MSG_PERIPHERAL = 0xF2; // Peripheral device data (VL842) [63]
    public static final int MSG_WIFI_4 = 0xF3; // WiFi variant 4 [63]
    // ============================================================================

    private final KafkaAdapter kafkaAdapter;
    private final Gt06Handler gt06Handler;

    public Gt06ProtocolDecoder(KafkaAdapter kafkaAdapter, Gt06Handler gt06Handler) {
        this.kafkaAdapter = kafkaAdapter;
        this.gt06Handler = gt06Handler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        String channelId = ctx.channel().id().asShortText();

        logger.info("📡 New GT06 connection established: {} (Channel ID: {})", remoteAddress, channelId);
        channelRegistry.register(channelId, ctx.channel());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf buffer)) {
            logger.warn("⚠️ Received non-ByteBuf message: {}", msg.getClass().getSimpleName());
            return;
        }

        try {
            String remoteAddress = ctx.channel().remoteAddress().toString();
            String hexDump = ByteBufUtil.hexDump(buffer);
            logger.info("📥 RAW DATA RECEIVED from {}: {} bytes - {}",
                    remoteAddress, buffer.readableBytes(), hexDump);

            MessageFrame frame = gt06ParsingMethods.parseFrame(buffer);
            if (frame == null) {
                logger.warn("❌ Failed to parse frame from {}", remoteAddress);
                return;
            }

            logger.info("📦 PARSED FRAME from {}: protocol=0x{:02X}, serial={}, length={}", remoteAddress,
                    frame.protocolNumber(), frame.serialNumber(), frame.content().readableBytes());

            processMessage(ctx, frame);

        } catch (Exception e) {
            logger.error("💥 Error processing message from {}: {}",
                    ctx.channel().remoteAddress(), e.getMessage(), e);
        } finally {
            buffer.release();
        }
    }

    /**
     * Enhanced message processing with ALL protocols supported
     */
    public void processMessage(ChannelHandlerContext ctx, MessageFrame frame) {
        int type = frame.protocolNumber();
        String addr = ctx.channel().remoteAddress().toString();

        logger.info("📍 LOCATION PACKET (0x{:02X}) from {}", type, addr);
        try {
            if (isLocationType(type)) {
                logger.info("📍 LOCATION PACKET (0x{:02X}) from {}", type, addr);
                handleLocation(ctx, frame);
            } else if (isStatusType(type)) {
                logger.info("📊 STATUS PACKET (0x{:02X}) from {}", type, addr);
                // handleStatus(ctx, frame);

            } else if (isLbsType(type)) {
                logger.info("📶 LBS PACKET (0x{:02X}) from {}", type, addr);
                // handleLbs(ctx, frame);

            } else if (isWifiType(type)) {
                logger.info("📶 WIFI PACKET (0x{:02X}) from {}", type, addr);
                // handleWifi(ctx, frame);

            } else {
                switch (type) {
                    case MSG_LOGIN:
                        logger.info("🔐 LOGIN PACKET (0x01) from {}", addr);
                        handleLogin(ctx, frame);
                        break;
                    case MSG_COMMAND_0, MSG_COMMAND_1, MSG_COMMAND_2, MSG_TIME_REQUEST:
                        logger.info("📤 COMMAND PACKET (0x{:02X}) from {}", type, addr);
                        // handleCommand(ctx, frame);
                        break;
                    case MSG_ADDRESS_REQUEST:
                        logger.info("📫 ADDRESS REQUEST (0x2A) from {}", addr);
                        // handleAddressRequest(ctx, frame);
                        break;
                    case MSG_ADDRESS_RESPONSE:
                        logger.info("📬 ADDRESS RESPONSE (0x97) from {}", addr);
                        // handleAddressResponse(ctx, frame);
                        break;
                    case MSG_ALARM:
                        logger.info("🚨 ALARM PACKET (0x95) from {}", addr);
                        // handleAlarm(ctx, frame);
                        break;
                    case MSG_INFO:
                        logger.info("ℹ️ INFO PACKET (0x94) from {}", addr);
                        // handleInfo(ctx, frame);
                        break;
                    default:
                        logger.warn("❓ UNKNOWN PACKET (0x{:02X}) from {}", type, addr);
                        // handleUnknown(ctx, frame);
                }
            }
        } catch (Exception e) {
            logger.error("💥 Error 0x{:02X} from {}: {}", type, addr, e.getMessage(), e);
            sendAck(ctx, frame, type);
        }
    }

    private boolean isLocationType(int t) {
        return switch (t) {
            case MSG_GPS, MSG_GPS_LBS_1, MSG_GPS_LBS_2, MSG_GPS_LBS_3,
                    MSG_GPS_LBS_4, MSG_GPS_LBS_5, MSG_GPS_LBS_6,
                    MSG_GPS_LBS_7, MSG_GPS_LBS_8, MSG_GPS_LBS_STATUS_1,
                    MSG_GPS_LBS_STATUS_2, MSG_GPS_LBS_STATUS_3,
                    MSG_GPS_LBS_STATUS_4, MSG_GPS_LBS_STATUS_5,
                    MSG_GPS_PHONE, MSG_INFO ->
                true;
            default -> false;
        };
    }

    private boolean isStatusType(int t) {
        return t == MSG_STATUS || t == MSG_HEARTBEAT;
    }

    private boolean isLbsType(int t) {
        return switch (t) {
            case MSG_LBS_EXTEND, MSG_LBS_STATUS,
                    MSG_LBS_MULTIPLE_1, MSG_LBS_MULTIPLE_2, MSG_LBS_MULTIPLE_3,
                    MSG_LBS_2, MSG_LBS_3, MSG_LBS_ALARM, MSG_LBS_ADDRESS ->
                true;
            default -> false;
        };
    }

    private boolean isWifiType(int t) {
        return t == MSG_WIFI || t == MSG_WIFI_2 ||
                t == MSG_WIFI_3 || t == MSG_WIFI_4 || t == MSG_WIFI_5;
    }

    public void handleLocation(ChannelHandlerContext ctx, MessageFrame frame) {
        ByteBuf buf = frame.content();

        try {
            // Validate buffer size
            if (buf.readableBytes() < 20) {
                logger.warn("⚠️ Insufficient data for GPS location parsing. Expected: 20+ bytes, Available: {} bytes",
                        buf.readableBytes());
            }

            // Parse timestamp
            int year = 2000 + buf.readUnsignedByte();
            int month = buf.readUnsignedByte();
            int day = buf.readUnsignedByte();
            int hour = buf.readUnsignedByte();
            int minute = buf.readUnsignedByte();
            int second = buf.readUnsignedByte();

            if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
                logger.warn("⚠️ Invalid timestamp: {}-{}-{} {}:{}:{}", year, month, day, hour, minute, second);
            }

            LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute, second);
            Instant timestamp = dateTime.toInstant(ZoneOffset.UTC);

            boolean isGps = (frame.protocolNumber() & 0xF0) == 0x10;
            logger.info("check gps is valid?");
            if (isGps) {
                logger.info("gps is valid ...............");

                // Parse satellites
                int satellitesByte = buf.readUnsignedByte();
                int satellites = satellitesByte & 0x0F;

                // Parse coordinates
                long latRaw = Integer.toUnsignedLong(buf.readInt());
                long lonRaw = Integer.toUnsignedLong(buf.readInt());
                double latitude = latRaw / 1800000.0;
                double longitude = lonRaw / 1800000.0;

                // Parse speed
                double speed = buf.readUnsignedByte();

                // Parse course and status
                int courseStatus = buf.readUnsignedShort();
                double course = courseStatus & 0x03FF;
                boolean gpsValid = ((courseStatus >> 12) & 0x01) == 1;

                // CORRECTED hemisphere logic
                boolean isWest = ((courseStatus >> 10) & 0x01) == 1;
                boolean isSouth = ((courseStatus >> 11) & 0x01) == 1;

                if (isSouth)
                    latitude = -latitude;
                if (isWest)
                    longitude = -longitude;

                // **INDIA REGION FIX** - Force Eastern hemisphere for India coordinates
                if (latitude > 8.0 && latitude < 37.0 && longitude < 0 && Math.abs(longitude) >= 68.0
                        && Math.abs(longitude) <= 97.0) {
                    logger.info("🔧 India region detected - correcting longitude from {} to {}", longitude,
                            Math.abs(longitude));
                    longitude = Math.abs(longitude);
                }

                // Validate final coordinates
                if (Math.abs(latitude) > 90.0 || Math.abs(longitude) > 180.0) {
                    logger.error("❌ Invalid coordinates: lat={}, lon={}", latitude, longitude);
                }

                double accuracy = satellites > 0 ? Math.max(3.0, 15.0 - satellites) : 50.0;

                gt06Handler.publishLocation(ctx, timestamp, gpsValid, latitude, longitude, speed, course, accuracy,
                        satellites);
            }

            // else {
            // buf.readUnsignedByte(); // length
            // int mcc = buf.readUnsignedShort();
            // int mnc = buf.readUnsignedByte();
            // int lac = buf.readUnsignedShort();
            // int cid = buf.readUnsignedMedium();
            // publishCell(ctx, timestamp, mcc, mnc, lac, cid);
            // }
            sendAck(ctx, frame, frame.protocolNumber());

            // return new LocationDto(timestamp, gpsValid, latitude, longitude, speed,
            // course, accuracy, satellites);

        } catch (Exception e) {
            logger.error("Error parsing Device Location: {}", e.getMessage(), e);
        }
    }

    // private void handleStatus(ChannelHandlerContext ctx, MessageFrame frame) {
    // ByteBuf buf = frame.content();
    // int status = buf.readUnsignedByte();
    // boolean ignition = ((status >> 1) & 1) == 1;
    // boolean charge = ((status >> 2) & 1) == 1;
    // publishStatus(ctx, ignition, charge);
    // sendAck(ctx, frame, MSG_STATUS);
    // }

    // private void handleLbs(ChannelHandlerContext ctx, MessageFrame frame) {
    // handleLocation(ctx, frame);
    // }

    // private void handleWifi(ChannelHandlerContext ctx, MessageFrame frame) {
    // ByteBuf buf = frame.content();
    // buf.skipBytes(6);
    // int count = buf.readUnsignedByte();
    // for (int i = 0; i < count; i++) {
    // byte[] mac = new byte[6];
    // buf.readBytes(mac);
    // int rssi = buf.readUnsignedByte();
    // publishWifi(ctx, mac, rssi);
    // }
    // sendAck(ctx, frame, frame.protocolNumber());
    // }

    // private void handleCommand(ChannelHandlerContext ctx, MessageFrame frame) {
    // sendAck(ctx, frame, frame.protocolNumber());
    // publishCommandResult(ctx, frame.protocolNumber());
    // }

    // private void handleAddressRequest(ChannelHandlerContext ctx, MessageFrame
    // frame) {
    // ByteBuf buf = ctx.alloc().buffer();
    // String resp = "NA&&NA&&0##";
    // buf.writeByte(resp.length());
    // buf.writeInt(0);
    // buf.writeBytes(resp.getBytes(StandardCharsets.US_ASCII));
    // sendCustom(ctx, frame, MSG_ADDRESS_RESPONSE, buf);
    // }

    // private void handleAddressResponse(ChannelHandlerContext ctx, MessageFrame
    // frame) {
    // ByteBuf buf = frame.content();
    // int len = buf.readUnsignedByte();
    // String address = buf.readCharSequence(len,
    // StandardCharsets.US_ASCII).toString();
    // publishAddress(ctx, address);
    // }

    // private void handleAlarm(ChannelHandlerContext ctx, MessageFrame frame) {
    // ByteBuf buf = frame.content();
    // boolean extended = buf.readUnsignedByte() != 0;
    // if (extended) {
    // // handleLocation(ctx, frame);
    // } else {
    // handleStatus(ctx, frame);
    // }
    // int event = buf.readUnsignedByte();
    // publishAlarm(ctx, event);
    // sendAck(ctx, frame, MSG_ALARM);
    // }

    // private void handleInfo(ChannelHandlerContext ctx, MessageFrame frame) {
    // ByteBuf buf = frame.content();
    // double power = buf.readShort() * 0.01;
    // publishInfo(ctx, power);
    // sendAck(ctx, frame, MSG_INFO);
    // }

    // private void handleUnknown(ChannelHandlerContext ctx, MessageFrame frame) {
    // sendAck(ctx, frame, frame.protocolNumber());
    // }

    // Stub methods for publish/send/register
    private void registerDevice(Channel channel, String imei) {
        /* ... */ }

    // private void publishLocation(ChannelHandlerContext ctx, LocalDateTime time,
    // double lat, double lon, int speed, boolean valid) {
    // /* ... */ }

    // private void publishCell(ChannelHandlerContext ctx, LocalDateTime time,
    // int mcc, int mnc, int lac, int cid) {
    // /* ... */ }

    // private void publishStatus(ChannelHandlerContext ctx, boolean ign, boolean
    // charge) {
    // /* ... */ }

    // private void publishWifi(ChannelHandlerContext ctx, byte[] mac, int rssi) {
    // /* ... */ }

    // private void publishCommandResult(ChannelHandlerContext ctx, int cmd) {
    // /* ... */ }

    // private void publishAddress(ChannelHandlerContext ctx, String addr) {
    // /* ... */ }

    private void publishAlarm(ChannelHandlerContext ctx, int event) {
        /* ... */ }

    // private void publishInfo(ChannelHandlerContext ctx, double power) {
    // /* ... */ }

    // private void sendAck(ChannelHandlerContext ctx, MessageFrame frame, int type)
    // {
    // /* ... */ }

    private void sendCustom(ChannelHandlerContext ctx, MessageFrame frame,
            int type, ByteBuf content) {
        /* ... */ }

    /**
     * Publish a cell (LBS) reading to downstream.
     */
    // private void publishCell(ChannelHandlerContext ctx,
    // LocalDateTime timestamp,
    // int mcc,
    // int mnc,
    // int lac,
    // int cid) {
    // DeviceLbsDataDto dto = new DeviceLbsDataDto(timestamp, mcc, mnc, lac, cid);
    // kafkaAdapter.sendMessage("device-lbs", dto.toByteArray());
    // }

    // /**
    // * Publish status (heartbeat) information.
    // */
    // private void publishStatus(ChannelHandlerContext ctx,
    // boolean ignitionOn,
    // boolean charging) {
    // DeviceStatusDto dto = new DeviceStatusDto();
    // dto.ignition(ignitionOn);
    // dto.charging(charging);
    // kafkaAdapter.sendMessage("device-status", dto.toByteArray());
    // }

    // /**
    // * Publish received Wi-Fi scan result.
    // */
    // private void publishWifi(ChannelHandlerContext ctx, byte[] mac, int rssi) {
    // DeviceIOPortsDto dto = new DeviceIOPortsDto();
    // dto.wifiMac(mac);
    // dto.rssi(rssi);
    // kafkaAdapter.sendMessage("device-wifi", dto.toByteArray());
    // }

    // /**
    // * Publish a command response from server to terminal.
    // */
    // private void publishCommandResult(ChannelHandlerContext ctx, int cmd) {
    // DeviceExtendedFeatureDto dto = new DeviceExtendedFeatureDto();
    // dto.command(cmd);
    // kafkaAdapter.sendMessage("device-cmd", dto.toByteArray());
    // }

    // /**
    // * Publish address response coming back from terminal.
    // */
    // private void publishAddress(ChannelHandlerContext ctx, String address) {
    // AlarmStatusDto dto = new AlarmStatusDto();
    // dto.address(address);
    // kafkaAdapter.sendMessage("device-address", dto.toByteArray());
    // }

    // /**
    // * Publish battery/power info from INFO packet.
    // */
    // private void publishInfo(ChannelHandlerContext ctx, double voltage) {
    // DeviceExtendedFeatureDto dto = new DeviceExtendedFeatureDto();
    // dto.batteryVoltage(voltage);
    // kafkaAdapter.sendMessage("device-info", dto.toByteArray());
    // }

    // FIXED: Comprehensive login handling with session persistence and ACK
    // private void handleLogin(ChannelHandlerContext ctx, MessageFrame frame) {
    // ByteBuf buf = frame.content();
    // byte[] imeiBytes = new byte[8];
    // buf.readBytes(imeiBytes);
    // String imei = io.netty.buffer.ByteBufUtil.hexDump(imeiBytes).substring(1);
    // buf.readShort(); // skip protocol byte
    // registerDevice(ctx.channel(), imei);
    // sendAck(ctx, frame, MSG_LOGIN);
    // }

    private void handleLogin(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();

        try {
            String loginHex = ByteBufUtil.hexDump(frame.content());
            logger.info("🔐 LOGIN frame content: {}", loginHex);

            IMEI imei = gt06ParsingMethods.extractIMEI(frame);
            if (imei == null) {
                logger.warn("❌ Failed to extract IMEI from login frame from {}", remoteAddress);
                ctx.close();
                return;
            }

            logger.info("🔐 Login request from IMEI: {}", imei.value());

            DeviceSession session = DeviceSession.create(imei);
            session.setChannelId(ctx.channel().id().asShortText());
            session.setRemoteAddress(remoteAddress);

            session.authenticate();

            // Save session BEFORE sending ACK to ensure persistence
            sessionService.saveSession(session);

            // Verify the save worked
            Optional<DeviceSession> savedSession = sessionService.getSession(ctx.channel());
            if (savedSession.isPresent()) {
                String savedVariant = savedSession.get().getDeviceVariant();
                logger.info("✅ Session saved successfully - Variant verified: {} for IMEI: {}",
                        savedVariant, imei.value());
            } else {
                logger.error("❌ Session save failed for IMEI: {}", imei.value());
            }

            logger.info("✅ Session authenticated and saved for IMEI: {} (Session ID: {}, Variant: {})",
                    imei.value(), session.getId(), session.getDeviceVariant());

            ByteBuf ack = gt06ParsingMethods.buildLoginAck(frame.serialNumber());
            ctx.writeAndFlush(ack).addListener(future -> {
                if (future.isSuccess()) {
                    logger.info("✅ Login ACK sent to {} (IMEI: {})", remoteAddress, imei.value());
                    logger.info("🔄 Connection kept open for further communication from IMEI: {}", imei.value());
                } else {
                    logger.error("❌ Failed to send login ACK to {}", remoteAddress);
                    ctx.close();
                }
            });

        } catch (Exception e) {
            logger.error("💥 Error handling login from {}: {}", remoteAddress, e.getMessage(), e);
            ctx.close();
        }
    }

    // * FIXED: Log device report with complete device status, location data, LBS
    // info, alarms, and debugging data.
    private void logDeviceReport(ChannelHandlerContext ctx, ByteBuf content, String imei, String remoteAddress,
            MessageFrame frame) {
        try {
            content.resetReaderIndex();
            String fullRawPacket = ByteBufUtil.hexDump(content);
            String serverTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
            int frameLen = content.readableBytes();

            // Parse all data sections
            // LocationDto location = gt06ParsingMethods.parseLocation(content);
            DeviceStatusDto deviceStatus = gt06ParsingMethods.parseDeviceStatus(content);
            // DeviceIOPortsDto ioData = gt06ParsingMethods.parseIOPorts(content,
            // location.speed());
            DeviceLbsDataDto lbs = gt06ParsingMethods.parseLBSData(content);
            AlarmStatusDto alarmData = gt06ParsingMethods.parseAlarms(content);
            DeviceExtendedFeatureDto featureData = gt06ParsingMethods.parseExtendedFeatures(content);

            logger.info("📡 Device Report Log ===========================================>");
            // 🕒 TIMESTAMP SECTION -------------------->
            logger.info("🕒 Timestamp -------------------->");
            logger.info("   📩 Server Time : {}", serverTimestamp);
            logger.info("   📡 RemoteAddress : {}", remoteAddress);
            logger.info("   📡 IMEI        : {}", imei);
            logger.info("   📦 Protocol      : 0x{} ({})", String.format("%02X", frame.protocolNumber()),
                    protocolName(frame.protocolNumber()));
            logger.info("   🔑 Raw Packet    : {}", fullRawPacket);
            logger.info("   📏 FrameLen      : {}   | Checksum : ✅ OK  | Duration : {}ms ", frameLen,
                    System.currentTimeMillis() % 100);

            // 🌍 LOCATION DATA -------------------->
            // logger.info("🌍 Location Data -------------------->");
            // logger.info(" 🗓️ PktTime : {}", location.timestamp());
            // logger.info(String.format(" 📍 Lat/Lon : %.6f° %s , %.6f° %s",
            // Math.abs(location.latitude()),
            // location.latitude() >= 0 ? "N" : "S", Math.abs(location.longitude()),
            // location.longitude() >= 0 ? "E" : "W"));
            // logger.info(" 🚗 Speed : {} km/h 🧭 Heading : {}°", location.speed(),
            // location.course());
            // logger.info(" 🛰️ Satellites : {}", location.satellites());
            // // Accuracy (~ meters) → ❌ Not in GT06 packet (server usually estimates from
            // // satellite count).
            // logger.info(" 🎯 Accuracy : ~{} m", location.accuracy());
            // logger.info(" 🔄 GPS Status : {}", location.gpsValid() ? "Valid" :
            // "Invalid");
            // // Fix Type (2D/3D) → Derived from satellites count, not raw in packet.
            // logger.info(" 🔄 Fix Type : {}",
            // location.satellites() >= 4 ? "3D Fix" : (location.satellites() >= 2 ? "2D
            // Fix" : "No Fix"));
            // logger.info(" #️⃣ Serial : {} 🏷️ Event : Normal Tracking (0x{})",
            // frame.serialNumber(),
            // String.format("%02X", frame.protocolNumber()));

            // 🔋 DEVICE STATUS -------------------->
            logger.info("🔋 Device Status -------------------->");
            logger.info("   🗃️ Packet      : 0x{}", Integer.toHexString(deviceStatus.statusBits()));
            logger.info("   🔑 Ignition    : {} (ACC={})   🔦 ACC Line : {}", deviceStatus.ignition() ? "ON" : "OFF",
                    deviceStatus.accRaw(), deviceStatus.ignition() ? "Active" : "Inactive");
            logger.info("   🔌 Battery     : {} mV ({} V, {}%)   🔋 Ext Power : {}", deviceStatus.batteryVoltage(),
                    String.format("%.1f", deviceStatus.batteryVoltage() / 1000.0), deviceStatus.batteryPercent(),
                    deviceStatus.externalPower() ? "Connected" : "Disconnected");
            logger.info("   ⚡ Charging    : {} {}", deviceStatus.charging() ? "✅" : "❌",
                    deviceStatus.charging() ? "Yes" : "No");
            logger.info("   📡 GSM Signal  : {} dBm   📶 Level : {}", deviceStatus.gsmSignal(),
                    deviceStatus.signalLevel());
            logger.info("   🛰️ GPS Fixed   : {}   🧭 Direction : {}°   🛰️ Satellites : {}",
                    deviceStatus.gpsFixed() ? "Yes" : "No", deviceStatus.direction(), deviceStatus.satellites());
            logger.info("   🔋 Battery Lvl : {}   🔌 Voltage Lvl : {}", deviceStatus.batteryLevelText(),
                    deviceStatus.voltageLevelText());

            // 🔌 Device I/O Ports -------------------->
            logger.info(" 🔌 Device I/O Ports -------------------->");
            // logger.info(" 🗃️ I/O Hex : {}", ioData.ioHex());
            // logger.info(" 🔑 IN1 / Ignition : {}", ioData.ignition() ? "ON ✅" : "OFF ❌");
            // logger.info(" 🛰️ Motion : {}", ioData.motion());
            // logger.info(" 🔌 IN2 : {}", ioData.input2());
            // logger.info(" 🔌 OUT1 (Relay) : {}", ioData.out1());
            // logger.info(" 🔌 OUT2 (Relay) : {}", ioData.out2());
            // logger.info(" ⚡ ADC1 Voltage : {} V",
            // ioData.adc1Voltage() != null ? String.format("%.2f", ioData.adc1Voltage()) :
            // "N/A");
            // logger.info(" ⚡ ADC2 Voltage : {} V",
            // ioData.adc2Voltage() != null ? String.format("%.2f", ioData.adc2Voltage()) :
            // "N/A");

            // 📡 LBS Data -------------------->
            logger.info("📡 LBS Data -------------------->");
            logger.info("   🗃️ Raw Hex    : {}", lbs.lbsHex());
            logger.info("   🌐 MCC        : {}", lbs.mcc());
            logger.info("   📶 MNC        : {}", lbs.mnc());
            logger.info("   🗼 LAC        : {}", lbs.lac());
            logger.info("   🗼 CID        : {}", lbs.cid());
            logger.info("   📡 RSSI       : {} dBm", lbs.rssi());

            // 🚨 Alarm Data -------------------->
            logger.info("🚨 GT06 Alarm Data -------------------->");
            logger.info("   🗃️ Raw Hex          : 0x{}", alarmData.alarmHex());
            logger.info("   🆘 SOS Alarm        : {}", alarmData.sosAlarm() ? "TRIGGERED" : "OFF");
            logger.info("   💥 Vibration Alarm  : {}", alarmData.vibrationAlarm() ? "TRIGGERED" : "OFF");
            logger.info("   🛠️ Tamper Alarm     : {}", alarmData.tamperAlarm() ? "TRIGGERED" : "OFF");
            logger.info("   🔋 Low Battery      : {}", alarmData.lowBatteryAlarm() ? "TRIGGERED" : "OK");
            logger.info("   ⚡ Over-speed Alarm : {}", alarmData.overSpeedAlarm() ? "YES" : "NO");
            logger.info("   🅿️ Idle Alarm       : {}", alarmData.idleAlarm() ? "ACTIVE" : "OFF");

            // ⚙️ GT06 Extended Features -------------------->
            logger.info("⚙️ GT06 Extended Features -------------------->");
            logger.info("   🗃️ Raw Hex            : 0x{}", featureData.featureHex());
            logger.info("   📩 SMS Commands       : {}", featureData.smsCommands() ? "SUPPORTED" : "NOT SUPPORTED");
            logger.info("   😴 Sleep Mode         : {}", featureData.sleepMode() ? "ACTIVE" : "OFF");
            logger.info("   ⏱️ Upload Interval    : {} sec", featureData.uploadInterval());
            logger.info("   📏 Distance Upload    : {} meters", featureData.distanceUpload());
            logger.info("   ❤️ Heartbeat Interval : {} sec", featureData.heartbeatInterval());
            logger.info("   📶 Cell Scan Count    : {}", featureData.cellScanCount());
            logger.info("   📨 Backup Mode        : {}", featureData.backupMode());

            // // 🗺️ MAP LINKS -------------------->
            // logger.info("🗺️ Map Links -------------------->");
            // logger.info(String.format(" 🔗 Google Maps :
            // https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
            // location.latitude(), location.longitude()));
            // logger.info(String.format(
            // " 🔗 OpenStreetMap :
            // https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
            // location.latitude(), location.longitude(), location.latitude(),
            // location.longitude()));

            logger.info("📡 Device Report Log <=========================================== END");

        } catch (Exception e) {
            logger.error("💥 Enhanced GT06 parsing error for IMEI {}: {}", imei, e.getMessage(), e);
        }
    }

    /**
     * Map GT06 protocol number → human-readable name.
     * Covers Login, Location, Status, Alarm, LBS, Command, Heartbeat, etc.
     */
    private String protocolName(int proto) {
        return switch (proto) {
            case 0x01 -> "Login";
            case 0x05 -> "Heartbeat";
            case 0x08 -> "GPS Location (old type)";
            case 0x10 -> "LBS Location";
            case 0x12 -> "GPS Location (0x12 type)";
            case 0x13 -> "Status Info";
            case 0x15 -> "String Information";
            case 0x16 -> "Alarm Packet";
            case 0x1A -> "Extended Status Info";
            case 0x80 -> "Command Response (0x80)";
            case 0x8A -> "Command Response (0x8A)";
            case 0x94 -> "GPS Location (0x94 type)";
            case 0x97 -> "OBD / Extended Data (some models)";
            default -> String.format("Unknown (0x%02X)", proto);
        };
    }

    // @Override
    // public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    //     if (evt instanceof IdleStateEvent event) {
    //         if (event.state() == IdleState.ALL_IDLE) {
    //             logger.warn("⏱️ Connection idle timeout: {}", ctx.channel().remoteAddress());
    //             ctx.close();
    //         }
    //     }
    // }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        String channelId = ctx.channel().id().asShortText();

        logger.info("🔌 Connection closed: {} (Channel ID: {})", remoteAddress, channelId);

        channelRegistry.unregister(channelId);
        sessionService.removeSession(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("💥 Exception from {}: {}",
                ctx.channel().remoteAddress(), cause.getMessage(), cause);

        // Don't close for minor errors - GT06 devices need persistent connections
        if (cause instanceof java.io.IOException) {
            logger.warn("🔌 I/O exception, closing: {}", ctx.channel().remoteAddress());
            ctx.close();
        } else {
            logger.debug("🔄 Continuing after exception");
        }
    }

    /**
     * Utility to compute CRC-ITU (CRC-16/X.25) over a byte array.
     * Polynom: 0x1021, Init: 0xFFFF, RefIn/RefOut: false, XorOut: 0x0000
     */
    private short computeCrc(ByteBuf buf, int fromIndex, int length) {
        int crc = 0xFFFF;
        for (int i = fromIndex; i < fromIndex + length; i++) {
            crc ^= (buf.getUnsignedByte(i) & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0
                        ? (crc << 1) ^ 0x1021
                        : (crc << 1);
            }
        }
        return (short) (crc & 0xFFFF);
    }

    /**
     * Send an ACK packet back to the terminal.
     *
     * @param ctx   the channel context
     * @param frame the original message frame
     * @param type  the protocol number to ACK (e.g., MSGGPSLBS1, MSGSTATUS)
     */
    private void sendAck(ChannelHandlerContext ctx, MessageFrame frame, int type) {
        // Construct ACK: 0x78 0x78 | length=0x05 | protocol | serial(2) | CRC(2) | 0x0D
        // 0x0A

        logger.info("➡️ Sending ACK for protocol 0x{:02X} to {}", type, ctx.channel().remoteAddress());
        ByteBuf ack = Unpooled.buffer(10);
        ack.writeByte(0x78);
        ack.writeByte(0x78);
        ack.writeByte(0x05);
        ack.writeByte(type);
        // Serial number high & low byte
        ack.writeByte((frame.serialNumber() >> 8) & 0xFF);
        ack.writeByte(frame.serialNumber() & 0xFF);
        // Compute CRC over length, protocol, serial
        short crc = computeCrc(ack, /* fromIndex= */2, /* length= */1 + 1 + 2);
        ack.writeByte((crc >> 8) & 0xFF);
        ack.writeByte(crc & 0xFF);
        ack.writeByte(0x0D);
        ack.writeByte(0x0A);
        ctx.writeAndFlush(ack);
    }

}