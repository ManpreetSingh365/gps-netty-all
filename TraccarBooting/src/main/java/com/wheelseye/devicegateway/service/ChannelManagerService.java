package com.wheelseye.devicegateway.service;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.wheelseye.devicegateway.model.DeviceMessage;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Channel Manager Service - Tracks Active Channels
 * 
 * Works with existing DeviceBusinessHandler to maintain channel references
 * that are lost during Redis session serialization.
 */
@Slf4j
@Service
public class ChannelManagerService {

    private static final AttributeKey<String> IMEI_ATTR = AttributeKey.valueOf("DEVICE_IMEI");

    // Track active channels by IMEI
    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();

    /**
     * Register active channel for device
     */
    public void registerChannel(String imei, Channel channel) {
        if (imei != null && channel != null && channel.isActive()) {
            activeChannels.put(imei, channel);
            log.debug("📡 Registered active channel for device: {}", imei);
        }
    }

    /**
     * Unregister channel for device
     */
    public void unregisterChannel(String imei) {
        if (imei != null) {
            Channel removed = activeChannels.remove(imei);
            if (removed != null) {
                log.debug("📴 Unregistered channel for device: {}", imei);
            }
        }
    }

    /**
     * Get active channel for device
     */
    public Optional<Channel> getActiveChannel(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return Optional.empty();
        }

        Channel channel = activeChannels.get(imei);

        // Verify channel is still active
        if (channel != null && channel.isActive()) {
            return Optional.of(channel);
        } else if (channel != null) {
            // Clean up inactive channel
            activeChannels.remove(imei);
            log.debug("🧹 Cleaned up inactive channel for device: {}", imei);
        }

        return Optional.empty();
    }

    /**
     * Check if device has active channel
     */
    public boolean hasActiveChannel(String imei) {
        return getActiveChannel(imei).isPresent();
    }

    /**
     * Get count of active channels
     */
    public int getActiveChannelCount() {
        // Clean up inactive channels first
        activeChannels.entrySet().removeIf(entry -> entry.getValue() == null || !entry.getValue().isActive());

        return activeChannels.size();
    }

    /**
     * CORRECTED: Send GT06 command using DeviceMessage (not raw String)
     * Service Layer → prepares DeviceMessage and finds Channel
     */
    public boolean sendGT06Command(String imei, String commandType, String command, String password, int serverFlag) {

        Optional<Channel> channelOpt = getActiveChannel(imei);

        if (channelOpt.isEmpty()) {
            log.warn("❌ No active channel found for device: {}", imei);
            return false;
        }

        Channel channel = channelOpt.get();

        try {
            // Prepare DeviceMessage (business layer representation)
            Map<String, Object> data = new HashMap<>();
            data.put("command", command);
            data.put("password", password);
            data.put("serverFlag", serverFlag);
            data.put("useEnglish", true);

            DeviceMessage deviceMessage = DeviceMessage.builder()
                    .imei(imei)
                    .type(commandType) // "engine_cut_off" or "engine_restore"
                    .timestamp(Instant.now())
                    .data(data)
                    .build();
            log.debug("Prepared DeviceMessage for {}: {}", imei, deviceMessage);

            // 🔥 Send DeviceMessage through Netty (will hit encoder pipeline)
            channel.writeAndFlush(deviceMessage).addListener(future -> {
                if (future.isSuccess()) {
                    log.info("✅ GT06 command sent to {}: {} ({})", imei, commandType, command);
                } else {
                    log.error("❌ Failed to send GT06 command to {}: {}",
                            imei, future.cause().getMessage());
                }
            });

            return true;

        } catch (Exception e) {
            log.error("❌ Error sending GT06 command to {}: {}", imei, e.getMessage(), e);
            return false;
        }
    }
}
