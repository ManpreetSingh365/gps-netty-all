// package com.wheelseye.devicegateway.service;

// import com.wheelseye.devicegateway.model.DeviceMessage;
// import com.wheelseye.devicegateway.model.DeviceSession;
// import com.wheelseye.devicegateway.repository.DeviceSessionRepository;
// import io.netty.channel.Channel;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.scheduling.annotation.Async;
// import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Service;

// import java.time.Duration;
// import java.time.Instant;
// import java.util.*;
// import java.util.concurrent.CompletableFuture;
// import java.util.concurrent.ConcurrentHashMap;

// /**
//  * Unified Device Connection Manager - Simple & Working
//  * 
//  * Combines ChannelManagerService + DeviceSessionService functionality
//  * with minimal changes to existing code structure.
//  * 
//  * @author WheelsEye Development Team
//  * @version 3.0.0 - Simplified & Unified
//  */
// @Slf4j
// @Service
// @RequiredArgsConstructor
// public class DeviceConnectionManager {

//     private final DeviceSessionRepository sessionRepository;
    
//     // =========== DUAL STORAGE ===========
    
//     // Memory: Active channels for fast command sending
//     private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();
    
//     // Memory: Channel to IMEI lookup for cleanup
//     private final ConcurrentHashMap<String, String> channelToImei = new ConcurrentHashMap<>();
    
//     // Configuration
//     private static final Duration SESSION_IDLE_TIMEOUT = Duration.ofMinutes(15);

//     // =========== UNIFIED DEVICE LIFECYCLE ===========

//     /**
//      * Register device - handles both channel and session
//      */
//     public DeviceSession registerDevice(String imei, Channel channel) {
//         if (imei == null || imei.trim().isEmpty() || !imei.matches("\\d{15}")) {
//             throw new IllegalArgumentException("Invalid IMEI: " + imei);
//         }
//         if (channel == null || !channel.isActive()) {
//             throw new IllegalArgumentException("Channel must be active");
//         }

//         try {
//             log.info("📱 Registering device: {}", imei);
            
//             // Register channel in memory
//             registerChannelInMemory(imei, channel);
            
//             // Create/update session in Redis
//             DeviceSession session = createOrUpdateSession(imei, channel);
            
//             log.info("✅ Device registered: {} (Session: {})", imei, session.getId());
//             return session;
            
//         } catch (Exception e) {
//             log.error("❌ Failed to register device {}: {}", imei, e.getMessage(), e);
//             // Cleanup on failure
//             removeChannelFromMemory(imei);
//             throw new RuntimeException("Device registration failed", e);
//         }
//     }

//     /**
//      * Unregister device - cleans up both channel and session
//      */
//     public boolean unregisterDevice(String imei) {
//         if (imei == null || imei.trim().isEmpty()) {
//             return false;
//         }

//         try {
//             log.info("📱 Unregistering device: {}", imei);
            
//             // Remove from memory
//             boolean channelRemoved = removeChannelFromMemory(imei);
            
//             // Remove from Redis
//             boolean sessionRemoved = disconnectSession(imei);
            
//             if (channelRemoved || sessionRemoved) {
//                 log.info("✅ Device unregistered: {}", imei);
//                 return true;
//             } else {
//                 log.debug("📭 No connection found for: {}", imei);
//                 return false;
//             }
            
//         } catch (Exception e) {
//             log.error("❌ Failed to unregister device {}: {}", imei, e.getMessage(), e);
//             return false;
//         }
//     }

//     /**
//      * Unregister by channel (for disconnect events)
//      */
//     public boolean unregisterDeviceByChannel(Channel channel) {
//         if (channel == null) {
//             return false;
//         }

//         String channelId = channel.id().asShortText();
//         String imei = channelToImei.get(channelId);
        
//         if (imei != null) {
//             return unregisterDevice(imei);
//         } else {
//             log.debug("📭 No IMEI found for channel: {}", channelId);
//             return false;
//         }
//     }

//     // =========== CHANNEL MANAGEMENT (from ChannelManagerService) ===========

//     /**
//      * Register channel in memory
//      */
//     private void registerChannelInMemory(String imei, Channel channel) {
//         String channelId = channel.id().asShortText();
        
//         // Remove existing channel if any
//         Channel existingChannel = activeChannels.get(imei);
//         if (existingChannel != null) {
//             String oldChannelId = existingChannel.id().asShortText();
//             channelToImei.remove(oldChannelId);
//             log.debug("🔄 Replacing existing channel for: {}", imei);
//         }
        
