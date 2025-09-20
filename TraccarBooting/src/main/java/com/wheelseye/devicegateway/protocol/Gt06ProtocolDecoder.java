package com.wheelseye.devicegateway.protocol;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wheelseye.devicegateway.dto.DeviceStatusDto;
import com.wheelseye.devicegateway.helper.ChannelRegistry;
import com.wheelseye.devicegateway.helper.Gt06Handler;
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
import io.netty.util.AttributeKey;
import io.netty.buffer.Unpooled;

@Component
@ChannelHandler.Sharable
public class Gt06ProtocolDecoder extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Gt06ProtocolDecoder.class);

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

    private static final AttributeKey<DeviceSession> SESSION_KEY = AttributeKey.valueOf("device.session");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // private static final String UNKNOWN_IMEI = "000000000000000";
    // private static final String UNKNOWN_VARIANT = "UNKNOWN";

    private static final int COURSE_STATUS_OFFSET = 12; // Replace with actual offset in your packet structure
    private static final int VOLTAGE_MIN = 3600; // Minimum battery voltage for scaling
    private static final int VOLTAGE_MAX = 4200; // Maximum battery voltage for scaling

    private final Gt06Handler gt06Handler;
    private final Gt06FrameDecoder gt06FrameDecoder;

    public Gt06ProtocolDecoder(Gt06Handler gt06Handler, Gt06FrameDecoder gt06FrameDecoder) {
        this.gt06Handler = gt06Handler;
        this.gt06FrameDecoder = gt06FrameDecoder;
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

            MessageFrame frame = gt06FrameDecoder.parseFrame(buffer);
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

        try {
            if (isLocationType(type)) {
                logger.info("📍 LOCATION PACKET (0x{:02X}) from {}", type, addr);
                handleLocation(ctx, frame);
            } else if (isStatusType(type)) {
                logger.info("📊 STATUS PACKET (0x{:02X}) from {}", type, addr);
                handleStatus(ctx, frame);
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
            if (isGps) {

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
                    // logger.info("🔧 India region detected - correcting longitude from {} to {}",
                    // longitude, Math.abs(longitude));
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

    private void handleStatus(ChannelHandlerContext ctx, MessageFrame frame) {
        ByteBuf buf = frame.content();
        try {
            buf.resetReaderIndex();

            int readable = buf.readableBytes();
            if (readable < 10) { // minimum status packet length
                gt06Handler.publishStatus(ctx, DeviceStatusDto.getDefaultDeviceStatus());
                sendAck(ctx, frame, MSG_STATUS);
                return;
            }

            // --- Course/Status word (offset 4-5) ---
            buf.resetReaderIndex().skipBytes(4);
            int courseStatus = readable >= 6 ? buf.readUnsignedShort() : 0;
            boolean ignition = (courseStatus & 0x2000) != 0;
            boolean gpsFixed = (courseStatus & 0x1000) != 0;
            int direction = courseStatus & 0x03FF;
            boolean externalPower = (courseStatus & 0x4000) != 0;

            // --- Voltage level (offset 6) ---
            int voltageLevel = 0;
            if (readable >= 7) {
                buf.resetReaderIndex().skipBytes(6);
                voltageLevel = buf.readUnsignedByte();
            }
            int batteryVoltage = voltageLevel * 20; // scale adjustment
            int batteryPercent = Math.max(0, Math.min(100, voltageLevel * 100 / 255));

            // --- GSM signal (offset 7) ---
            // --- GSM signal (offset 7) ---
            int gsmRaw = (readable >= 8) ? buf.getUnsignedByte(buf.readerIndex() + 7) : 0;

            // GSM mapping: index = gsmRaw (1-5), values = signal dBm
            int[] gsmSignals = { -113, -103, -93, -83, -73, -63 }; // index 0 unused, 1-5 used
            int gsmLevel = (gsmRaw >= 1 && gsmRaw <= 5) ? gsmRaw : 0;
            int gsmSignal = (gsmRaw >= 1 && gsmRaw <= 5) ? gsmSignals[gsmRaw] : -113;

            // --- Terminal info / charging (offset 8) ---
            int terminalInfo = 0;
            if (readable >= 9) {
                buf.resetReaderIndex().skipBytes(8);
                terminalInfo = buf.readUnsignedByte();
            }
            boolean charging = (terminalInfo & 0x04) != 0;

            // --- Satellites ---
            int satellites = gpsFixed ? 1 : 0;

            // --- Build and publish DTO ---
            DeviceStatusDto status = new DeviceStatusDto(ignition, gpsFixed, direction,
                    satellites, externalPower,
                    charging, batteryVoltage, batteryPercent,
                    getBatteryLevelText(batteryPercent),
                    getVoltageLevelText(batteryVoltage), gsmSignal, gsmLevel, courseStatus);

            gt06Handler.publishStatus(ctx, status);
            sendAck(ctx, frame, MSG_STATUS);

        } catch (Exception e) {
            logger.error("Error handling GT06 status packet: {}", e.getMessage(), e);
            gt06Handler.publishStatus(ctx, DeviceStatusDto.getDefaultDeviceStatus());
            sendAck(ctx, frame, MSG_STATUS);
        }
    }

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

    private void handleLogin(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();

        try {
            // Validate frame length for GT06 login (minimum 8 bytes for IMEI)
            if (frame.content().readableBytes() < 8) {
                logger.warn("🔐 LOGIN frame too short from {} ({} bytes)",
                        remoteAddress, frame.content().readableBytes());
                sendAck(ctx, frame, MSG_LOGIN);
                ctx.close();
                return;
            }

            // Extract and validate IMEI from BCD-encoded bytes
            String imei = extractIMEI(frame.content());
            if (imei == null || imei.length() < 14) {
                logger.warn("🔐 Invalid IMEI from {}: {}", remoteAddress, imei);
                sendAck(ctx, frame, MSG_LOGIN);
                ctx.close();
                return;
            }

            logger.info("🔐 Login from IMEI: {} ({})", imei, remoteAddress);

            // Register/authenticate device session
            boolean registered = authenticateDevice(ctx.channel(), imei);
            if (!registered) {
                logger.warn("❌ Failed to register IMEI: {}", imei);
                sendAck(ctx, frame, MSG_LOGIN);
                ctx.close();
                return;
            }

            // Send acknowledgment
            sendAck(ctx, frame, MSG_LOGIN);
            logger.info("✅ Login successful for IMEI: {} ({})", imei, remoteAddress);

        } catch (Exception e) {
            logger.error("💥 Login error from {}: {}", remoteAddress, e.getMessage(), e);
            try {
                sendAck(ctx, frame, MSG_LOGIN);
            } catch (Exception ignore) {
                /* best-effort */ }
            ctx.close();
        }
    }

    private String extractIMEI(ByteBuf content) {
        try {
            if (content.readableBytes() < 8) {
                logger.warn("Insufficient bytes for IMEI extraction: {}", content.readableBytes());
                return null;
            }

            int readerIndex = content.readerIndex();
            byte[] data = new byte[8];
            content.getBytes(readerIndex, data);

            StringBuilder imei = new StringBuilder(16);

            for (byte b : data) {
                int high = (b & 0xF0) >>> 4;
                int low = b & 0x0F;

                if (high != 0x0F) {
                    if (high > 9) {
                        logger.warn("Invalid high nibble in IMEI byte: {}", high);
                        return null;
                    }
                    imei.append(high);
                }

                if (low != 0x0F) {
                    if (low > 9) {
                        logger.warn("Invalid low nibble in IMEI byte: {}", low);
                        return null;
                    }
                    imei.append(low);
                }
            }

            // ✅ Fix: drop leading zero if length = 16
            if (imei.length() == 16 && imei.charAt(0) == '0') {
                imei.deleteCharAt(0);
            }

            // ✅ Validate length
            if (imei.length() != 15) {
                logger.warn("Invalid IMEI length {} after decoding: {}", imei.length(), imei);
                return null;
            }

            // ✅ Validate numeric
            if (!imei.toString().matches("\\d{15}")) {
                logger.warn("IMEI contains non-digit characters: {}", imei);
                return null;
            }

            logger.info("Extracted IMEI: {}", imei);
            return imei.toString();

        } catch (Exception e) {
            logger.error("Exception during IMEI extraction", e);
            return null;
        }
    }

    private boolean authenticateDevice(Channel channel, String imeiStr) {
        try {
            IMEI imei = IMEI.of(imeiStr);

            // Retrieve or create session
            DeviceSession session = sessionService.getSession(imei)
                    .orElseGet(() -> DeviceSession.create(
                            imei,
                            channel.id().asShortText(),
                            channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown"));

            // Update channel and remote address
            session.setChannel(
                    channel.id().asShortText(),
                    channel.remoteAddress() != null ? channel.remoteAddress().toString() : "unknown");

            // Authenticate and persist
            session.authenticate();
            sessionService.saveSession(session);

            // Attach to channel for lookup
            channel.attr(SESSION_KEY).set(session);

            return true;

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid IMEI format: {}", imeiStr);
            return false;
        } catch (Exception e) {
            logger.error("Error authenticating IMEI {}: {}", imeiStr, e.getMessage(), e);
            return false;
        }
    }

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

    private String getBatteryLevelText(int batteryPercent) {
        if (batteryPercent >= 90) {
            return "Full";
        } else if (batteryPercent >= 75) {
            return "High";
        } else if (batteryPercent >= 50) {
            return "Medium";
        } else if (batteryPercent >= 25) {
            return "Low";
        } else {
            return "Critical";
        }
    }

    private String getVoltageLevelText(int batteryVoltage) {
        if (batteryVoltage >= 4100) {
            return "High";
        } else if (batteryVoltage >= 3900) {
            return "Normal";
        } else if (batteryVoltage >= 3700) {
            return "Low";
        } else {
            return "Critical";
        }
    }

    // /**
    // * Send an ACK packet back to the terminal.
    // *
    // * @param ctx the channel context
    // * @param frame the original message frame
    // * @param type the protocol number to ACK (e.g., MSGGPSLBS1, MSGSTATUS)
    // */
    // private void sendAck(ChannelHandlerContext ctx, MessageFrame frame, int type)
    // {
    // // Construct ACK: 0x78 0x78 | length=0x05 | protocol | serial(2) | CRC(2) |
    // 0x0D
    // // 0x0A

    // logger.info("➡️ Sending ACK for protocol 0x{:02X} to {}", type,
    // ctx.channel().remoteAddress());
    // ByteBuf ack = Unpooled.buffer(10);
    // ack.writeByte(0x78);
    // ack.writeByte(0x78);
    // ack.writeByte(0x05);
    // ack.writeByte(type);
    // // Serial number high & low byte
    // ack.writeByte((frame.serialNumber() >> 8) & 0xFF);
    // ack.writeByte(frame.serialNumber() & 0xFF);
    // // Compute CRC over length, protocol, serial
    // short crc = computeCrc(ack, /* fromIndex= */2, /* length= */1 + 1 + 2);
    // ack.writeByte((crc >> 8) & 0xFF);
    // ack.writeByte(crc & 0xFF);
    // ack.writeByte(0x0D);
    // ack.writeByte(0x0A);
    // ctx.writeAndFlush(ack);
    // }

    // /**
    // * Utility to compute CRC-ITU (CRC-16/X.25) over a byte array.
    // * Polynom: 0x1021, Init: 0xFFFF, RefIn/RefOut: false, XorOut: 0x0000
    // */
    // private short computeCrc(ByteBuf buf, int fromIndex, int length) {
    // int crc = 0xFFFF;
    // for (int i = fromIndex; i < fromIndex + length; i++) {
    // crc ^= (buf.getUnsignedByte(i) & 0xFF) << 8;
    // for (int j = 0; j < 8; j++) {
    // crc = (crc & 0x8000) != 0
    // ? (crc << 1) ^ 0x1021
    // : (crc << 1);
    // }
    // }
    // return (short) (crc & 0xFFFF);
    // }

    private void sendAck(ChannelHandlerContext ctx, MessageFrame frame, int protocol) {
        // Validate inputs
        if (frame == null) {
            logger.warn("Cannot send ACK — frame is null (remote: {})", ctx.channel().remoteAddress());
            return;
        }
        int serial = frame.serialNumber();
        if ((serial & ~0xFFFF) != 0) {
            logger.warn("Serial number out of range (must fit in 16 bits): {} (remote: {})", serial,
                    ctx.channel().remoteAddress());
            return;
        }

        // Typical ACK layout for GT06:
        // start(2) | length(1) | protocol(1) | serial(2) | crc(2) | end(2)
        // length usually = 0x05 for ACKs that contain protocol + serial + crc (1 + 2 +
        // 2)
        final int lengthField = 0x05;

        logger.info("➡️ Sending ACK for protocol {} to {}", String.format("0x%02X", protocol & 0xFF),
                ctx.channel().remoteAddress());

        ByteBuf ack = Unpooled.buffer(10); // exact size: 2 + 1 + 1 + 2 + 2 + 2 = 10
        // Start bytes
        ack.writeByte(0x78);
        ack.writeByte(0x78);

        // Length and protocol
        ack.writeByte(lengthField & 0xFF);
        ack.writeByte(protocol & 0xFF);

        // Serial (big-endian: high then low) — writeShort writes two bytes BE by
        // default
        ack.writeShort(serial & 0xFFFF);

        // Compute CRC over: [length][protocol][serialHigh][serialLow]
        final int crcStartIndex = 2; // index of length byte (0-based)
        final int crcLength = ack.writerIndex() - crcStartIndex; // number of bytes between length and serial inclusive
        int crc = computeCrc16Ccitt(ack, crcStartIndex, crcLength) & 0xFFFF;

        // Write CRC (two bytes)
        ack.writeShort(crc);

        // End bytes
        ack.writeByte(0x0D);
        ack.writeByte(0x0A);

        // Optionally log the ACK hex for debugging
        logger.debug("ACK -> {}", ByteBufUtil.hexDump(ack));

        ctx.writeAndFlush(ack);
    }

    /**
     * Compute CRC-16/CCITT (poly 0x1021, init 0xFFFF) over the given ByteBuf slice.
     * This routine computes CRC over bytes [fromIndex .. fromIndex+length-1].
     *
     * Many GT06 implementations expect CRC computed over the length byte up to
     * serial bytes inclusive.
     */
    private int computeCrc16Ccitt(ByteBuf buf, int fromIndex, int length) {
        int crc = 0xFFFF;
        final int end = fromIndex + length;
        for (int i = fromIndex; i < end; i++) {
            crc ^= (buf.getUnsignedByte(i) & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

}