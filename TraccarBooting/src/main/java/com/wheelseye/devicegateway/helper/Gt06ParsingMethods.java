// package com.wheelseye.devicegateway.helper;

// import com.wheelseye.devicegateway.dto.AlarmStatusDto;
// import com.wheelseye.devicegateway.dto.DeviceExtendedFeatureDto;
// import com.wheelseye.devicegateway.dto.DeviceIOPortsDto;
// import com.wheelseye.devicegateway.dto.DeviceLbsDataDto;
// // import com.wheelseye.devicegateway.model.MessageFrame;

// import java.time.LocalDateTime;
// import java.time.format.DateTimeFormatter;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
// import org.springframework.stereotype.Component;
// import io.netty.buffer.ByteBuf;
// import io.netty.buffer.ByteBufUtil;
// import io.netty.buffer.Unpooled;
// import io.netty.channel.ChannelHandlerContext;

// @Component
// public class Gt06ParsingMethods {

//     private static final Logger logger = LoggerFactory.getLogger(Gt06ParsingMethods.class);

//     public DeviceIOPortsDto parseIOPorts(ByteBuf content, double gpsSpeed) {
//         try {
//             content.resetReaderIndex();

//             // --- Defaults ---
//             String ioHex = "N/A";
//             boolean input2 = false;
//             String out1 = "OFF";
//             String out2 = "OFF";
//             Double adc1Voltage = null;
//             Double adc2Voltage = null;

//             // --- I/O bytes (usually at offset 20) ---
//             if (content.readableBytes() > 20) {
//                 content.skipBytes(20);
//                 if (content.readableBytes() >= 4) {
//                     byte[] ioBytes = new byte[4];
//                     content.readBytes(ioBytes);
//                     ioHex = ByteBufUtil.hexDump(ioBytes);

//                     // Digital inputs
//                     input2 = (ioBytes[0] & 0x02) != 0;

//                     // Relay outputs
//                     out1 = (ioBytes[0] & 0x04) != 0 ? "ON" : "OFF";
//                     out2 = (ioBytes[0] & 0x08) != 0 ? "ON" : "OFF";

//                     // ADCs scaled 0–5V
//                     adc1Voltage = (ioBytes[1] & 0xFF) * 5.0 / 255.0;
//                     adc2Voltage = (ioBytes[2] & 0xFF) * 5.0 / 255.0;
//                 }
//             }

//             // --- Ignition / IN1 from course/status word ---
//             content.resetReaderIndex();
//             boolean ignition = false;
//             if (content.readableBytes() >= 19) {
//                 content.skipBytes(17);
//                 int courseStatus = content.readUnsignedShort();
//                 ignition = (courseStatus & 0x2000) != 0;
//             }

//             // --- Motion / Vibration derived from GPS speed ---
//             String motion = gpsSpeed > 1.0 ? "MOVING" : "STATIONARY";

//             // --- Build DTO ---
//             return new DeviceIOPortsDto(ioHex, ignition, motion, input2 ? "ON" : "OFF", out1, out2, adc1Voltage,
//                     adc2Voltage);

//         } catch (Exception e) {
//             logger.error("Error parsing I/O ports: {}", e.getMessage(), e);
//             return DeviceIOPortsDto.getDefaultIOPorts();
//         }
//     }

//     public DeviceLbsDataDto parseLBSData(ByteBuf content) {
//         try {
//             content.markReaderIndex();

//             String lbsHex = "N/A";
//             int mcc = 0;
//             int mnc = 0;
//             int lac = 0;
//             int cid = 0;

//             // LBS data starts after GPS location data (18 bytes from start in GT06)
//             if (content.readableBytes() > 18) {
//                 content.skipBytes(18);

//                 if (content.readableBytes() >= 8) { // GT06 LBS is 8 bytes
//                     byte[] lbsBytes = new byte[8];
//                     content.readBytes(lbsBytes);
//                     lbsHex = ByteBufUtil.hexDump(lbsBytes);

