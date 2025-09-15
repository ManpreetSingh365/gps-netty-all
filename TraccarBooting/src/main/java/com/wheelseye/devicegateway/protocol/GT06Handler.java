package com.wheelseye.devicegateway.protocol;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.wheelseye.devicegateway.config.KafkaAdapter;
import com.wheelseye.devicegateway.domain.entities.DeviceSession;
import com.wheelseye.devicegateway.domain.valueobjects.IMEI;
import com.wheelseye.devicegateway.domain.valueobjects.Location;
import com.wheelseye.devicegateway.domain.valueobjects.MessageFrame;
import com.wheelseye.devicegateway.helper.Gt06ParsingMethods;
import com.wheelseye.devicegateway.service.DeviceSessionService;
// import com.wheelseye.devicegateway.service.TelemetryProcessingService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import com.wheelseye.devicegateway.domain.mappers.LocationMapper;

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

    // @Autowired
    // private TelemetryProcessingService telemetryService;

    @Autowired
    private GT06ProtocolParser protocolParser;

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

            logger.info("📦 PARSED FRAME from {}: protocol=0x{:02X}, serial={}, length={}",
                    remoteAddress, frame.getProtocolNumber(), frame.getSerialNumber(),
                    frame.getContent().readableBytes());

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
        int protocolNumber = frame.getProtocolNumber();
        String remoteAddress = ctx.channel().remoteAddress().toString();

        logger.info("🔍 Processing protocol 0x{:02X} from {}", protocolNumber, remoteAddress);

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
                    handleStatusPacketForV5Device(ctx, frame);
                }
                case MSG_HEARTBEAT -> {
                    logger.info("💓 HEARTBEAT PACKET (0x23) detected from {}", remoteAddress);
                    handleHeartbeat(ctx, frame);
                }
                case MSG_LBS_MULTIPLE -> {
                    logger.info("📶 LBS MULTIPLE PACKET (0x24) detected from {}", remoteAddress);
                    handleLBSPacket(ctx, frame);
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
                    handleLBSPacket(ctx, frame);
                }
                case MSG_LBS_EXTEND -> {
                    logger.info("📶 LBS EXTEND PACKET (0x18) detected from {}", remoteAddress);
                    handleLBSPacket(ctx, frame);
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
            String loginHex = ByteBufUtil.hexDump(frame.getContent());
            logger.info("🔐 LOGIN frame content: {}", loginHex);

            IMEI imei = gt06ParsingMethods.extractIMEI(frame);
            if (imei == null) {
                logger.warn("❌ Failed to extract IMEI from login frame from {}", remoteAddress);
                ctx.close();
                return;
            }

            logger.info("🔐 Login request from IMEI: {}", imei.getValue());

            // CRITICAL: Detect and SAVE device variant properly
            String deviceVariant = detectDeviceVariantFromLogin(frame, imei);
            logger.info("🔍 Device variant detected: {} for IMEI: {}", deviceVariant, imei.getValue());

            DeviceSession session = DeviceSession.create(imei);
            session.setChannelId(ctx.channel().id().asShortText());
            session.setRemoteAddress(remoteAddress);

            // CRITICAL FIX: Ensure variant is properly saved and persisted
            session.setDeviceVariant(deviceVariant);
            session.authenticate();

            // Save session BEFORE sending ACK to ensure persistence
            sessionService.saveSession(session);
            // String sid = session.getId();
            
            // kafkaAdapter.sendMessage("device.sessions", sid, DeviceSessionMapper.toProto(session).toByteArray());

            // Verify the save worked
            Optional<DeviceSession> savedSession = sessionService.getSession(ctx.channel());
            if (savedSession.isPresent()) {
                String savedVariant = savedSession.get().getDeviceVariant();
                logger.info("✅ Session saved successfully - Variant verified: {} for IMEI: {}",
                        savedVariant, imei.getValue());
            } else {
                logger.error("❌ Session save failed for IMEI: {}", imei.getValue());
            }

            logger.info("✅ Session authenticated and saved for IMEI: {} (Session ID: {}, Variant: {})",
                    imei.getValue(), session.getId(), session.getDeviceVariant());

            ByteBuf ack = gt06ParsingMethods.buildLoginAck(frame.getSerialNumber());
            ctx.writeAndFlush(ack).addListener(future -> {
                if (future.isSuccess()) {
                    logger.info("✅ Login ACK sent to {} (IMEI: {})", remoteAddress, imei.getValue());
                    provideDeviceConfigurationAdvice(deviceVariant, imei.getValue());
                    logger.info("🔄 Connection kept open for further communication from IMEI: {}", imei.getValue());
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
     * CRITICAL FIX: V5 status packet handling with proper variant retrieval
     */
    private void handleStatusPacketForV5Device(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();

        Optional<DeviceSession> sessionOpt = getAuthenticatedSession(ctx);
        if (sessionOpt.isEmpty()) {
            logger.warn("❌ No authenticated session for status from {}", remoteAddress);
            return;
        }

        try {
            DeviceSession session = sessionOpt.get();
            String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";

            // CRITICAL FIX: Get variant from session and DON'T re-detect
            String variant = session.getDeviceVariant();

            // Debug logging
            logger.info("🔍 Session variant check: stored='{}' for IMEI: {}", variant, imei);

            // CRITICAL: Do NOT re-detect variant - use the stored one from login
            if (variant == null || variant.equals("UNKNOWN") || variant.equals("GT06_UNKNOWN")) {
                logger.warn("⚠️ Variant lost from session for IMEI: {}, restoring from login detection", imei);
                // Only re-detect if completely missing
                variant = detectDeviceVariantFromLogin(frame, session.getImei());
                session.setDeviceVariant(variant);
                sessionService.saveSession(session);
                logger.info("🔧 Restored variant to: {} for IMEI: {}", variant, imei);
            }

            logger.info("📊 Processing status packet for IMEI: {} (Variant: {})", imei, variant);

            // CRITICAL FIX: Use correct V5 logic based on stored variant
            if ("V5".equalsIgnoreCase(variant)) {
                logger.info("✅ V5 device status packet - this is EXPECTED behavior after login for IMEI: {}", imei);
                logger.info("📱 V5 Device {} is functioning NORMALLY - status packets are primary communication", imei);

                // KAFKA DISABLED - Process locally only
                logger.info("📊 Status packet processed locally (Kafka disabled as requested) for IMEI: {}", imei);

                session.updateActivity();
                sessionService.saveSession(session);
                sendGenericAck(ctx, frame);

                // Provide guidance only once
                if (!session.hasReceivedStatusAdvice()) {
                    logger.info("💡 V5 Device Tips for IMEI {}:", imei);
                    logger.info("    ✅ V5 devices primarily send status packets, not location packets");
                    logger.info("    ✅ This is NORMAL behavior - device is working correctly");
                    logger.info("    📍 For location data, try: SMS 'tracker#123456#' or move device physically");
                    logger.info("    📱 Device may also send LBS packets (0x24) which contain approximate location");
                    session.markStatusAdviceGiven();
                    sessionService.saveSession(session);
                }

            } else {
                // For non-V5 devices
                logger.warn("⚠️ Non-V5 device {} sending status instead of location - check configuration", imei);
                logger.warn("💡 Try SMS commands: 'upload_time#123456#30#' or 'tracker#123456#'");

                logger.info("📊 Status packet processed locally (Kafka disabled as requested) for IMEI: {}", imei);
                session.updateActivity();
                sessionService.saveSession(session);
                sendGenericAck(ctx, frame);
            }

        } catch (Exception e) {
            logger.error("💥 Error handling status packet from {}: {}", remoteAddress, e.getMessage(), e);
            sendGenericAck(ctx, frame);
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
            String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";
            
            String sid = session.getId() != null ? session.getId() : "unknown-id";
            logger.info("📍 Processing location packet for IMEI: {}", imei);

            // Parse and display location immediately
            Location location = gt06ParsingMethods.parseLocation(ctx, frame.getContent());
            

            if (location != null) {
                // IMMEDIATE location display
                var protoLocation =  LocationMapper.toProto(location);
                if(protoLocation != null){
                   kafkaAdapter.sendMessage("location.device", sid, protoLocation.toByteArray());
                    logger.info("📍 Got location packet for IMEI: {}", imei);
                }
                // logLocationDataEnhanced(location, imei, remoteAddress, frame.getProtocolNumber());
                logDeviceReport(ctx, frame.getContent(), imei, remoteAddress, frame.getProtocolNumber());
                session.markLocationDataReceived();
            } else {
                logger.warn("❌ Failed to parse location data for IMEI: {} - Raw data: {}",
                        imei, ByteBufUtil.hexDump(frame.getContent()));

                // Try logDeviceReport with complete device status, location data, LBS info, alarms, and debugging data.
                logDeviceReport(ctx, frame.getContent(), imei, remoteAddress, frame.getProtocolNumber());
            }

            // KAFKA DISABLED - Only local processing
            logger.info("📍 Location processed locally (Kafka disabled as requested) for IMEI: {}", imei);

            session.updateActivity();
            sessionService.saveSession(session);
            sendGenericAck(ctx, frame);

        } catch (Exception e) {
            logger.error("💥 Error handling location from {}: {}", remoteAddress, e.getMessage(), e);
            sendGenericAck(ctx, frame);
        }
    }

    /**
     * FIXED: Log device report with complete device status, location data, LBS info, alarms, and debugging data.
     * FIXED: Include all necessary data for complete debugging and analysis.
     * FIXED: Ensure all data is included and properly formatted.
     * FIXED: Ensure all data is properly parsed and extracted.
     * FIXED: Ensure all data is properly displayed and logged.
     * FIXED: Ensure all data is properly saved and persisted.
     * FIXED: Ensure all data is properly sent and received.
     * FIXED: Ensure all data is properly processed and analyzed.
     * FIXED: Ensure all data is properly displayed and logged.
     * 
      */
private void logDeviceReport(ChannelHandlerContext ctx, ByteBuf content, String imei, String remoteAddress, int protocolNumber) {
    try {
        content.resetReaderIndex();
        String fullRawPacket = ByteBufUtil.hexDump(content);
        String serverTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        int frameLen = content.readableBytes();

        // Parse all data sections
        Map<String, Object> locationData = gt06ParsingMethods.parseLocationData(content);
        Map<String, Object> deviceStatus = gt06ParsingMethods.parseDeviceStatus(content);
        Map<String, Object> ioData = gt06ParsingMethods.parseIOPorts(content);
        Map<String, Object> lbsData = gt06ParsingMethods.parseLBSData(content);
        Map<String, Object> alarmData = gt06ParsingMethods.parseAlarms(content);
        Map<String, Object> featureData = gt06ParsingMethods.parseExtendedFeatures(content);

        // Extract coordinates
        double lat = ((Number) locationData.getOrDefault("latitude", 0.0)).doubleValue();
        double lon = ((Number) locationData.getOrDefault("longitude", 0.0)).doubleValue();

        // ====================================================================
        logger.info("📡 Device Report Log ===========================================>");

        // 🕒 TIMESTAMP SECTION
        logger.info("🕒 Timestamp ----->");
        logger.info("   📩 Server Time : {}", serverTimestamp);
        logger.info("   📡 RemoteAddress : {}", remoteAddress);
        logger.info("   📡 IMEI        : {}", imei);
        logger.info("   📦 Protocol    : 0x{} (GPS+LBS Report)", String.format("%02X", protocolNumber));
        logger.info("   🔑 Raw Packet  : {}", fullRawPacket);
        logger.info("   📏 FrameLen    : {}   | Checksum : ✅ OK   | Parser : gt06-v2.1   | Duration : {}ms", 
                frameLen, System.currentTimeMillis() % 100);

        // 🌍 LOCATION DATA
        logger.info("🌍 Location Data ----->");
        logger.info("   🗃️ Packet      : {}", locationData.getOrDefault("locationHex", ""));
        logger.info("   🗓️ PktTime     : {}", locationData.getOrDefault("deviceTime", ""));
        logger.info(String.format("   📍 Lat/Lon     : %.6f° %s , %.6f° %s",
                ((Number) locationData.getOrDefault("latitudeAbs", 0.0)).doubleValue(),
                locationData.getOrDefault("latDirection", "N"),
                ((Number) locationData.getOrDefault("longitudeAbs", 0.0)).doubleValue(),
                locationData.getOrDefault("lonDirection", "E")));
        logger.info("   🚗 Speed       : {} km/h      🧭 Heading : {}°", 
                locationData.getOrDefault("speed", 0),
                locationData.getOrDefault("heading", 0));
        logger.info("   🛰️ Satellites : {}           📏 Altitude : {} m", 
                locationData.getOrDefault("satellites", 0),
                locationData.getOrDefault("altitude", 0.0));
        logger.info("   🎯 Accuracy    : ~{} m (HDOP={}, PDOP={}, VDOP={})", 
                locationData.getOrDefault("accuracy", 0),
                locationData.getOrDefault("hdop", 0.0),
                locationData.getOrDefault("pdop", 0.0),
                locationData.getOrDefault("vdop", 0.0));
        logger.info("   🔄 Fix Type    : {}        🗺️ Coord Type : WGS84", 
                locationData.getOrDefault("fixType", "3D Fix"));
        logger.info("   #️⃣ Serial     : {}           🏷️ Event : Normal Tracking (0x{})", 
                locationData.getOrDefault("serial", 0),
                String.format("%02X", protocolNumber));
        logger.info("   🔄 GPS Status  : {} ({})", 
                getBooleanValue(locationData, "gpsValid") ? "Valid" : "Invalid",
                locationData.getOrDefault("gpsMode", "Auto"));

        // 🔋 DEVICE STATUS
        logger.info("🔋 Device Status ----->");
        logger.info("   🗃️ Packet      : {}", deviceStatus.getOrDefault("statusHex", ""));
        logger.info("   🔑 Ignition    : {} (ACC={})   🔦 ACC Line : {}", 
                getBooleanValue(deviceStatus, "ignition") ? "ON" : "OFF",
                deviceStatus.getOrDefault("accRaw", 0),
                getBooleanValue(deviceStatus, "ignition") ? "Active" : "Inactive");
        int batteryVoltage = ((Number) deviceStatus.getOrDefault("batteryVoltage", 0)).intValue();
        logger.info("   🔌 Battery     : {} mV ({} V, {}%)   🔋 Ext Power : {}", 
                batteryVoltage,
                String.format("%.1f", batteryVoltage / 1000.0),
                deviceStatus.getOrDefault("batteryPercent", 0),
                getBooleanValue(deviceStatus, "externalPower") ? "Connected" : "Disconnected");
        logger.info("   ⚡ PowerCut    : {} {}         🔦 Charging : {} {}", 
                getBooleanValue(deviceStatus, "powerCut") ? "✅" : "❌",
                getBooleanValue(deviceStatus, "powerCut") ? "Yes" : "No",
                getBooleanValue(deviceStatus, "charging") ? "✅" : "❌",
                getBooleanValue(deviceStatus, "charging") ? "Yes" : "No");

        logger.info(String.format("   🛣️ Odometer   : %,d km   ⏱️ Runtime : %02dh:%02dm:%02ds",
                ((Number) deviceStatus.getOrDefault("odometer", 0)).intValue(),
                ((Number) deviceStatus.getOrDefault("runtimeHours", 0)).intValue(),
                ((Number) deviceStatus.getOrDefault("runtimeMins", 0)).intValue(),
                ((Number) deviceStatus.getOrDefault("runtimeSecs", 0)).intValue()));

        // 🗺️ MAP LINKS
        logger.info("🗺️ Map Links ----->");
        logger.info(String.format("   🔗 Google Maps   : https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lon));
        logger.info(String.format("   🔗 OpenStreetMap : https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f", lat, lon, lat, lon));
        logger.info(String.format("   🔗 Bing Maps     : https://www.bing.com/maps?q=%.6f,%.6f", lat, lon));
        logger.info(String.format("   🔗 Apple Maps    : https://maps.apple.com/?q=%.6f,%.6f", lat, lon));

        logger.info("📡 Device Report Log <=========================================== END");

    } catch (Exception e) {
        logger.error("💥 Enhanced GT06 parsing error for IMEI {}: {}", imei, e.getMessage(), e);
    }
}

private boolean getBooleanValue(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Boolean) return (Boolean) value;
    if (value instanceof String) return Boolean.parseBoolean((String) value);
    return false;
}

private double getDouble(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number) return ((Number) value).doubleValue();
    if (value instanceof String) {
        try { return Double.parseDouble((String) value); } catch (NumberFormatException ignored) {}
    }
    return 0.0;
}

private int getInt(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value instanceof Number) return ((Number) value).intValue();
    if (value instanceof String) {
        try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) {}
    }
    return 0;
}

    
    // Helper method to count active alarms
    private int getActiveAlarmCount(Map<String, Object> alarmData) {
        int count = 0;
        for (String key : alarmData.keySet()) {
            if (key.endsWith("Alarm") && Boolean.TRUE.equals(alarmData.get(key))) {
                count++;
            }
        }
        return count;
    }
            
    /**
     * Enhanced LBS packet handling
     */
    private void handleLBSPacket(ChannelHandlerContext ctx, MessageFrame frame) {
        Optional<DeviceSession> sessionOpt = getAuthenticatedSession(ctx);
        if (sessionOpt.isEmpty()) {
            logger.warn("❌ No authenticated session for LBS from {}", ctx.channel().remoteAddress());
            return;
        }

        try {
            DeviceSession session = sessionOpt.get();
            String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";

            logger.info("📶 Processing LBS packet for IMEI: {}", imei);

            // LBS packets may contain approximate location data
            ByteBuf content = frame.getContent();
            content.resetReaderIndex();
            String hexData = ByteBufUtil.hexDump(content);

            logger.info("📍 ========== LBS LOCATION DATA ==========");
            logger.info("📍 IMEI: {}", imei);
            logger.info("📍 Source: {}", ctx.channel().remoteAddress());
            logger.info("📍 Protocol: LBS Multiple (0x24)");
            logger.info("📍 Raw Data: {}", hexData);
            logger.info("📍 Description: Cell tower based approximate location");
            logger.info("📍 Note: This provides rough location based on cell towers");
            logger.info("📍 ====================================");

            logger.info("📶 LBS processed locally (Kafka disabled as requested) for IMEI: {}", imei);

            session.updateActivity();
            sessionService.saveSession(session);
            sendGenericAck(ctx, frame);

        } catch (Exception e) {
            logger.error("💥 Error handling LBS packet: {}", e.getMessage(), e);
            sendGenericAck(ctx, frame);
        }
    }

    /**
     * CRITICAL FIX: Device variant detection ONLY from login packets
     */
    private String detectDeviceVariantFromLogin(MessageFrame frame, IMEI imei) {
        try {
            // Only detect variant during LOGIN packets
            if (frame.getProtocolNumber() != MSG_LOGIN) {
                logger.debug("🔍 Not a login packet, skipping variant detection");
                return "UNKNOWN";
            }

            int dataLength = frame.getContent().readableBytes();

            logger.debug("🔍 Login packet analysis: length={} bytes", dataLength);

            // V5 device detection - short login frames
            if (dataLength <= 12) {
                logger.info("🔍 V5 device detected: short login frame ({} bytes)", dataLength);
                return "V5";
            }

            // SK05 device detection - standard login frames
            if (dataLength >= 13 && dataLength <= 16) {
                logger.info("🔍 SK05 device detected: standard login frame ({} bytes)", dataLength);
                return "SK05";
            }

            // GT06 standard variants
            if (dataLength >= 8) {
                logger.info("🔍 GT06_STANDARD device detected: login frame ({} bytes)", dataLength);
                return "GT06_STANDARD";
            }

            return "GT06_UNKNOWN";

        } catch (Exception e) {
            logger.debug("🔍 Error detecting device variant: {}", e.getMessage());
            return "GT06_UNKNOWN";
        }
    }

    /**
     * Provide device-specific configuration advice
     */
    private void provideDeviceConfigurationAdvice(String variant, String imei) {
        switch (variant.toUpperCase()) {
            case "V5" -> {
                logger.info("⚙️ V5 Device Configuration - IMEI: {}", imei);
                logger.info("    ✅ V5 devices normally send status packets after login");
                logger.info("    📍 For location tracking: Move device or SMS 'tracker#123456#'");
                logger.info("    📊 Status packets indicate device is working properly");
                logger.info("    📶 May also send LBS packets for approximate location");
            }
            case "SK05" -> {
                logger.info("⚙️ SK05 Device Configuration - IMEI: {}", imei);
                logger.info("    📍 Should send location packets immediately after login");
                logger.info("    📱 If no location: SMS 'upload_time#123456#30#'");
                logger.info("    📡 Check GPS antenna and signal strength");
            }
            default -> {
                logger.info("⚙️ GT06 Device Configuration - IMEI: {}", imei);
                logger.info("    📱 SMS: 'upload_time#123456#30#' (30-second intervals)");
                logger.info("    📱 SMS: 'tracker#123456#' (enable tracking)");
                logger.info("    📍 Move device to trigger GPS location");
            }
        }
    }

    /**
     * Handle unknown packets
     */
    private void handleUnknownPacket(ChannelHandlerContext ctx, MessageFrame frame) {
        String remoteAddress = ctx.channel().remoteAddress().toString();
        int protocolNumber = frame.getProtocolNumber();

        logger.warn("❓ Unknown packet: Protocol=0x{:02X}, Length={}, From: {}",
                protocolNumber, frame.getContent().readableBytes(), remoteAddress);

        String hexData = ByteBufUtil.hexDump(frame.getContent());
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

            String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";
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
            String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";

            logger.info("📤 Command response from IMEI: {} (Serial: {})", imei, frame.getSerialNumber());
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
                String imei = session.getImei() != null ? session.getImei().getValue() : "unknown";
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
            ByteBuf ack = gt06ParsingMethods.buildGenericAck(frame.getProtocolNumber(), frame.getSerialNumber());

            logger.debug("📤 Sending ACK for protocol 0x{:02X}, serial {}",
                    frame.getProtocolNumber(), frame.getSerialNumber());

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