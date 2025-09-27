package com.wheelseye.devicegateway.service;

import com.wheelseye.devicegateway.model.DeviceMessage;
import com.wheelseye.devicegateway.dto.CommandRequest;
import com.wheelseye.devicegateway.dto.CommandResponse;
import com.wheelseye.devicegateway.dto.CommandStatus;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GT06 Command Service - Integrates with Existing Architecture
 * 
 * Works with existing DeviceSessionService and DeviceMessage model
 * Provides async command processing with proper session management
 * 
 * @author WheelsEye Team
 * @version 1.0 - Integrated Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommandService {
    
    private final DeviceSessionService deviceSessionService;
    
    // In-memory command tracking (consider Redis for production clustering)
    private final ConcurrentHashMap<String, CommandStatus> commandCache = new ConcurrentHashMap<>();
    
    /**
     * Send command to device asynchronously
     */
    @Async("virtualThreadExecutor") // Use existing virtual thread executor
    public CompletableFuture<CommandResponse> sendCommand(CommandRequest request) {
        String commandId = UUID.randomUUID().toString();
        
        log.info("Processing GT06 command {} for device {}: {}", 
                commandId, request.getDeviceId(), request.getCommandType());
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create command status tracking
                CommandStatus status = createCommandStatus(commandId, request);
                commandCache.put(commandId, status);
                
                // Validate device session exists
                var sessionOpt = deviceSessionService.getSessionByImei(request.getDeviceId());
                if (sessionOpt.isEmpty()) {
                    return handleCommandFailure(status, "Device not connected or session not found");
                }
                
                var session = sessionOpt.get();
                if (!session.isActive() || !session.isChannelActive()) {
                    return handleCommandFailure(status, "Device session is not active");
                }
                
                // Create DeviceMessage compatible with existing encoder
                DeviceMessage commandMessage = createDeviceMessage(request);
                
                // Send command through the channel (integrate with existing pipeline)
                Channel deviceChannel = session.getChannel();
                if (deviceChannel != null && deviceChannel.isActive()) {
                    deviceChannel.writeAndFlush(commandMessage)
                        .addListener(future -> {
                            if (future.isSuccess()) {
                                status.setStatus("SENT");
                                status.setSentAt(Instant.now());
                                commandCache.put(commandId, status);
                                
                                log.info("✅ GT06 command {} sent to device {}", 
                                        commandId, request.getDeviceId());
                            } else {
                                status.setStatus("FAILED");
                                status.setErrorDetails("Failed to write to channel: " + 
                                                     future.cause().getMessage());
                                commandCache.put(commandId, status);
                                
                                log.error("❌ Failed to send command {} to device {}: {}", 
                                         commandId, request.getDeviceId(), future.cause().getMessage());
                            }
                        });
                    
                    // Mark as pending (will be updated by listener)
                    status.setStatus("PENDING");
                    commandCache.put(commandId, status);
                    
                    return CommandResponse.success(commandId, request.getDeviceId());
                    
                } else {
                    return handleCommandFailure(status, "Device channel is not active");
                }
                
            } catch (Exception e) {
                log.error("Command processing failed for {}: {}", commandId, e.getMessage(), e);
                return CommandResponse.error("Command processing failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Create DeviceMessage compatible with existing encoder
     */
    private DeviceMessage createDeviceMessage(CommandRequest request) {
        var data = Map.<String, Object>of(
            "command", request.getCommand(),
            "password", request.getPassword(),
            "serverFlag", request.getServerFlag(),
            "useEnglish", request.isUseEnglish(),
            "accOnInterval", request.getAccOnInterval(),
            "accOffInterval", request.getAccOffInterval(),
            "serverIp", request.getServerIp(),
            "serverPort", request.getServerPort()
        );
        
        return DeviceMessage.builder()
                .imei(request.getDeviceId())
                .protocol("GT06")
                .type(mapCommandTypeToMessageType(request.getCommandType()))
                .timestamp(request.getRequestedAt())
                .data(data)
                .build();
    }
    
    /**
     * Map command types to message types for encoder
     */
    private String mapCommandTypeToMessageType(String commandType) {
        return switch (commandType) {
            case "ENGINE_CUT_OFF" -> "engine_cut_off";
            case "ENGINE_RESTORE" -> "engine_restore";
            case "LOCATION_REQUEST" -> "location_request";
            case "DEVICE_RESET" -> "device_reset";
            case "STATUS_QUERY" -> "status_query";
            case "TIMER_CONFIG" -> "timer_config";
            case "SERVER_CONFIG" -> "server_config";
            default -> "gt06_command";
        };
    }
    
    /**
     * Create command status tracking record
     */
    private CommandStatus createCommandStatus(String commandId, CommandRequest request) {
        return CommandStatus.builder()
                .commandId(commandId)
                .deviceId(request.getDeviceId())
                .commandType(request.getCommandType())
                .command(request.getCommand())
                .status("CREATED")
                .createdAt(Instant.now())
                .retryCount(0)
                .expectedResponse(request.getExpectedResponse())
                .build();
    }
    
    /**
     * Handle command failure with proper status update
     */
    private CommandResponse handleCommandFailure(CommandStatus status, String errorMessage) {
        status.setStatus("FAILED");
        status.setErrorDetails(errorMessage);
        commandCache.put(status.getCommandId(), status);
        
        log.error("GT06 command failed - Device: {}, Command: {}, Error: {}", 
                 status.getDeviceId(), status.getCommandType(), errorMessage);
                 
        return CommandResponse.error(errorMessage);
    }
    
    /**
     * Get command status by ID
     */
    public CommandStatus getCommandStatus(String commandId) {
        return commandCache.get(commandId);
    }
    
    /**
     * Get command history for device (simplified in-memory version)
     */
    public List<CommandStatus> getCommandHistory(String deviceId, int page, int size) {
        return commandCache.values().stream()
                .filter(status -> deviceId.equals(status.getDeviceId()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }
    
    /**
     * Cancel pending command
     */
    public boolean cancelCommand(String commandId) {
        CommandStatus status = commandCache.get(commandId);
        if (status != null && "PENDING".equals(status.getStatus())) {
            status.setStatus("CANCELLED");
            status.setCancelledAt(Instant.now());
            commandCache.put(commandId, status);
            return true;
        }
        return false;
    }
    
    /**
     * Process command response from device (called by DeviceBusinessHandler)
     */
    public void processCommandResponse(String deviceId, String response) {
        log.debug("Processing command response from device {}: {}", deviceId, response);
        
        // Find pending commands for this device and match response
        commandCache.values().stream()
                .filter(status -> deviceId.equals(status.getDeviceId()))
                .filter(status -> "SENT".equals(status.getStatus()) || "PENDING".equals(status.getStatus()))
                .filter(status -> response.startsWith(status.getExpectedResponse()))
                .findFirst()
                .ifPresentOrElse(
                    status -> handleCommandSuccess(status, response),
                    () -> log.debug("No matching pending command found for response: {}", response)
                );
    }
    
    /**
     * Handle successful command response
     */
    private void handleCommandSuccess(CommandStatus status, String response) {
        status.setStatus("ACKNOWLEDGED");
        status.setAcknowledgedAt(Instant.now());
        status.setResponse(response);
        commandCache.put(status.getCommandId(), status);
        
        log.info("✅ GT06 command {} acknowledged by device {} with response: {}", 
                status.getCommandId(), status.getDeviceId(), response);
    }
    
    /**
     * Check if device has pending commands
     */
    public boolean hasPendingCommands(String deviceId) {
        return commandCache.values().stream()
                .anyMatch(status -> deviceId.equals(status.getDeviceId()) && 
                         ("PENDING".equals(status.getStatus()) || "SENT".equals(status.getStatus())));
    }
    
    /**
     * Get pending commands count for device
     */
    public long getPendingCommandsCount(String deviceId) {
        return commandCache.values().stream()
                .filter(status -> deviceId.equals(status.getDeviceId()))
                .filter(status -> "PENDING".equals(status.getStatus()) || "SENT".equals(status.getStatus()))
                .count();
    }
    
    /**
     * Cleanup old command entries (called periodically)
     */
    public void cleanupOldCommands() {
        Instant cutoff = Instant.now().minusSeconds(3600); // 1 hour
        
        commandCache.entrySet().removeIf(entry -> {
            CommandStatus status = entry.getValue();
            return status.getCreatedAt().isBefore(cutoff) && 
                   ("ACKNOWLEDGED".equals(status.getStatus()) || 
                    "FAILED".equals(status.getStatus()) || 
                    "CANCELLED".equals(status.getStatus()));
        });
    }
}