//                     // ✅ GT06 LBS format: [MCC:2][MNC:1][LAC:2][CID:3]
//                     mcc = ((lbsBytes[0] & 0xFF) << 8) | (lbsBytes[1] & 0xFF);
//                     mnc = (lbsBytes[2] & 0xFF);
//                     lac = ((lbsBytes[3] & 0xFF) << 8) | (lbsBytes[4] & 0xFF);
//                     cid = ((lbsBytes[5] & 0xFF) << 16) | ((lbsBytes[6] & 0xFF) << 8) | (lbsBytes[7] & 0xFF);
//                 }
//             }

//             // Reset to re-read for satellites
//             content.resetReaderIndex();
//             int satellites = 0;
//             if (content.readableBytes() > 7) {
//                 content.skipBytes(7);
//                 satellites = content.readUnsignedByte();
//             }

//             // Fake RSSI estimation (you may refine this later)
//             int rssi = satellites > 4 ? -65 : satellites > 2 ? -75 : -85;

//             return new DeviceLbsDataDto(lbsHex, mcc, mnc, lac, cid, rssi);

//         } catch (Exception e) {
//             logger.error("Error parsing LBS data: {}", e.getMessage(), e);
//             return DeviceLbsDataDto.getDefaultDeviceLbsData();
//         }
//     }

//     public AlarmStatusDto parseAlarms(ByteBuf content) {
//         // Configurable thresholds
//         final int OVERSPEED_THRESHOLD = 80; // km/h

//         try {
//             // 1. Mark reader index to reset after reading course/status
//             content.markReaderIndex();

//             // 2. Read course/status word (contains SOS, vibration, tamper, low-battery
//             // bits)
//             String alarmHex = "0000";
//             int courseStatus = 0;
//             if (content.readableBytes() >= 19) {
//                 content.skipBytes(17);
//                 courseStatus = content.readUnsignedShort();
//                 alarmHex = String.format("%04X", courseStatus);
//             }

//             // 3. Decode supported alarm flags
//             boolean sosAlarm = (courseStatus & 0x0004) != 0;
//             boolean vibrationAlarm = (courseStatus & 0x0008) != 0;
//             boolean tamperAlarm = (courseStatus & 0x0020) != 0;
//             boolean lowBatteryAlarm = (courseStatus & 0x0040) != 0;

//             // 4. Reset and read speed for overspeed and idle detection
//             content.resetReaderIndex();
//             int speed = 0;
//             if (content.readableBytes() >= 17) {
//                 content.skipBytes(16);
//                 speed = content.readUnsignedByte();
//             }
//             boolean overSpeedAlarm = speed > OVERSPEED_THRESHOLD;
//             boolean ignition = (courseStatus & 0x2000) != 0;
//             boolean idleAlarm = ignition && speed == 0;

//             // 5. Build and return DTO with only GT06-supported alarms
//             return new AlarmStatusDto(alarmHex, sosAlarm, vibrationAlarm, tamperAlarm, lowBatteryAlarm, overSpeedAlarm,
//                     idleAlarm);
//         } catch (Exception e) {
//             logger.error("Error parsing GT06 alarm data: {}", e.getMessage(), e);
//             return AlarmStatusDto.getDefaultStatus();
//         }
//     }

//     public DeviceExtendedFeatureDto parseExtendedFeatures(ByteBuf content) {
//         try {
//             // 1. Reset reader index and skip to feature field
//             content.resetReaderIndex();
//             String featureHex = "0000";
//             if (content.readableBytes() > 24) {
//                 content.skipBytes(24);
//                 if (content.readableBytes() >= 2) {
//                     byte[] featureBytes = new byte[2];
//                     content.readBytes(featureBytes);
//                     featureHex = ByteBufUtil.hexDump(Unpooled.wrappedBuffer(featureBytes));
//                 }
//             }