//         // Register new channel
//         activeChannels.put(imei, channel);
//         channelToImei.put(channelId, imei);
        
//         log.debug("📡 Channel registered: {} -> {}", imei, channelId);
//     }

//     /**
//      * Remove channel from memory
//      */
//     private boolean removeChannelFromMemory(String imei) {
//         Channel channel = activeChannels.remove(imei);
//         if (channel != null) {
//             String channelId = channel.id().asShortText();
//             channelToImei.remove(channelId);
//             log.debug("📴 Channel removed: {}", imei);
//             return true;
//         }
//         return false;
//     }

//     /**
//      * Get active channel for device
//      */
//     public Optional<Channel> getActiveChannel(String imei) {
//         if (imei == null || imei.trim().isEmpty()) {
//             return Optional.empty();
//         }

//         Channel channel = activeChannels.get(imei);
//         if (channel != null && channel.isActive()) {
//             return Optional.of(channel);
//         } else if (channel != null) {
//             // Clean up inactive channel
//             removeChannelFromMemory(imei);
//             log.debug("🧹 Cleaned inactive channel for: {}", imei);
//         }
        
//         return Optional.empty();
//     }

//     /**
//      * Check if device has active channel
//      */
//     public boolean hasActiveChannel(String imei) {
//         return getActiveChannel(imei).isPresent();
//     }

//     /**
//      * Get count of active channels
//      */
//     public int getActiveChannelCount() {
//         // Clean up inactive channels
//         activeChannels.entrySet().removeIf(entry -> 
//             entry.getValue() == null || !entry.getValue().isActive());
//         return activeChannels.size();
//     }

//     /**
//      * Send GT06 command to device
//      */
//     public boolean sendGT06Command(String imei, String commandType, String command,
//                                   String password, int serverFlag) {
//         Optional<Channel> channelOpt = getActiveChannel(imei);
        
//         if (channelOpt.isEmpty()) {
//             log.warn("❌ No active channel for device: {}", imei);
//             return false;
//         }
        
//         Channel channel = channelOpt.get();
        
//         try {
//             // Create command message
//             Map<String, Object> data = new HashMap<>();
//             data.put("command", command);
//             data.put("password", password);
//             data.put("serverFlag", serverFlag);
//             data.put("useEnglish", true);
            
//             DeviceMessage deviceMessage = DeviceMessage.builder()
//                 .imei(imei)
//                 .type(commandType)
//                 .timestamp(Instant.now())
//                 .data(data)
//                 .build();
            
//             log.info("📤 Sending command to {}: {} ({})", imei, commandType, command);
            
//             // Send command
//             channel.writeAndFlush(deviceMessage)
//                 .addListener(future -> {
//                     if (future.isSuccess()) {
//                         log.info("✅ Command sent successfully to {}: {}", imei, commandType);
//                         touchSession(imei); // Update session activity
//                     } else {
//                         log.error("❌ Failed to send command to {}: {}", 
//                                  imei, future.cause().getMessage());
//                     }
//                 });
            
//             return true;
            
//         } catch (Exception e) {
//             log.error("❌ Error sending command to {}: {}", imei, e.getMessage(), e);
//             return false;
//         }
//     }

//     // =========== SESSION MANAGEMENT (from DeviceSessionService) ===========

//     /**
//      * Create or update session in Redis
//      */
//     private DeviceSession createOrUpdateSession(String imei, Channel channel) {
//         try {
//             Optional<DeviceSession> existingOpt = sessionRepository.findByImei(imei);
            
//             if (existingOpt.isPresent()) {
//                 log.debug("🔄 Updating existing session for: {}", imei);
//                 DeviceSession session = existingOpt.get()
//                     .setChannel(channel)
//                     .setStatus("ACTIVE")
//                     .touch();
//                 return sessionRepository.save(session);
//             } else {
//                 log.debug("🆕 Creating new session for: {}", imei);
//                 DeviceSession session = DeviceSession.create(imei, channel);
//                 return sessionRepository.save(session);
//             }
//         } catch (Exception e) {
//             log.error("❌ Session operation failed for {}: {}", imei, e.getMessage(), e);
//             throw new RuntimeException("Session creation failed", e);
//         }
//     }

//     /**
//      * Disconnect session and remove from Redis
//      */
//     private boolean disconnectSession(String imei) {
//         try {
//             Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//             if (sessionOpt.isPresent()) {
//                 DeviceSession session = sessionOpt.get().markDisconnected();
//                 sessionRepository.deleteById(session.getId());
//                 log.debug("📵 Session removed: {}", imei);
//                 return true;
//             }
//             return false;
//         } catch (Exception e) {
//             log.error("❌ Failed to remove session for {}: {}", imei, e.getMessage(), e);
//             return false;
//         }
//     }

