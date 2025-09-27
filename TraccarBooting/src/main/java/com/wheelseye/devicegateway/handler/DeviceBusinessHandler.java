package com.wheelseye.devicegateway.handler;

import com.wheelseye.devicegateway.model.DeviceMessage;
import com.wheelseye.devicegateway.service.ChannelManagerService;
import com.wheelseye.devicegateway.service.CommandService;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Production-Ready Device Business Handler
 * 
 * Enhanced GPS device message processor with:
 * - Fixed method compatibility with DeviceSessionService
 * - Proper error handling and logging
 * - Async message processing for performance
 * - Complete device lifecycle management
 * - Modern Java 21 features
 * 
 * @author WheelsEye Development Team
 * @version 2.1.0 - Production Fixed
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
    private final CommandService commandService;

    @Autowired
    private ChannelManagerService channelManagerService;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) {
        // Process messages asynchronously to avoid blocking the event loop
        CompletableFuture.runAsync(() -> processMessage(ctx, message))
                .exceptionally(throwable -> {
                    log.error("❌ Error processing message from {}: {}",
                            ctx.channel().remoteAddress(), throwable.getMessage(), throwable);
                    sendErrorResponse(ctx);
                    return null;
                });
    }

    /**
     * Main message processor with enhanced error handling
     */
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

    /**
     * Handle device login with session creation
     */
    private void handleLogin(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            String imei = message.imei();
            log.info("🔐 Processing login for device: {} from {}",
                    imei, ctx.channel().remoteAddress());

            // Create or update session using the correct service method
            var session = sessionService.createOrUpdateSession(imei, ctx.channel());

            // Store session info in channel attributes
            ctx.channel().attr(IMEI_ATTR).set(imei);
            ctx.channel().attr(SESSION_ID_ATTR).set(session.getId());

            // Authenticate the session
            sessionService.authenticateSession(imei);

            // Send login acknowledgment
            sendLoginAck(ctx);

            // Configure device reporting interval
            configureDevice(ctx, imei);

            // ADD THIS LINE: Register active channel
            channelManagerService.registerChannel(imei, ctx.channel());

            log.info("✅ Device {} logged in, session: {}", imei, session.getId());

        } catch (Exception e) {
            log.error("❌ Login failed for {} from {}: {}",
                    message.imei(), ctx.channel().remoteAddress(), e.getMessage(), e);
            ctx.close();
        }
    }

    /**
     * Handle GPS location updates with proper coordinate validation
     */
    private void handleLocation(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            String imei = message.imei();

            // Extract GPS data with safe type conversion
            var latitude = message.latitude().orElse(null);
            var longitude = message.longitude().orElse(null);
            var timestamp = message.getData("gpsTimestamp", Instant.class).orElse(Instant.now());
            var speed = message.speed().orElse(0);
            var course = message.course().orElse(0);

            if (latitude != null && longitude != null) {
                // Update session position using the correct service method
                sessionService.updateLastPosition(imei, latitude, longitude, timestamp);

                // Log GPS information with Google Maps link
                log.info(
                        "📍 Device {} -> [ 🌐 {}°{} , {}°{} ] 🏎️ {} km/h 🧭 {}° 🔗 https://www.google.com/maps?q={},{}",
                        imei,
                        String.format("%.6f", Math.abs(latitude)), latitude >= 0 ? "N" : "S",
                        String.format("%.6f", Math.abs(longitude)), longitude >= 0 ? "E" : "W",
                        speed, course, latitude, longitude);
            } else {
                log.warn("⚠️ Invalid location from {}: lat={}, lon={}", imei, latitude, longitude);
            }

            // Send location acknowledgment
            sendLocationAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing location from {}: {}",
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle alarm messages with location processing
     */
    private void handleAlarm(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Process location data first
            handleLocation(ctx, message);

            String imei = message.imei();
            var alarmStatus = message.alarmStatus().orElse(0);

            if (alarmStatus != 0) {
                log.warn("🚨 ALARM from {}: status=0x{:02X} at [{}, {}]",
                        imei, alarmStatus,
                        message.latitude().orElse(0.0),
                        message.longitude().orElse(0.0));

                // Update session status with alarm information
                sessionService.updateStatusAsync(imei, 0, false, 0)
                        .whenComplete((result, throwable) -> {
                            if (throwable != null) {
                                log.warn("⚠️ Failed to update alarm status: {}", throwable.getMessage());
                            }
                        });
            }

        } catch (Exception e) {
            log.error("❌ Error processing alarm from {}: {}",
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle heartbeat messages with device status updates
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            String imei = message.imei();

            // Update session heartbeat using the correct service method
            sessionService.updateLastHeartbeat(imei);

            // Extract device status information
            var charging = message.charging().orElse(false);
            var gsmSignal = message.gsmSignal().orElse(0);
            var voltage = message.voltageLevel().orElse(0);

            // Update device status asynchronously
            sessionService.updateStatusAsync(imei, gsmSignal, charging, voltage)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            log.warn("⚠️ Failed to update device status: {}", throwable.getMessage());
                        }
                    });

            log.debug("💓 Heartbeat from {}: charging={}, gsm={}, voltage={}",
                    imei, charging, gsmSignal, voltage);

            // Send heartbeat acknowledgment
            sendHeartbeatAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing heartbeat from {}: {}",
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle string messages from device
     * MODIFIED: Enhanced handleString method to process command responses
     */
    private void handleString(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            String imei = message.imei();
            var content = message.getData("content", String.class).orElse("");
            log.info("📝 String message from {}: {}", imei, content);

            // NEW: Process as potential command response
            if (isCommandResponse(content)) {
                commandService.processCommandResponse(imei, content);
                log.info("🔄 Processed command response from {}: {}", imei, content);
            }

            // Touch session to update activity
            sessionService.touchSession(imei);

            // Send generic acknowledgment
            sendGenericAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing string from {}: {}",
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * NEW: Check if string message is a command response
     */
    private boolean isCommandResponse(String content) {
        return content.startsWith("DYD=") ||
                content.startsWith("HFYD=") ||
                content.startsWith("DWXX=") ||
                content.startsWith("RESET") ||
                content.startsWith("Battery:") ||
                content.startsWith("TIMER") ||
                content.startsWith("SERVER");
    }

    /**
     * Handle address request messages
     */
    private void handleAddressRequest(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Process location data first
            handleLocation(ctx, message);

            String imei = message.imei();
            var phoneNumber = message.getData("phoneNumber", String.class).orElse("");

            log.info("📞 Address request from {}: phone={}", imei, phoneNumber);

            // Touch session to update activity
            sessionService.touchSession(imei);

        } catch (Exception e) {
            log.error("❌ Error processing address request from {}: {}",
                    message.imei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle generic/unknown messages
     */
    private void handleGeneric(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = message.imei();
        log.debug("📄 Generic message from {}: type={}", imei, message.type());

        // Touch session to update activity
        sessionService.touchSession(imei);

        // Send generic acknowledgment
        sendGenericAck(ctx);
    }

    /**
     * Configure device reporting interval
     */
    private void configureDevice(ChannelHandlerContext ctx, String imei) {
        if (Boolean.TRUE.equals(ctx.channel().attr(CONFIGURED_ATTR).get())) {
            return; // Already configured
        }

        try {
            // GT06 configuration command for 30-second reporting
            var configCommand = String.format("**,%s,C02,30s", imei);
            var buffer = ctx.alloc().buffer();
            buffer.writeBytes(configCommand.getBytes());

            ctx.writeAndFlush(buffer).addListener(future -> {
                if (future.isSuccess()) {
                    ctx.channel().attr(CONFIGURED_ATTR).set(true);
                    log.info("📡 Configuration sent to {}: 30s reporting", imei);
                } else {
                    log.warn("⚠️ Failed to configure device {}: {}",
                            imei, future.cause() != null ? future.cause().getMessage() : "Unknown error");
                }
            });

        } catch (Exception e) {
            log.error("❌ Error configuring device {}: {}", imei, e.getMessage(), e);
        }
    }

    // === RESPONSE METHODS ===

    /**
     * Send login acknowledgment
     */
    private void sendLoginAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[] { 0x78, 0x78, 0x05, 0x01, 0x00, 0x01, (byte) 0xD9, (byte) 0xDC, 0x0D, 0x0A });
    }

    /**
     * Send location acknowledgment
     */
    private void sendLocationAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[] { 0x78, 0x78, 0x05, 0x01, 0x00, 0x02, (byte) 0xD9, (byte) 0xDD, 0x0D, 0x0A });
    }

    /**
     * Send heartbeat acknowledgment
     */
    private void sendHeartbeatAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[] { 0x78, 0x78, 0x05, 0x01, 0x00, 0x03, (byte) 0xD9, (byte) 0xDE, 0x0D, 0x0A });
    }

    /**
     * Send generic acknowledgment
     */
    private void sendGenericAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[] { 0x78, 0x78, 0x05, 0x01, 0x00, 0x00, (byte) 0xD9, (byte) 0xDB, 0x0D, 0x0A });
    }

    /**
     * Send error response
     */
    private void sendErrorResponse(ChannelHandlerContext ctx) {
        sendResponse(ctx,
                new byte[] { 0x78, 0x78, 0x05, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xD8, (byte) 0xDA, 0x0D, 0x0A });
    }

    /**
     * Generic response sender with error handling
     */
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

    // === CHANNEL LIFECYCLE METHODS ===

    /**
     * Handle channel disconnect
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        var sessionId = ctx.channel().attr(SESSION_ID_ATTR).get();

        if (imei != null) {
            log.info("📵 Device {} disconnected (session: {})", imei, sessionId);

            try {
                // Remove session using the correct service method
                sessionService.removeSession(ctx.channel());

                // ADD THIS LINE: Unregister channel
                channelManagerService.unregisterChannel(imei);
            
            } catch (Exception e) {
                log.error("❌ Error removing session: {}", e.getMessage(), e);
            }
        } else {
            log.debug("📵 Unknown device disconnected from {}", ctx.channel().remoteAddress());
        }

        super.channelInactive(ctx);
    }

    /**
     * Handle exceptions in the channel
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        var maskedImei = imei != null ? maskImei(imei) : "UNKNOWN";

        log.error("❌ Handler exception for {} from {}: {}",
                maskedImei, ctx.channel().remoteAddress(), cause.getMessage(), cause);

        // Close the channel on exception
        ctx.close();
    }

    /**
     * Mask IMEI for logging privacy
     */
    private String maskImei(String imei) {
        if (imei == null || imei.length() < 4) {
            return "INVALID";
        }
        return "*".repeat(11) + imei.substring(imei.length() - 4);
    }
}