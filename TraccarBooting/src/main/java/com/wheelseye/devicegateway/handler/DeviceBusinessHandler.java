package com.wheelseye.devicegateway.handler;

import com.wheelseye.devicegateway.model.DeviceMessage;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Production-ready Device Business Handler following modern Java 21 and Spring Boot 3.5.5 practices.
 * 
 * Key improvements:
 * - Comprehensive message handling for all GT06 protocol types
 * - Asynchronous processing to prevent blocking the Netty event loop
 * - Proper device configuration and location reporting setup
 * - Enhanced error handling and logging
 * - Performance optimizations with context caching
 */
@Component
@ChannelHandler.Sharable
public class DeviceBusinessHandler extends SimpleChannelInboundHandler<DeviceMessage> {

    private static final Logger log = LoggerFactory.getLogger(DeviceBusinessHandler.class);

    // Channel attribute keys
    private static final AttributeKey<String> IMEI_ATTR = AttributeKey.valueOf("DEVICE_IMEI");
    private static final AttributeKey<String> SESSION_ID_ATTR = AttributeKey.valueOf("SESSION_ID");
    private static final AttributeKey<Boolean> CONFIGURED_ATTR = AttributeKey.valueOf("DEVICE_CONFIGURED");

    private final DeviceSessionService sessionService;

    public DeviceBusinessHandler(DeviceSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DeviceMessage message) throws Exception {
        // Process message asynchronously to avoid blocking Netty event loop
        CompletableFuture.runAsync(() -> processDeviceMessage(ctx, message))
                .exceptionally(throwable -> {
                    log.error("❌ Error processing message from {}: {}", 
                            ctx.channel().remoteAddress(), throwable.getMessage(), throwable);
                    return null;
                });
    }

    /**
     * Process device messages based on type.
     */
    private void processDeviceMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            log.debug("📨 Processing {} message from device: {}", 
                    message.getType(), message.getImei());