//     /**
//      * Get session by IMEI (with channel restoration)
//      */
//     public Optional<DeviceSession> getSessionByImei(String imei) {
//         if (imei == null || imei.trim().isEmpty()) {
//             return Optional.empty();
//         }

//         try {
//             Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//             if (sessionOpt.isPresent()) {
//                 DeviceSession session = sessionOpt.get();
                
//                 // Restore channel reference if available
//                 getActiveChannel(imei).ifPresent(session::setChannel);
                
//                 return Optional.of(session);
//             }
//             return Optional.empty();
//         } catch (Exception e) {
//             log.error("❌ Error getting session for {}: {}", imei, e.getMessage(), e);
//             return Optional.empty();
//         }
//     }

//     /**
//      * Touch session to update activity
//      */
//     public boolean touchSession(String imei) {
//         try {
//             Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//             if (sessionOpt.isPresent()) {
//                 DeviceSession session = sessionOpt.get().touch();
//                 sessionRepository.save(session);
//                 log.debug("👋 Session touched: {}", imei);
//                 return true;
//             }
//             return false;
//         } catch (Exception e) {
//             log.error("❌ Failed to touch session for {}: {}", imei, e.getMessage(), e);
//             return false;
//         }
//     }

//     /**
//      * Update position asynchronously
//      */
//     @Async
//     public CompletableFuture<Boolean> updatePositionAsync(String imei, double latitude, 
//                                                          double longitude, Instant timestamp) {
//         return CompletableFuture.supplyAsync(() -> {
//             try {
//                 Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//                 if (sessionOpt.isPresent()) {
//                     DeviceSession session = sessionOpt.get()
//                         .updatePosition(latitude, longitude, timestamp)
//                         .touch();
//                     sessionRepository.save(session);
//                     log.debug("📍 Position updated: {} -> [{}, {}]", imei, latitude, longitude);
//                     return true;
//                 }
//                 return false;
//             } catch (Exception e) {
//                 log.error("❌ Position update failed for {}: {}", imei, e.getMessage(), e);
//                 return false;
//             }
//         });
//     }

//     /**
//      * Update device status asynchronously
//      */
//     @Async
//     public CompletableFuture<Boolean> updateStatusAsync(String imei, int signalStrength, 
//                                                        boolean isCharging, int batteryLevel) {
//         return CompletableFuture.supplyAsync(() -> {
//             try {
//                 Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//                 if (sessionOpt.isPresent()) {
//                     DeviceSession session = sessionOpt.get()
//                         .updateStatus(signalStrength, isCharging, batteryLevel)
//                         .touch();
//                     sessionRepository.save(session);
//                     log.debug("📊 Status updated: {} -> signal={}, charging={}, battery={}%",
//                              imei, signalStrength, isCharging, batteryLevel);
//                     return true;
//                 }
//                 return false;
//             } catch (Exception e) {
//                 log.error("❌ Status update failed for {}: {}", imei, e.getMessage(), e);
//                 return false;
//             }
//         });
//     }

//     /**
//      * Authenticate session
//      */
//     public boolean authenticateSession(String imei) {
//         try {
//             Optional<DeviceSession> sessionOpt = sessionRepository.findByImei(imei);
//             if (sessionOpt.isPresent()) {
//                 DeviceSession session = sessionOpt.get()
//                     .setAuthenticated(true)
//                     .setStatus("ACTIVE")
//                     .touch();
//                 sessionRepository.save(session);
//                 log.info("🔐 Session authenticated: {}", imei);
//                 return true;
//             }
//             return false;
//         } catch (Exception e) {
//             log.error("❌ Authentication failed for {}: {}", imei, e.getMessage(), e);
//             return false;
//         }
//     }

//     // =========== STATISTICS & QUERIES ===========

//     /**
//      * Get all active device IMEIs
//      */
//     public Set<String> getActiveDeviceImeis() {
//         return new HashSet<>(activeChannels.keySet());
//     }

//     /**
//      * Check if device is connected (has both session and channel)
//      */
//     public boolean isDeviceConnected(String imei) {
//         return hasActiveChannel(imei) && getSessionByImei(imei).isPresent();
//     }

//     /**
//      * Get connection statistics
//      */
//     public ConnectionStatistics getStatistics() {
//         try {
//             List<DeviceSession> allSessions = sessionRepository.findAll();
            