//             // 2. Default/configurable values for supported GT06/GT06N features
//             boolean smsCommands = true; // Always supported
//             int uploadInterval = 30; // seconds
//             int distanceUpload = 200; // meters
//             int heartbeatInterval = 300; // seconds
//             int cellScanCount = 1; // basic model
//             String backupMode = "SMS"; // fallback mode
//             boolean sleepMode = false; // basic model does not sleep

//             // 3. Construct and return DTO with only supported fields
//             return new DeviceExtendedFeatureDto(featureHex, smsCommands, uploadInterval, distanceUpload,
//                     heartbeatInterval, cellScanCount, backupMode, sleepMode);
//         } catch (Exception e) {
//             logger.error("Error parsing GT06/GT06N extended features: {}", e.getMessage(), e);
//             return DeviceExtendedFeatureDto.getDefaultFeatures();
//         }
//     }

//     public String getOperatorName(int mcc, int mnc) {
//         if (mcc == 404) { // India
//             return switch (mnc) {
//                 case 1, 2, 3 -> "Airtel";
//                 case 10, 11, 12 -> "Airtel";
//                 case 20, 21, 22 -> "Vodafone";
//                 case 27, 28, 29 -> "Vodafone";
//                 case 40, 41, 42 -> "Jio";
//                 case 43, 44, 45 -> "Jio";
//                 case 70, 71, 72 -> "BSNL";
//                 default -> "Unknown IN";
//             };
//         }
//         return "Unknown";
//     }

//     // ------------------------------------------------
//     // private void logDeviceReport(ChannelHandlerContext ctx, ByteBuf content, String imei, String remoteAddress,
//     //         MessageFrame frame) {
//     //     try {
//     //         content.resetReaderIndex();
//     //         String fullRawPacket = ByteBufUtil.hexDump(content);
//     //         String serverTimestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
//     //         int frameLen = content.readableBytes();

//     //         // Parse all data sections
//     //         DeviceLbsDataDto lbs = parseLBSData(content);
//     //         AlarmStatusDto alarmData = parseAlarms(content);
//     //         DeviceExtendedFeatureDto featureData = parseExtendedFeatures(content);

//     //         logger.info("📡 Device Report Log ===========================================>");
//     //         // 🕒 TIMESTAMP SECTION -------------------->
//     //         logger.info("🕒 Timestamp -------------------->");
//     //         logger.info("   📩 Server Time : {}", serverTimestamp);
//     //         logger.info("   📡 RemoteAddress : {}", remoteAddress);
//     //         logger.info("   📡 IMEI        : {}", imei);
//     //         logger.info("   📦 Protocol      : 0x{} ({})", String.format("%02X", frame.protocolNumber()),
//     //                 protocolName(frame.protocolNumber()));
//     //         logger.info("   🔑 Raw Packet    : {}", fullRawPacket);
//     //         logger.info("   📏 FrameLen      : {}   | Checksum : ✅ OK  | Duration : {}ms ", frameLen,
//     //                 System.currentTimeMillis() % 100);

//     //         // 🔌 Device I/O Ports -------------------->
//     //         logger.info(" 🔌 Device I/O Ports -------------------->");
//     //         // logger.info(" 🗃️ I/O Hex : {}", ioData.ioHex());
//     //         // logger.info(" 🔑 IN1 / Ignition : {}", ioData.ignition() ? "ON ✅" : "OFF ❌");
//     //         // logger.info(" 🛰️ Motion : {}", ioData.motion());
//     //         // logger.info(" 🔌 IN2 : {}", ioData.input2());
//     //         // logger.info(" 🔌 OUT1 (Relay) : {}", ioData.out1());
//     //         // logger.info(" 🔌 OUT2 (Relay) : {}", ioData.out2());
//     //         // logger.info(" ⚡ ADC1 Voltage : {} V",
//     //         // ioData.adc1Voltage() != null ? String.format("%.2f", ioData.adc1Voltage()) :
//     //         // "N/A");
//     //         // logger.info(" ⚡ ADC2 Voltage : {} V",
//     //         // ioData.adc2Voltage() != null ? String.format("%.2f", ioData.adc2Voltage()) :
//     //         // "N/A");

