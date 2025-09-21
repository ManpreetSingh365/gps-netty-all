package com.wheelseye.devicegateway.handler;

import com.wheelseye.devicegateway.model.DeviceMessage;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Device Business Handler - FIXED VERSION
 * 
 * The issue was in the IMEI validation logic - it was rejecting valid 15-digit numeric IMEIs.
 * This fix corrects the validation to match the working reference implementation.
 */
@Component
@ChannelHandler.Sharable
public class DeviceBusinessHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DeviceBusinessHandler.class);
    
    @Autowired
    private DeviceSessionService deviceSessionService;
    
    private final ApplicationContext applicationContext;

    public DeviceBusinessHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DeviceMessage deviceMessage) {
            try {
                processDeviceMessage(ctx, deviceMessage);
            } catch (Exception e) {
                logger.error("Error processing device message from {}: {}", 
                    ctx.channel().remoteAddress(), e.getMessage(), e);
                sendErrorAck(ctx, deviceMessage);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private void processDeviceMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        String messageType = message.getType().toLowerCase();
        String imei = resolveImei(ctx, message);
        
        logger.info("Processing {} message from device {} at {}", 
            messageType, imei != null ? imei : "UNKNOWN", ctx.channel().remoteAddress());

        switch (messageType) {
            case "login" -> handleLogin(ctx, message);
            case "gps" -> handleGps(ctx, message);
            case "heartbeat" -> handleHeartbeat(ctx, message);
            case "other", "unknown" -> handleOther(ctx, message);
            default -> handleUnknown(ctx, message);
        }
    }

    /**
     * Handle login message - FIXED IMEI validation
     */
    private void handleLogin(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // CRITICAL FIX: Extract IMEI from login data where protocol decoder stored it
            String imei = extractLoginImei(message);
            
            // CRITICAL FIX: Corrected IMEI validation that matches working implementation
            if (!isValidImei(imei)) {
                logger.warn("Login rejected - invalid IMEI '{}' from {}", 
                    imei, ctx.channel().remoteAddress());
                ctx.close();
                return;
            }

            // Create device session
            try {
                IMEI imeiObj = IMEI.of(imei);
                DeviceSession session = deviceSessionService.createOrUpdateSession(imeiObj, ctx.channel());

                if (session != null) {
                    // Store session in channel attributes
                    ctx.channel().attr(DeviceSession.DEVICE_SESSION_KEY).set(session);
                    
                    // Send login acknowledgment
                    sendAck(ctx, message);
                    
                    logger.info("✅ Device {} logged in successfully from {}", 
                        imei, ctx.channel().remoteAddress());
                } else {
                    logger.warn("❌ Session creation failed for device {} from {}", 
                        imei, ctx.channel().remoteAddress());
                    ctx.close();
                }
            } catch (Exception e) {
                logger.error("❌ Error creating session for device {} from {}: {}", 
                    imei, ctx.channel().remoteAddress(), e.getMessage(), e);
                ctx.close();
            }
            
        } catch (Exception e) {
            logger.error("❌ Login processing failed for device from {}: {}", 
                ctx.channel().remoteAddress(), e.getMessage(), e);
            ctx.close();
        }
    }

    /**
     * Handle GPS location data
     */
    private void handleGps(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = getImeiFromSession(ctx);
        if (imei == null) return;

        try {
            Map<String, Object> data = message.getData();
            if (data != null && hasValidGpsData(data)) {
                Double latitude = (Double) data.get("latitude");
                Double longitude = (Double) data.get("longitude");
                Integer speed = (Integer) data.get("speed");
                
                logger.debug("📍 GPS from {}: lat={}, lon={}, speed={}km/h",
                    imei, latitude, longitude, speed);
                
                // Update device position
                updateDevicePosition(imei, latitude, longitude, getTimestamp(message));
                
                // Send GPS acknowledgment
                sendAck(ctx, message);
            } else {
                logger.warn("⚠️ Invalid GPS data from device {}", imei);
            }
        } catch (Exception e) {
            logger.error("❌ Error processing GPS data from {}: {}", imei, e.getMessage(), e);
        }
    }

    /**
     * Handle heartbeat/status message
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = getImeiFromSession(ctx);
        if (imei == null) return;

        try {
            Map<String, Object> data = message.getData();
            if (data != null) {
                Integer voltage = (Integer) data.get("voltageLevel");
                Integer signal = (Integer) data.get("gsmSignalStrength");
                Boolean charging = (Boolean) data.get("charging");
                
                logger.debug("💓 Heartbeat from {}: battery={}, signal={}, charging={}",
                    imei, voltage, signal, charging);
            }
            
            // Update last heartbeat timestamp
            updateDeviceHeartbeat(imei);
            
            // Send heartbeat acknowledgment
            sendAck(ctx, message);
            
        } catch (Exception e) {
            logger.error("❌ Error processing heartbeat from {}: {}", imei, e.getMessage(), e);
        }
    }

    /**
     * Handle other/unknown message types
     */
    private void handleOther(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = getImeiFromSessionOrMessage(ctx, message);
        logger.info("📝 Other message from {}: protocol={}", 
            imei != null ? imei : "UNKNOWN", 
            message.getData() != null ? message.getData().get("protocolId") : "unknown");

        // Send acknowledgment
        sendAck(ctx, message);
    }

    private void handleUnknown(ChannelHandlerContext ctx, DeviceMessage message) {
        logger.warn("❓ Unknown message type '{}' from {}", 
            message.getType(), ctx.channel().remoteAddress());
        sendAck(ctx, message);
    }

    // CRITICAL FIX: Proper IMEI extraction from login data
    private String extractLoginImei(DeviceMessage message) {
        // First, check if IMEI was extracted and stored in login data by protocol decoder
        if (message.getData() != null && message.getData().containsKey("loginImei")) {
            Object loginImei = message.getData().get("loginImei");
            if (loginImei != null) {
                String imei = loginImei.toString();
                logger.info("📱 Extracted IMEI from login data: {}", imei);
                return imei;
            }
        }
        
        // Fallback to message IMEI field
        String imei = message.getImei();
        logger.debug("📱 Using message IMEI field: {}", imei);
        return imei;
    }

    // CRITICAL FIX: Corrected IMEI validation that matches working implementation
    private boolean isValidImei(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            logger.debug("IMEI validation failed: null or empty");
            return false;
        }
        
        // Remove whitespace
        imei = imei.trim();
        
        // Check for placeholder values
        if (imei.startsWith("UNKNOWN") || imei.startsWith("DEVICE_")) {
            logger.debug("IMEI validation failed: placeholder value: {}", imei);
            return false;
        }
        
        // Check length (must be exactly 15 digits)
        if (imei.length() != 15) {
            logger.debug("IMEI validation failed: invalid length {}: {}", imei.length(), imei);
            return false;
        }
        
        // CRITICAL FIX: Use simple character-by-character validation instead of regex
        for (int i = 0; i < imei.length(); i++) {
            char c = imei.charAt(i);
            if (c < '0' || c > '9') {
                logger.debug("IMEI validation failed: non-digit character '{}' at position {} in: {}", 
                    c, i, imei);
                return false;
            }
        }
        
        // Additional check: IMEI should not be all zeros
        if ("000000000000000".equals(imei)) {
            logger.debug("IMEI validation failed: all zeros");
            return false;
        }
        
        logger.debug("IMEI validation passed: {}", imei);
        return true;
    }

    private String resolveImei(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = message.getImei();
        
        // For login messages, extract from login data
        if ("login".equals(message.getType())) {
            return extractLoginImei(message);
        }
        
        // For other messages, try session first
        if (!isValidImei(imei)) {
            imei = getImeiFromSession(ctx);
        }
        
        return imei;
    }

    private String getImeiFromSession(ChannelHandlerContext ctx) {
        DeviceSession session = ctx.channel().attr(DeviceSession.DEVICE_SESSION_KEY).get();
        if (session != null) {
            return session.getImei().value();
        }
        
        logger.error("❌ No device session found for channel {}", ctx.channel().remoteAddress());
        ctx.close();
        return null;
    }

    private String getImeiFromSessionOrMessage(ChannelHandlerContext ctx, DeviceMessage message) {
        String imei = getImeiFromSession(ctx);
        if (imei == null) {
            imei = message.getImei();
        }
        return imei;
    }

    private boolean hasValidGpsData(Map<String, Object> data) {
        return data.containsKey("latitude") && data.containsKey("longitude") &&
               data.get("latitude") instanceof Double && data.get("longitude") instanceof Double;
    }

    private Instant getTimestamp(DeviceMessage message) {
        if (message.getData() != null && message.getData().containsKey("timestamp")) {
            return (Instant) message.getData().get("timestamp");
        }
        return message.getTimestamp();
    }

    // Service integration methods
    private void updateDevicePosition(String imei, double latitude, double longitude, Instant timestamp) {
        try {
            if (deviceSessionService != null) {
                deviceSessionService.updateLastPosition(imei, latitude, longitude, timestamp);
            }
        } catch (Exception e) {
            logger.debug("Error updating device position: {}", e.getMessage());
        }
    }

    private void updateDeviceHeartbeat(String imei) {
        try {
            if (deviceSessionService != null) {
                deviceSessionService.updateLastHeartbeat(imei);
            }
        } catch (Exception e) {
            logger.debug("Error updating device heartbeat: {}", e.getMessage());
        }
    }

    // Response methods - Send simple ACK responses matching GT06 protocol
    private void sendAck(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            // Echo the message back as acknowledgment (GT06 protocol expects this)
            DeviceMessage ack = new DeviceMessage(
                message.getImei(), "GT06", message.getType(), Instant.now(), message.getData());
            ctx.writeAndFlush(ack);
            
            logger.debug("➡️ Sent ACK for {} to {}", 
                message.getType(), ctx.channel().remoteAddress());
        } catch (Exception e) {
            logger.error("Failed to send ACK: {}", e.getMessage());
        }
    }

    private void sendErrorAck(ChannelHandlerContext ctx, DeviceMessage message) {
        try {
            sendAck(ctx, message);
        } catch (Exception e) {
            logger.error("Failed to send error ACK: {}", e.getMessage());
        }
    }

    // Channel lifecycle methods
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        DeviceSession session = ctx.channel().attr(DeviceSession.DEVICE_SESSION_KEY).get();
        if (session != null) {
            logger.info("📤 Device {} disconnected from {}", 
                session.getImei().value(), ctx.channel().remoteAddress());
            
            try {
                if (deviceSessionService != null) {
                    deviceSessionService.removeSession(session.getId());
                }
            } catch (Exception e) {
                logger.error("Error removing session on disconnect: {}", e.getMessage());
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Business handler exception from {}: {}", 
            ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}