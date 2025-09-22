package com.wheelseye.devicegateway.handler;

import com.wheelseye.devicegateway.model.DeviceMessage;
// import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Device Business Handler - Concise Lombok Implementation
 * 
 * FIXED: All ClassCastException issues with safe type conversion.
 * Uses Lombok for minimal, clean code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class DeviceBusinessHandler extends SimpleChannelInboundHandler<DeviceMessage> {

    private static final AttributeKey<String> IMEI_ATTR = AttributeKey.valueOf("DEVICE_IMEI");
    private static final AttributeKey<String> SESSION_ID_ATTR = AttributeKey.valueOf("SESSION_ID");
    private static final AttributeKey<Boolean> CONFIGURED_ATTR = AttributeKey.valueOf("DEVICE_CONFIGURED");

    private final DeviceSessionService sessionService;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) {
        CompletableFuture.runAsync(() -> processMessage(ctx, message))
                .exceptionally(throwable -> {
                    log.error("❌ Error processing message from {}: {}", 
                            ctx.channel().remoteAddress(), throwable.getMessage(), throwable);
                    return null;
                });
    }

    private void processMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            log.debug("📨 Processing {} from {}", message.type(), message.imei());

            switch (message.type()) {
                case "login" -> handleLogin(ctx, message);
                case "gps" -> handleLocation(ctx, message);
                case "alarm" -> handleAlarm(ctx, message);
                case "heartbeat" -> handleHeartbeat(ctx, message);
                case "string" -> handleString(ctx, message);
                case "gps_address" -> handleAddressRequest(ctx, message);
                default -> handleGeneric(ctx, message);
            }

        } catch (Exception e) {
            log.error("❌ Error processing {} from {}: {}", 
                    message.type(), ctx.channel().remoteAddress(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // var imei = IMEI.of(message.imei());

            log.info("🔐 Processing login for device: {} from {}", 
                    message.imei(), ctx.channel().remoteAddress());

            var session = sessionService.createOrUpdateSession(message.imei(), ctx.channel());

            ctx.channel().attr(IMEI_ATTR).set(message.imei());
            ctx.channel().attr(SESSION_ID_ATTR).set(session.getId());

            sendLoginAck(ctx);
            configureDevice(ctx, message.imei());

            log.info("✅ Device {} logged in, session: {}", message.imei(), session.getId());

        } catch (Exception e) {
            log.error("❌ Login failed for {} from {}: {}", 
                    message.imei(), ctx.channel().remoteAddress(), e.getMessage(), e);
            ctx.close();
        }
    }

    /**
     * FIXED: Safe type conversion prevents ClassCastException
     */
    private void handleLocation(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // FIXED: Use Optional-based safe extraction
            var latitude = message.latitude().orElse(null);
            var longitude = message.longitude().orElse(null);
            var timestamp = message.getData("gpsTimestamp", Instant.class).orElse(Instant.now());
            var speed = message.speed().orElse(0);     // FIXED: Safe integer extraction
            var course = message.course().orElse(0);   // FIXED: Safe integer extraction

            if (latitude != null && longitude != null) {
                sessionService.updateLastPosition(message.imei(), latitude, longitude, timestamp);

                log.info(
                    "📍 Device {} -> [ 🌐 {}°{} , {}°{} ] 🏎️ {} km/h 🧭 {}° 🔗 https://www.google.com/maps?q={},{}",
                    message.imei(),
                    String.format("%.6f", Math.abs(latitude)), latitude >= 0 ? "N" : "S",
                    String.format("%.6f", Math.abs(longitude)), longitude >= 0 ? "E" : "W",
                    speed, course,
                    latitude, longitude
                );
            } else {
                // var imei = IMEI.of(message.imei());
                log.warn("⚠️ Invalid location from {}: lat={}, lon={}", 
                        message.imei(), latitude, longitude);
            }

            sendLocationAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing location from {}: {}", 
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleAlarm(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            handleLocation(ctx, message);

            var alarmStatus = message.alarmStatus().orElse(0);

            if (alarmStatus != 0) {
                // var imei = IMEI.of(message.imei());
                log.warn("🚨 ALARM from {}: status=0x{:02X} at [{}, {}]", 
                        message.imei(), alarmStatus,
                        message.latitude().orElse(0.0), 
                        message.longitude().orElse(0.0));
            }

        } catch (Exception e) {
            log.error("❌ Error processing alarm from {}: {}", 
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            sessionService.updateLastHeartbeat(message.imei());

            var charging = message.charging().orElse(false);
            var gsmSignal = message.gsmSignal().orElse(0);
            var voltage = message.voltageLevel().orElse(0);

            // var imei = IMEI.of(message.imei());
            log.debug("💓 Heartbeat from {}: charging={}, gsm={}, voltage={}", 
                    message.imei(), charging, gsmSignal, voltage);

            sendHeartbeatAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing heartbeat from {}: {}", 
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleString(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            var content = message.getData("content", String.class).orElse("");
            // var imei = IMEI.of(message.imei());

            log.info("📝 String message from {}: {}", message.imei(), content);
            sendGenericAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing string from {}: {}", 
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleAddressRequest(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            handleLocation(ctx, message);

            var phoneNumber = message.getData("phoneNumber", String.class).orElse("");
            // var imei = IMEI.of(message.imei());

            log.info("📞 Address request from {}: phone={}", message.imei(), phoneNumber);

        } catch (Exception e) {
            log.error("❌ Error processing address request from {}: {}", 
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    private void handleGeneric(ChannelHandlerContext ctx, DeviceMessage message) {
        // var imei = IMEI.of(message.imei());
        log.debug("📄 Generic message from {}: type={}", message.imei(), message.type());
        sendGenericAck(ctx);
    }

    private void configureDevice(ChannelHandlerContext ctx, String imei) {
        if (Boolean.TRUE.equals(ctx.channel().attr(CONFIGURED_ATTR).get())) {
            return;
        }

        var configCommand = String.format("**,%s,C02,30s", imei);
        var buffer = ctx.alloc().buffer();
        buffer.writeBytes(configCommand.getBytes());

        ctx.writeAndFlush(buffer).addListener(future -> {
            if (future.isSuccess()) {
                ctx.channel().attr(CONFIGURED_ATTR).set(true);
                // var maskedImei = IMEI.of(imei);
                log.info("📡 Configuration sent to {}: 30s reporting", imei);
            } else {
                log.warn("⚠️ Failed to configure device {}: {}", 
                        imei, future.cause().getMessage());
            }
        });
    }

    // Response methods
    private void sendLoginAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x01, (byte)0xD9, (byte)0xDC, 0x0D, 0x0A});
    }

    private void sendLocationAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x02, (byte)0xD9, (byte)0xDD, 0x0D, 0x0A});
    }

    private void sendHeartbeatAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x03, (byte)0xD9, (byte)0xDE, 0x0D, 0x0A});
    }

    private void sendGenericAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x00, (byte)0xD9, (byte)0xDB, 0x0D, 0x0A});
    }

    private void sendErrorResponse(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, (byte)0xFF, (byte)0xFF, (byte)0xD8, (byte)0xDA, 0x0D, 0x0A});
    }

    private void sendResponse(ChannelHandlerContext ctx, byte[] response) {
        try {
            var buffer = ctx.alloc().buffer(response.length);
            buffer.writeBytes(response);
            ctx.writeAndFlush(buffer);
        } catch (Exception e) {
            log.error("❌ Failed to send response to {}: {}", 
                    ctx.channel().remoteAddress(), e.getMessage(), e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        var sessionId = ctx.channel().attr(SESSION_ID_ATTR).get();

        if (imei != null) {
            // var maskedImei = IMEI.of(imei);
            log.info("📵 Device {} disconnected (session: {})", imei, sessionId);

            try {
                sessionService.removeSession(ctx.channel());
            } catch (Exception e) {
                log.error("❌ Error removing session: {}", e.getMessage(), e);
            }
        } else {
            log.debug("📵 Unknown device disconnected from {}", ctx.channel().remoteAddress());
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        var maskedImei = imei != null ? imei : "UNKNOWN";

        log.error("❌ Handler exception for {} from {}: {}", 
                maskedImei, ctx.channel().remoteAddress(), cause.getMessage(), cause);

        ctx.close();
    }
}