            switch (message.getType()) {
                case "login" -> handleLogin(ctx, message);
                case "gps" -> handleLocationData(ctx, message);
                case "alarm" -> handleAlarmData(ctx, message);
                case "heartbeat" -> handleHeartbeat(ctx, message);
                case "string" -> handleStringMessage(ctx, message);
                case "gps_address" -> handleAddressRequest(ctx, message);
                default -> handleGenericMessage(ctx, message);
            }

        } catch (Exception e) {
            log.error("❌ Error processing {} message from {}: {}", 
                    message.getType(), ctx.channel().remoteAddress(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle device login and establish session.
     */
    private void handleLogin(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            var imeiValue = message.getImei();
            var imei = IMEI.of(imeiValue);
            
            log.info("🔐 Processing login for device: {} from {}", 
                    imeiValue, ctx.channel().remoteAddress());

            // Create or update device session
            var session = sessionService.createOrUpdateSession(imei, ctx.channel());
            
            // Cache session information in channel context
            ctx.channel().attr(IMEI_ATTR).set(imeiValue);
            ctx.channel().attr(SESSION_ID_ATTR).set(session.getId());

            // Send login acknowledgment
            sendLoginAck(ctx);

            // Configure device for location reporting
            configureDeviceForReporting(ctx, imeiValue);

            log.info("✅ Device {} logged in successfully, session: {}", 
                    imeiValue, session.getId());

        } catch (Exception e) {
            log.error("❌ Login failed for device {} from {}: {}", 
                    message.getImei(), ctx.channel().remoteAddress(), e.getMessage(), e);
            ctx.close(); // Close connection on login failure
        }
    }

    /**
     * Handle GPS location data.
     */
    private void handleLocationData(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            var data = message.getData();
            var latitude = (Double) data.get("latitude");
            var longitude = (Double) data.get("longitude");
            var timestamp = (Instant) data.get("gpsTimestamp");
            var speed = (Integer) data.get("speed");
            var course = (Integer) data.get("course");

            if (latitude != null && longitude != null) {
                // Update session with location data asynchronously
                sessionService.updateLastPosition(
                        message.getImei(), 
                        latitude, 
                        longitude, 
                        timestamp != null ? timestamp : Instant.now()
                );

                log.info("📍 Location update: {} -> [{:.6f}, {:.6f}] speed={}km/h course={}°", 
                        message.getImei(), latitude, longitude, 
                        speed != null ? speed : 0, course != null ? course : 0);
            } else {
                log.warn("⚠️ Invalid location data from {}: lat={}, lon={}", 
                        message.getImei(), latitude, longitude);
            }

            // Send location acknowledgment
            sendLocationAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing location data from {}: {}", 
                    message.getImei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle alarm messages (location + alert information).
     */
    private void handleAlarmData(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Process location data first
            handleLocationData(ctx, message);

            // Extract alarm information
            var data = message.getData();
            var alarmStatus = (Integer) data.get("alarmStatus");
            
            if (alarmStatus != null && alarmStatus != 0) {
                log.warn("🚨 ALARM from device {}: status=0x{:02X} at [{}, {}]", 
                        message.getImei(), alarmStatus,
                        data.get("latitude"), data.get("longitude"));
                
                // Here you could implement alarm-specific logic:
                // - Send notifications
                // - Store alarm events
                // - Trigger emergency protocols
            }

        } catch (Exception e) {
            log.error("❌ Error processing alarm data from {}: {}", 
                    message.getImei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle heartbeat/status messages.
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Update last heartbeat timestamp
            sessionService.updateLastHeartbeat(message.getImei());

            var data = message.getData();
            var charging = (Boolean) data.get("charging");
            var gsmSignal = (Integer) data.get("gsmSignalStrength");
            var voltage = (Integer) data.get("voltageLevel");

            log.debug("💓 Heartbeat from {}: charging={}, gsm={}, voltage={}", 
                    message.getImei(), charging, gsmSignal, voltage);

            // Send heartbeat acknowledgment
            sendHeartbeatAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing heartbeat from {}: {}", 
                    message.getImei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle string messages.
     */
    private void handleStringMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            var data = message.getData();
            var content = (String) data.get("content");
            
            log.info("📝 String message from {}: {}", message.getImei(), content);
            
            // Send acknowledgment
            sendGenericAck(ctx);

        } catch (Exception e) {
            log.error("❌ Error processing string message from {}: {}", 
                    message.getImei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle GPS address requests.
     */
    private void handleAddressRequest(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Process location data
            handleLocationData(ctx, message);
            
            var data = message.getData();
            var phoneNumber = (String) data.get("phoneNumber");
            
            log.info("📞 Address request from {}: phone={}", message.getImei(), phoneNumber);
            
            // Here you could implement reverse geocoding and SMS response

        } catch (Exception e) {
            log.error("❌ Error processing address request from {}: {}", 
                    message.getImei(), e.getMessage(), e);
            sendErrorResponse(ctx);
        }
    }

    /**
     * Handle unknown/generic messages.
     */
    private void handleGenericMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        log.debug("📄 Generic message from {}: type={}", 
                message.getImei(), message.getType());
        sendGenericAck(ctx);
    }

    /**
     * Configure device for automatic location reporting.
     */
    private void configureDeviceForReporting(ChannelHandlerContext ctx, String imei) {
        try {
            // Check if already configured to avoid repeated commands
            if (Boolean.TRUE.equals(ctx.channel().attr(CONFIGURED_ATTR).get())) {
                return;
            }

            // Send configuration command for 30-second location reporting
            // GT06 format: **,imei,C02,30s (C02=set interval, 30s=30 seconds)
            var configCommand = String.format("**,%s,C02,30s", imei);
            var configBuffer = ctx.alloc().buffer();
            configBuffer.writeBytes(configCommand.getBytes());
            
            ctx.writeAndFlush(configBuffer).addListener(future -> {
                if (future.isSuccess()) {
                    ctx.channel().attr(CONFIGURED_ATTR).set(true);
                    log.info("📡 Configuration sent to device {}: 30s location reporting", imei);
                } else {
                    log.warn("⚠️ Failed to send configuration to device {}: {}", 
                            imei, future.cause().getMessage());
                }
            });

        } catch (Exception e) {
            log.error("❌ Error configuring device {}: {}", imei, e.getMessage(), e);
        }
    }

    // Response methods

    /**
     * Send login acknowledgment.
     */
    private void sendLoginAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x01, (byte)0xD9, (byte)0xDC, 0x0D, 0x0A});
    }

    /**
     * Send location acknowledgment.
     */
    private void sendLocationAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x02, (byte)0xD9, (byte)0xDD, 0x0D, 0x0A});
    }

    /**
     * Send heartbeat acknowledgment.
     */
    private void sendHeartbeatAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x03, (byte)0xD9, (byte)0xDE, 0x0D, 0x0A});
    }

    /**
     * Send generic acknowledgment.
     */
    private void sendGenericAck(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, 0x00, 0x00, (byte)0xD9, (byte)0xDB, 0x0D, 0x0A});
    }

    /**
     * Send error response.
     */
    private void sendErrorResponse(ChannelHandlerContext ctx) {
        sendResponse(ctx, new byte[]{0x78, 0x78, 0x05, 0x01, (byte)0xFF, (byte)0xFF, (byte)0xD8, (byte)0xDA, 0x0D, 0x0A});
    }

    /**
     * Send response to device.
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

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        var sessionId = ctx.channel().attr(SESSION_ID_ATTR).get();
        
        if (imei != null) {
            log.info("📵 Device {} disconnected (session: {})", imei, sessionId);
            
            // Remove session on disconnect
            try {
                sessionService.removeSession(ctx.channel());
            } catch (Exception e) {
                log.error("❌ Error removing session on disconnect: {}", e.getMessage(), e);
            }
        } else {
            log.debug("📵 Unknown device disconnected from {}", ctx.channel().remoteAddress());
        }
        
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        var imei = ctx.channel().attr(IMEI_ATTR).get();
        log.error("❌ Handler exception for device {} from {}: {}", 
                imei != null ? imei : "UNKNOWN", 
                ctx.channel().remoteAddress(), 
                cause.getMessage(), cause);
        
        // Close connection on severe errors
        ctx.close();
    }
}