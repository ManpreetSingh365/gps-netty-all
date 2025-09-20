package com.wheelseye.devicegateway.helper;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wheelseye.devicegateway.config.KafkaConfig.KafkaAdapter;
import com.wheelseye.devicegateway.dto.DeviceStatusDto;
import com.wheelseye.devicegateway.dto.LocationDto;
import com.wheelseye.devicegateway.mappers.LocationMapper;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.DeviceSession.DeviceStatus;
import com.wheelseye.devicegateway.protocol.Gt06ProtocolDecoder;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.channel.ChannelHandlerContext;

@Component
public class Gt06Handler {

    private static final Logger logger = LoggerFactory.getLogger(Gt06ProtocolDecoder.class);

    private final KafkaAdapter kafkaAdapter;
    private DeviceSessionService sessionService;

    public Gt06Handler(KafkaAdapter kafkaAdapter, DeviceSessionService sessionService) {
        this.kafkaAdapter = kafkaAdapter;
        this.sessionService = sessionService;
    }

    public void publishLocation(ChannelHandlerContext ctx, Instant timestamp, boolean gpsValid, double latitude,
            double longitude, double speed, double course, double accuracy, int satellites) {

        String remoteAddress = ctx.channel().remoteAddress().toString();
        Optional<DeviceSession> sessionOpt = sessionService.getSession(ctx.channel());

        if (sessionOpt.isEmpty()) {
            logger.warn("❌ No authenticated session for location from {}", remoteAddress);
            return;
        }

        DeviceSession session = sessionOpt.get();
        String imei = session.getImei() != null ? session.getImei().value() : null;
        String sid = session.getId();

        if (imei == null || sid == null) {
            logger.warn("⚠️ Missing IMEI or session ID for {}", remoteAddress);
            return;
        }

        try {
            LocationDto location = new LocationDto(timestamp, gpsValid, latitude, longitude,
                    speed, course, accuracy, satellites);

            if (location != null) {

                logger.info("🌍 Location Data -------------------->");
                logger.info("   🗓️ PktTime     : {}", location.timestamp());
                logger.info(String.format("   📍 Lat/Lon     : %.6f° %s , %.6f° %s", Math.abs(location.latitude()),
                        location.latitude() >= 0 ? "N" : "S", Math.abs(location.longitude()),
                        location.longitude() >= 0 ? "E" : "W"));
                logger.info("   🚗 Speed       : {} km/h      🧭 Heading : {}°", location.speed(), location.course());
                logger.info("   🛰️ Satellites : {}", location.satellites());
                // Accuracy (~ meters) → ❌ Not in GT06 packet (server usually estimates from
                // satellite count).
                logger.info("   🎯 Accuracy    : ~{} m", location.accuracy());
                logger.info("   🔄 GPS Status  : {}", location.gpsValid() ? "Valid" : "Invalid");
                // Fix Type (2D/3D) → Derived from satellites count, not raw in packet.
                // logger.info(" 🔄 Fix Type : {}",
                // location.satellites() >= 4 ? "3D Fix" : (location.satellites() >= 2 ? "2D
                // Fix" : "No Fix"));
                // logger.info(" #️⃣ Serial : {} 🏷️ Event : Normal Tracking (0x{})",
                // frame.serialNumber(),
                // String.format("%02X", frame.protocolNumber()));

                // 🗺️ MAP LINKS -------------------->
                logger.info("🗺️ Map Links -------------------->");
                logger.info(
                        String.format("   🔗 Google Maps   : https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
                                location.latitude(), location.longitude()));
                logger.info(String.format(
                        "   🔗 OpenStreetMap : https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
                        location.latitude(), location.longitude(), location.latitude(), location.longitude()));

                byte[] payload = LocationMapper.toProto(location).toByteArray();
                kafkaAdapter.sendMessage("location.device", sid, payload);
            }
        } catch (Exception e) {
            logger.error("💥 Error publishing location for IMEI {}: {}", imei, e.getMessage(), e);
        }
    }

    public void publishStatus(ChannelHandlerContext ctx, DeviceStatusDto deviceStatus) {

        // 🔋 DEVICE STATUS -------------------->
        logger.info("🔋 Device Status -------------------->");
        logger.info("   🗃️ Packet      : 0x{}", Integer.toHexString(deviceStatus.statusBits()));
        logger.info("   🔑 Ignition    : {} (ACC={})   🔦 ACC Line : {}", deviceStatus.ignition() ? "ON" : "OFF",
                deviceStatus.ignition() ? "Active" : "Inactive");
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

    }

}
