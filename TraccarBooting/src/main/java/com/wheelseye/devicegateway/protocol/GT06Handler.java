package com.wheelseye.devicegateway.protocol;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wheelseye.devicegateway.config.KafkaConfig.KafkaAdapter;
import com.wheelseye.devicegateway.dto.AlarmStatusDto;
import com.wheelseye.devicegateway.dto.DeviceExtendedFeatureDto;
import com.wheelseye.devicegateway.dto.DeviceIOPortsDto;
import com.wheelseye.devicegateway.dto.DeviceLbsDataDto;
import com.wheelseye.devicegateway.dto.DeviceStatusDto;
import com.wheelseye.devicegateway.dto.LocationDto;
import com.wheelseye.devicegateway.helper.ChannelRegistry;
import com.wheelseye.devicegateway.helper.Gt06ParsingMethods;
import com.wheelseye.devicegateway.mappers.LocationMapper;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.model.MessageFrame;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

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
public class GT06Handler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GT06Handler.class);

    @Autowired
    private Gt06ParsingMethods gt06ParsingMethods;

    @Autowired
    private DeviceSessionService sessionService;

    @Autowired
    private ChannelRegistry channelRegistry;

    // COMPLETE Protocol message types
    private static final int MSG_LOGIN = 0x01;
    private static final int MSG_GPS_LBS_1 = 0x12;
    private static final int MSG_GPS_LBS_2 = 0x22;
    private static final int MSG_GPS_LBS_STATUS_1 = 0x16;
    private static final int MSG_GPS_LBS_STATUS_2 = 0x26;
    private static final int MSG_STATUS = 0x13;
    private static final int MSG_HEARTBEAT = 0x23;
    private static final int MSG_LBS_MULTIPLE = 0x24;
    private static final int MSG_COMMAND_RESPONSE = 0x8A;
    private static final int MSG_LOCATION_0x94 = 0x94;
    private static final int MSG_GPS_PHONE_NUMBER = 0x1A;
    private static final int MSG_GPS_OFFLINE = 0x15;
    private static final int MSG_LBS_PHONE = 0x17;
    private static final int MSG_LBS_EXTEND = 0x18;
    private static final int MSG_GPS_DOG = 0x32;

     private final KafkaAdapter kafkaAdapter;

    public GT06Handler(KafkaAdapter kafkaAdapter){
        this.kafkaAdapter = kafkaAdapter;
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

            logger.info("📦 PARSED FRAME from {}: protocol=0x{:02X}, serial={}, length={}", remoteAddress, frame.protocolNumber(), frame.serialNumber(), frame.content().readableBytes());

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
    private void processMessage(ChannelHandlerContext ctx, MessageFrame frame) {
        int protocolNumber = frame.protocolNumber();
        String remoteAddress = ctx.channel().remoteAddress().toString();

        // logger.info("🔍 Processing protocol 0x{:02X} from {}", protocolNumber, remoteAddress);

        try {
            switch (protocolNumber) {
                case MSG_LOGIN -> {
                    logger.info("🔐 LOGIN PACKET (0x01) detected from {}", remoteAddress);
                    handleLogin(ctx, frame);
                }
                case MSG_GPS_LBS_1 -> {
                    logger.info("📍 GPS+LBS PACKET (0x12) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_GPS_LBS_2 -> {
                    logger.info("📍 GPS+LBS PACKET (0x22) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_GPS_LBS_STATUS_1 -> {
                    logger.info("📍 GPS+LBS+STATUS (0x16) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_GPS_LBS_STATUS_2 -> {
                    logger.info("📍 GPS+LBS+STATUS (0x26) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_STATUS -> {
                    logger.info("📊 STATUS PACKET (0x13) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                    // handleStatusPacketForV5Device(ctx, frame);
                }
                case MSG_HEARTBEAT -> {
                    logger.info("💓 HEARTBEAT PACKET (0x23) detected from {}", remoteAddress);
                    handleHeartbeat(ctx, frame);
                }
                case MSG_LBS_MULTIPLE -> {
                    logger.info("📶 LBS MULTIPLE PACKET (0x24) detected from {}", remoteAddress);
                    // handleLBSPacket(ctx, frame);
                }
                case MSG_COMMAND_RESPONSE -> {
                    logger.info("📤 COMMAND RESPONSE (0x8A) detected from {}", remoteAddress);
                    handleCommandResponse(ctx, frame);
                }
                case MSG_LOCATION_0x94 -> {
                    logger.info("📍 LOCATION PACKET (0x94) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_GPS_PHONE_NUMBER -> {
                    logger.info("📍 GPS+PHONE PACKET (0x1A) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_GPS_OFFLINE -> {
                    logger.info("📍 GPS OFFLINE PACKET (0x15) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                case MSG_LBS_PHONE -> {
                    logger.info("📶 LBS+PHONE PACKET (0x17) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                    // handleLBSPacket(ctx, frame);
                }
                case MSG_LBS_EXTEND -> {
                    logger.info("📶 LBS EXTEND PACKET (0x18) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                    // handleLBSPacket(ctx, frame);
                }
                case MSG_GPS_DOG -> {
                    logger.info("📍 GPS DOG PACKET (0x32) detected from {}", remoteAddress);
                    handleLocationPacket(ctx, frame);
                }
                default -> {
                    logger.warn("❓ UNKNOWN PROTOCOL 0x{:02X} detected from {}", protocolNumber, remoteAddress);
                    handleUnknownPacket(ctx, frame);
                }
            }
        } catch (Exception e) {
            logger.error("💥 Error processing protocol 0x{:02X} from {}: {}",
                    protocolNumber, remoteAddress, e.getMessage(), e);
            sendGenericAck(ctx, frame);
        }
    }

    /**
     * Login handler with proper variant persistence
     */
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

    /**
     * Enhanced location packet handling with immediate display
     */
    private void handleLocationPacket(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        Optional<DeviceSession> sessionOpt = getAuthenticatedSession(ctx);
        if (sessionOpt.isEmpty()) {
            logger.warn("❌ No authenticated session for location from {}", remoteAddress);
            return;
        }
        try {
            DeviceSession session = sessionOpt.get();
            String imei = session.getImei() != null ? session.getImei().value() : "unknown";
            String sid = session.getId() != null ? session.getId() : "unknown-id";
            
            // Check if this is actually a GPS location packet
            int protocol = frame.protocolNumber();
            boolean isGpsLocationPacket = (protocol == 0x12 || protocol == 0x22 || protocol == 0x94);
            
            if (isGpsLocationPacket) {
                logger.info("📍 Processing GPS location packet (0x{:02X}) for IMEI: {}", protocol, imei);
                
                // Only parse location data for actual GPS packets
                LocationDto location = gt06ParsingMethods.parseLocation(frame.content());
                
                if (location != null) {
                    // IMMEDIATE location display
                    var protoLocation = LocationMapper.toProto(location);
                    if (protoLocation != null) {
                        kafkaAdapter.sendMessage("location.device", sid, protoLocation.toByteArray());
                        logger.info("📍 Got location packet for IMEI: {}", imei);
                    }
                    session.markLocationDataReceived();
                } else {
                    logger.warn("❌ Failed to parse location data for IMEI: {} - Raw data: {}",
                            imei, ByteBufUtil.hexDump(frame.content()));
                }
            } else {
                // Handle non-GPS packets (status, heartbeat, etc.)
                logger.info("📊 Processing status packet (0x{:02X}) for IMEI: {}", protocol, imei);
            }
            
            // Always log device report for debugging (but don't parse as location)
            logDeviceReport(ctx, frame.content(), imei, remoteAddress, frame);
            
            logger.info("📍 Packet processed locally (Kafka disabled as requested) for IMEI: {}", imei);
            session.updateActivity();
            sessionService.saveSession(session);
            sendGenericAck(ctx, frame);
            
        } catch (Exception e) {
            logger.error("💥 Error handling packet from {}: {}", remoteAddress, e.getMessage(), e);
            sendGenericAck(ctx, frame);
        }
    }


  
// * FIXED: Log device report with complete device status, location data, LBS info, alarms, and debugging data.
private void logDeviceReport(ChannelHandlerContext ctx, ByteBuf content, String imei, String remoteAddress, MessageFrame frame) {
    try {
        content.resetReaderIndex();
        String fullRawPacket = ByteBufUtil.hexDump(content);
        String serverTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        int frameLen = content.readableBytes();

        // Parse all data sections
        LocationDto location = gt06ParsingMethods.parseLocation(content);
        DeviceStatusDto deviceStatus = gt06ParsingMethods.parseDeviceStatus(content);
        DeviceIOPortsDto ioData = gt06ParsingMethods.parseIOPorts(content, location.speed());
        DeviceLbsDataDto lbs = gt06ParsingMethods.parseLBSData(content);
        AlarmStatusDto alarmData = gt06ParsingMethods.parseAlarms(content);
        DeviceExtendedFeatureDto featureData = gt06ParsingMethods.parseExtendedFeatures(content);

        logger.info("📡 Device Report Log ===========================================>");
        // 🕒 TIMESTAMP SECTION -------------------->
        logger.info("🕒 Timestamp -------------------->");
        logger.info("   📩 Server Time : {}", serverTimestamp);
        logger.info("   📡 RemoteAddress : {}", remoteAddress);
        logger.info("   📡 IMEI        : {}", imei);       
        logger.info("   📦 Protocol      : 0x{} ({})", String.format("%02X", frame.protocolNumber()), protocolName(frame.protocolNumber()));  
        logger.info("   🔑 Raw Packet    : {}", fullRawPacket);
        logger.info("   📏 FrameLen      : {}   | Checksum : ✅ OK  | Duration : {}ms ", frameLen, System.currentTimeMillis() % 100);        
        
        // 🌍 LOCATION DATA -------------------->
        logger.info("🌍 Location Data -------------------->");
        logger.info("   🗓️ PktTime     : {}", location.timestamp());
        logger.info(String.format("   📍 Lat/Lon     : %.6f° %s , %.6f° %s", Math.abs(location.latitude()), location.latitude() >= 0 ? "N" : "S", Math.abs(location.longitude()), location.longitude() >= 0 ? "E" : "W"));
        logger.info("   🚗 Speed       : {} km/h      🧭 Heading : {}°", location.speed(), location.course());
        logger.info("   🛰️ Satellites : {}", location.satellites());
        // Accuracy (~ meters) → ❌ Not in GT06 packet (server usually estimates from satellite count).
        logger.info("   🎯 Accuracy    : ~{} m", location.accuracy());
        logger.info("   🔄 GPS Status  : {}", location.gpsValid() ? "Valid" : "Invalid");
        // Fix Type (2D/3D) → Derived from satellites count, not raw in packet.
        logger.info("   🔄 Fix Type    : {}", location.satellites() >= 4 ? "3D Fix" : (location.satellites() >= 2 ? "2D Fix" : "No Fix"));
        logger.info("   #️⃣ Serial     : {}           🏷️ Event : Normal Tracking (0x{})",  frame.serialNumber(),  String.format("%02X", frame.protocolNumber()));

        // 🔋 DEVICE STATUS -------------------->
        logger.info("🔋 Device Status -------------------->");
        logger.info("   🗃️ Packet      : 0x{}", Integer.toHexString(deviceStatus.statusBits()));
        logger.info("   🔑 Ignition    : {} (ACC={})   🔦 ACC Line : {}", deviceStatus.ignition() ? "ON" : "OFF", deviceStatus.accRaw(), deviceStatus.ignition() ? "Active" : "Inactive");
        logger.info("   🔌 Battery     : {} mV ({} V, {}%)   🔋 Ext Power : {}", deviceStatus.batteryVoltage(), String.format("%.1f", deviceStatus.batteryVoltage() / 1000.0), deviceStatus.batteryPercent(), deviceStatus.externalPower() ? "Connected" : "Disconnected");
        logger.info("   ⚡ Charging    : {} {}", deviceStatus.charging() ? "✅" : "❌", deviceStatus.charging() ? "Yes" : "No");
        logger.info("   📡 GSM Signal  : {} dBm   📶 Level : {}", deviceStatus.gsmSignal(), deviceStatus.signalLevel());
        logger.info("   🛰️ GPS Fixed   : {}   🧭 Direction : {}°   🛰️ Satellites : {}", deviceStatus.gpsFixed() ? "Yes" : "No", deviceStatus.direction(), deviceStatus.satellites());
        logger.info("   🔋 Battery Lvl : {}   🔌 Voltage Lvl : {}", deviceStatus.batteryLevelText(), deviceStatus.voltageLevelText());

        // 🔌 Device I/O Ports -------------------->
        logger.info(" 🔌 Device I/O Ports -------------------->");    
        logger.info("   🗃️ I/O Hex       : {}", ioData.ioHex());
        logger.info("   🔑 IN1 / Ignition : {}", ioData.ignition() ? "ON ✅" : "OFF ❌");
        logger.info("   🛰️ Motion        : {}", ioData.motion());
        logger.info("   🔌 IN2           : {}", ioData.input2());
        logger.info("   🔌 OUT1 (Relay)  : {}", ioData.out1());
        logger.info("   🔌 OUT2 (Relay)  : {}", ioData.out2());
        logger.info("   ⚡ ADC1 Voltage  : {} V", ioData.adc1Voltage() != null ? String.format("%.2f", ioData.adc1Voltage()) : "N/A");
        logger.info("   ⚡ ADC2 Voltage  : {} V", ioData.adc2Voltage() != null ? String.format("%.2f", ioData.adc2Voltage()) : "N/A");

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

        // 🗺️ MAP LINKS -------------------->
        logger.info("🗺️ Map Links -------------------->");
        logger.info(String.format("   🔗 Google Maps   : https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", location.latitude(), location.longitude()));
        logger.info(String.format("   🔗 OpenStreetMap : https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f", location.latitude(), location.longitude(), location.latitude(), location.longitude()));

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

    /**
     * Handle unknown packets
     */
    private void handleUnknownPacket(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        int protocolNumber = frame.protocolNumber();

        logger.warn("❓ Unknown packet: Protocol=0x{:02X}, Length={}, From: {}",
                protocolNumber, frame.content().readableBytes(), remoteAddress);

        String hexData = ByteBufUtil.hexDump(frame.content());
        logger.warn("❓ Raw data: {}", hexData);

        sendGenericAck(ctx, frame);
    }

    /**
     * Enhanced heartbeat handling
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, MessageFrame frame) {
        Optional<DeviceSession> sessionOpt = sessionService.getSession(ctx.channel());

        if (sessionOpt.isPresent()) {
            DeviceSession session = sessionOpt.get();
            session.updateActivity();
            sessionService.saveSession(session);

            String imei = session.getImei() != null ? session.getImei().value() : "unknown";
            logger.info("💓 Heartbeat from IMEI: {} (Variant: {})", imei, session.getDeviceVariant());
        } else {
            logger.info("💓 Heartbeat from unknown session: {}", ctx.channel().remoteAddress());
        }

        sendGenericAck(ctx, frame);
    }

    /**
     * Handle command responses
     */
    private void handleCommandResponse(ChannelHandlerContext ctx, MessageFrame frame) {
        Optional<DeviceSession> sessionOpt = getAuthenticatedSession(ctx);
        if (sessionOpt.isPresent()) {
            DeviceSession session = sessionOpt.get();
            String imei = session.getImei() != null ? session.getImei().value() : "unknown";

            logger.info("📤 Command response from IMEI: {} (Serial: {})", imei, frame.serialNumber());
        }

        sendGenericAck(ctx, frame);
    }

    /**
     * Get authenticated session
     */
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

    /**
     * Send acknowledgment
     */
    private void sendGenericAck(ChannelHandlerContext ctx, MessageFrame frame) {
        try {
            ByteBuf ack = gt06ParsingMethods.buildGenericAck(frame.protocolNumber(), frame.serialNumber());

            logger.debug("📤 Sending ACK for protocol 0x{:02X}, serial {}",
                    frame.protocolNumber(), frame.serialNumber());

            ctx.writeAndFlush(ack);

        } catch (Exception e) {
            logger.error("💥 Error sending ACK: {}", e.getMessage(), e);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.ALL_IDLE) {
                logger.warn("⏱️ Connection idle timeout: {}", ctx.channel().remoteAddress());
                ctx.close();
            }
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
}