//             long totalSessions = allSessions.size();
//             long authenticatedSessions = allSessions.stream()
//                 .filter(DeviceSession::isAuthenticated).count();
//             long sessionsWithLocation = allSessions.stream()
//                 .filter(DeviceSession::hasValidLocation).count();
            
//             int activeChannels = getActiveChannelCount();
//             boolean healthy = sessionRepository.isHealthy() && activeChannels >= 0;
            
//             return new ConnectionStatistics(
//                 totalSessions,
//                 authenticatedSessions,
//                 sessionsWithLocation,
//                 activeChannels,
//                 healthy,
//                 Instant.now()
//             );
//         } catch (Exception e) {
//             log.error("❌ Error getting statistics: {}", e.getMessage(), e);
//             return new ConnectionStatistics(0, 0, 0, 0, false, Instant.now());
//         }
//     }

//     // =========== CLEANUP ===========

//     /**
//      * Scheduled cleanup of idle sessions and inactive channels
//      */
//     @Scheduled(fixedRate = 300_000) // 5 minutes
//     public void scheduledCleanup() {
//         try {
//             log.debug("🧹 Starting cleanup...");
            
//             int idleSessionsCleaned = cleanupIdleSessions();
//             int inactiveChannelsCleaned = cleanupInactiveChannels();
            
//             if (idleSessionsCleaned > 0 || inactiveChannelsCleaned > 0) {
//                 log.info("🧹 Cleanup completed: {} idle sessions, {} inactive channels",
//                         idleSessionsCleaned, inactiveChannelsCleaned);
//             }
//         } catch (Exception e) {
//             log.error("❌ Error during cleanup: {}", e.getMessage(), e);
//         }
//     }

//     private int cleanupIdleSessions() {
//         try {
//             Instant cutoffTime = Instant.now().minus(SESSION_IDLE_TIMEOUT);
//             List<DeviceSession> idleSessions = sessionRepository.findByLastActivityAtBefore(cutoffTime);
            
//             int cleaned = 0;
//             for (DeviceSession session : idleSessions) {
//                 try {
//                     removeChannelFromMemory(session.getImei());
//                     sessionRepository.deleteById(session.getId());
//                     cleaned++;
//                 } catch (Exception e) {
//                     log.warn("⚠️ Failed to cleanup session {}: {}", 
//                             session.getId(), e.getMessage());
//                 }
//             }
//             return cleaned;
//         } catch (Exception e) {
//             log.error("❌ Error cleaning idle sessions: {}", e.getMessage(), e);
//             return 0;
//         }
//     }

//     private int cleanupInactiveChannels() {
//         int cleaned = 0;
        
//         var iterator = activeChannels.entrySet().iterator();
//         while (iterator.hasNext()) {
//             var entry = iterator.next();
//             Channel channel = entry.getValue();
//             String imei = entry.getKey();
            
//             if (channel == null || !channel.isActive()) {
//                 iterator.remove();
//                 if (channel != null) {
//                     channelToImei.remove(channel.id().asShortText());
//                 }
//                 cleaned++;
//                 log.debug("🧹 Cleaned inactive channel for: {}", imei);
//             }
//         }
//         return cleaned;
//     }

//     // =========== LEGACY COMPATIBILITY METHODS ===========

//     /**
//      * Legacy: Create or update session (maps to registerDevice)
//      */
//     // @Deprecated
//     // public DeviceSession createOrUpdateSession(String imei, Channel channel) {
//     //     return registerDevice(imei, channel);
//     // }

//     /**
//      * Legacy: Remove session by channel
//      */
//     @Deprecated
//     public void removeSession(Channel channel) {
//         unregisterDeviceByChannel(channel);
//     }

//     /**
//      * Legacy: Update last position
//      */
//     @Deprecated
//     public void updateLastPosition(String imei, Double latitude, Double longitude, Instant timestamp) {
//         if (latitude != null && longitude != null) {
//             updatePositionAsync(imei, latitude, longitude, timestamp);
//         }
//     }

//     /**
//      * Legacy: Update last heartbeat
//      */
//     @Deprecated
//     public void updateLastHeartbeat(String imei) {
//         touchSession(imei);
//     }

//     /**
//      * Legacy: Disconnect device
//      */
//     @Deprecated
//     public boolean disconnectDevice(String imei) {
//         return unregisterDevice(imei);
//     }

//     // =========== DATA CLASSES ===========

//     /**
//      * Connection statistics record
//      */
//     public record ConnectionStatistics(
//         long totalSessions,
//         long authenticatedSessions,
//         long sessionsWithLocation,
//         int activeChannels,
//         boolean healthy,
//         Instant timestamp
//     ) {}
// }