//     //         // 📡 LBS Data -------------------->
//     //         logger.info("📡 LBS Data -------------------->");
//     //         logger.info("   🗃️ Raw Hex    : {}", lbs.lbsHex());
//     //         logger.info("   🌐 MCC        : {}", lbs.mcc());
//     //         logger.info("   📶 MNC        : {}", lbs.mnc());
//     //         logger.info("   🗼 LAC        : {}", lbs.lac());
//     //         logger.info("   🗼 CID        : {}", lbs.cid());
//     //         logger.info("   📡 RSSI       : {} dBm", lbs.rssi());

//     //         // 🚨 Alarm Data -------------------->
//     //         logger.info("🚨 GT06 Alarm Data -------------------->");
//     //         logger.info("   🗃️ Raw Hex          : 0x{}", alarmData.alarmHex());
//     //         logger.info("   🆘 SOS Alarm        : {}", alarmData.sosAlarm() ? "TRIGGERED" : "OFF");
//     //         logger.info("   💥 Vibration Alarm  : {}", alarmData.vibrationAlarm() ? "TRIGGERED" : "OFF");
//     //         logger.info("   🛠️ Tamper Alarm     : {}", alarmData.tamperAlarm() ? "TRIGGERED" : "OFF");
//     //         logger.info("   🔋 Low Battery      : {}", alarmData.lowBatteryAlarm() ? "TRIGGERED" : "OK");
//     //         logger.info("   ⚡ Over-speed Alarm : {}", alarmData.overSpeedAlarm() ? "YES" : "NO");
//     //         logger.info("   🅿️ Idle Alarm       : {}", alarmData.idleAlarm() ? "ACTIVE" : "OFF");

//     //         // ⚙️ GT06 Extended Features -------------------->
//     //         logger.info("⚙️ GT06 Extended Features -------------------->");
//     //         logger.info("   🗃️ Raw Hex            : 0x{}", featureData.featureHex());
//     //         logger.info("   📩 SMS Commands       : {}", featureData.smsCommands() ? "SUPPORTED" : "NOT SUPPORTED");
//     //         logger.info("   😴 Sleep Mode         : {}", featureData.sleepMode() ? "ACTIVE" : "OFF");
//     //         logger.info("   ⏱️ Upload Interval    : {} sec", featureData.uploadInterval());
//     //         logger.info("   📏 Distance Upload    : {} meters", featureData.distanceUpload());
//     //         logger.info("   ❤️ Heartbeat Interval : {} sec", featureData.heartbeatInterval());
//     //         logger.info("   📶 Cell Scan Count    : {}", featureData.cellScanCount());
//     //         logger.info("   📨 Backup Mode        : {}", featureData.backupMode());

//     //         logger.info("📡 Device Report Log <=========================================== END");

//     //     } catch (Exception e) {
//     //         logger.error("💥 Enhanced GT06 parsing error for IMEI {}: {}", imei, e.getMessage(), e);
//     //     }
//     // }

//     /**
//      * Map GT06 protocol number → human-readable name.
//      * Covers Login, Location, Status, Alarm, LBS, Command, Heartbeat, etc.
//      */
//     private String protocolName(int proto) {
//         return switch (proto) {
//             case 0x01 -> "Login";
//             case 0x05 -> "Heartbeat";
//             case 0x08 -> "GPS Location (old type)";
//             case 0x10 -> "LBS Location";
//             case 0x12 -> "GPS Location (0x12 type)";
//             case 0x13 -> "Status Info";
//             case 0x15 -> "String Information";
//             case 0x16 -> "Alarm Packet";
//             case 0x1A -> "Extended Status Info";
//             case 0x80 -> "Command Response (0x80)";
//             case 0x8A -> "Command Response (0x8A)";
//             case 0x94 -> "GPS Location (0x94 type)";
//             case 0x97 -> "OBD / Extended Data (some models)";
//             default -> String.format("Unknown (0x%02X)", proto);
//         };
//     }

// }
