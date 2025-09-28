package com.wheelseye.devicegateway.controller;

import com.wheelseye.devicegateway.dto.CommandRequest;
import com.wheelseye.devicegateway.dto.CommandResponse;
import com.wheelseye.devicegateway.dto.CommandStatus;
import com.wheelseye.devicegateway.service.CommandService;
import com.wheelseye.devicegateway.service.DeviceSessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GT06 Command Controller - Integrated with Existing Architecture
 * 
 * Provides REST API for sending GT06 commands to GPS devices
 * Integrates seamlessly with existing DeviceController and services
 * 
 * @author WheelsEye Team
 * @version 1.0 - Integrated Implementation
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/commands")
@Tag(name = "GT06 Commands", description = "GT06 GPS Device Command Management API")
@Validated
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;
    private final DeviceSessionService deviceSessionService;

    /**
     * Engine Cut Off Command - DYD#
     */
    @PostMapping("/engine/cut-off")
    @Operation(summary = "Cut off engine (DYD)", description = "Send DYD command to cut off vehicle engine/power")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Command queued successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @ApiResponse(responseCode = "404", description = "Device not connected"),
            @ApiResponse(responseCode = "500", description = "Command processing failed")
    })
    public CompletableFuture<ResponseEntity<CommandResponse>> cutOffEngine(
            @Parameter(description = "Device IMEI (15 digits)", required = true) @RequestParam String deviceId,
            @Parameter(description = "Device password (optional)") @RequestParam(required = false) String password,
            @Parameter(description = "Server identification flag") @RequestParam(defaultValue = "1") int serverFlag) {

        log.info("Engine cut-off requested for device: {}", deviceId);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command("DYD#")
                .password(password)
                .serverFlag(serverFlag)
                .commandType("ENGINE_CUT_OFF")
                .requestedAt(Instant.now())
                .expectedResponse("DYD=Success!")
                .build();

        return commandService.sendCommand(request)
                .thenApply(response -> {
                    if (response.isSuccess()) {
                        log.info("✅ Engine cut-off command queued for device: {}, commandId: {}",
                                deviceId, response.getCommandId());
                        return ResponseEntity.ok(response);
                    } else {
                        log.error("❌ Engine cut-off failed for device: {}: {}", deviceId, response.getMessage());
                        return ResponseEntity.badRequest().body(response);
                    }
                })
                .exceptionally(throwable -> {
                    log.error("❌ Engine cut-off processing failed for device: {}", deviceId, throwable);
                    return ResponseEntity.internalServerError()
                            .body(CommandResponse.error("Internal processing error: " + throwable.getMessage()));
                });
    }

    /**
     * Engine Restore Command - HFYD#
     */
    @PostMapping("/engine/restore")
    @Operation(summary = "Restore engine (HFYD)", description = "Send HFYD command to restore vehicle engine/power")
    public CompletableFuture<ResponseEntity<CommandResponse>> restoreEngine(
            @RequestParam String deviceId,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1") int serverFlag) {

        log.info("Engine restore requested for device: {}", deviceId);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command("HFYD#")
                .password(password)
                .serverFlag(serverFlag)
                .commandType("ENGINE_RESTORE")
                .requestedAt(Instant.now())
                .expectedResponse("HFYD=Success!")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Command failed: " + throwable.getMessage())));
    }

    /**
     * Location Request Command - DWXX#
     */
    @PostMapping("/location/request")
    @Operation(summary = "Request location (DWXX)", description = "Send DWXX command to request current location")
    public CompletableFuture<ResponseEntity<CommandResponse>> requestLocation(
            @RequestParam String deviceId,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1") int serverFlag) {

        log.info("Location request for device: {}", deviceId);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command("DWXX#")
                .password(password)
                .serverFlag(serverFlag)
                .commandType("LOCATION_REQUEST")
                .requestedAt(Instant.now())
                .expectedResponse("DWXX=Lat:")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Location request failed: " + throwable.getMessage())));
    }

    /**
     * Device Reset Command - RESET#
     */
    @PostMapping("/device/reset")
    @Operation(summary = "Reset device (RESET)", description = "Send RESET command to reboot device")
    public CompletableFuture<ResponseEntity<CommandResponse>> resetDevice(
            @RequestParam String deviceId,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1") int serverFlag) {

        log.info("Device reset requested for device: {}", deviceId);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command("RESET#")
                .password(password)
                .serverFlag(serverFlag)
                .commandType("DEVICE_RESET")
                .requestedAt(Instant.now())
                .expectedResponse("RESET OK")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Reset command failed: " + throwable.getMessage())));
    }

    /**
     * Status Query Command - STATUS#
     */
    @PostMapping("/device/status")
    @Operation(summary = "Query status (STATUS)", description = "Send STATUS command to query device status")
    public CompletableFuture<ResponseEntity<CommandResponse>> queryStatus(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "1") int serverFlag) {

        log.info("Status query for device: {}", deviceId);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command("STATUS#")
                .serverFlag(serverFlag)
                .commandType("STATUS_QUERY")
                .requestedAt(Instant.now())
                .expectedResponse("Battery:")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Status query failed: " + throwable.getMessage())));
    }

    /**
     * Timer Configuration - TIMER,T1,T2#
     */
    @PostMapping("/config/timer")
    @Operation(summary = "Configure timer (TIMER)", description = "Configure GPS data upload intervals")
    public CompletableFuture<ResponseEntity<CommandResponse>> configureTimer(
            @RequestParam String deviceId,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1") int serverFlag,
            @Parameter(description = "ACC ON interval (5-60 seconds)") @RequestParam @Min(5) @Max(60) int accOnInterval,
            @Parameter(description = "ACC OFF interval (5-1800 seconds)") @RequestParam @Min(5) @Max(1800) int accOffInterval) {

        log.info("Timer configuration for device: {} - ACC ON: {}s, ACC OFF: {}s",
                deviceId, accOnInterval, accOffInterval);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command(String.format("TIMER,%d,%d#", accOnInterval, accOffInterval))
                .password(password)
                .serverFlag(serverFlag)
                .commandType("TIMER_CONFIG")
                .accOnInterval(accOnInterval)
                .accOffInterval(accOffInterval)
                .requestedAt(Instant.now())
                .expectedResponse("TIMER ACC ON")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Timer configuration failed: " + throwable.getMessage())));
    }

    /**
     * Server Configuration - SERVER,0,IP,PORT,0#
     */
    @PostMapping("/config/server")
    @Operation(summary = "Configure server (SERVER)", description = "Update server IP and port settings")
    public CompletableFuture<ResponseEntity<CommandResponse>> configureServer(
            @RequestParam String deviceId,
            @RequestParam(required = false) String password,
            @RequestParam(defaultValue = "1") int serverFlag,
            @RequestParam String serverIp,
            @RequestParam int serverPort) {

        log.info("Server configuration for device: {} - Server: {}:{}", deviceId, serverIp, serverPort);

        CommandRequest request = CommandRequest.builder()
                .deviceId(deviceId)
                .command(String.format("SERVER,0,%s,%d,0#", serverIp, serverPort))
                .password(password)
                .serverFlag(serverFlag)
                .commandType("SERVER_CONFIG")
                .serverIp(serverIp)
                .serverPort(serverPort)
                .requestedAt(Instant.now())
                .expectedResponse("SERVER 0")
                .build();

        return commandService.sendCommand(request)
                .thenApply(ResponseEntity::ok)
                .exceptionally(throwable -> ResponseEntity.internalServerError()
                        .body(CommandResponse.error("Server configuration failed: " + throwable.getMessage())));
    }

    /**
     * Get command status by ID
     */
    @GetMapping("/{commandId}/status")
    @Operation(summary = "Get command status", description = "Retrieve command status by ID")
    public ResponseEntity<CommandStatus> getCommandStatus(
            @PathVariable String commandId) {

        try {
            CommandStatus status = commandService.getCommandStatus(commandId);
            if (status != null) {
                return ResponseEntity.ok(status);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Status retrieval failed for command: {}", commandId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get command history for device
     */
    @GetMapping("/device/{deviceId}/history")
    @Operation(summary = "Get command history", description = "Retrieve command history for device")
    public ResponseEntity<List<CommandStatus>> getCommandHistory(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        try {
            // First verify device exists
            var sessionOpt = deviceSessionService.getSessionByImei(deviceId);
            if (sessionOpt.isEmpty()) {
                log.warn("Command history requested for unknown device: {}", deviceId);
                return ResponseEntity.notFound().build();
            }

            List<CommandStatus> history = commandService.getCommandHistory(deviceId, page, size);
            return ResponseEntity.ok(history);

        } catch (Exception e) {
            log.error("History retrieval failed for device: {}", deviceId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get pending commands for device
     */
    @GetMapping("/device/{deviceId}/pending")
    @Operation(summary = "Get pending commands", description = "Get count of pending commands for device")
    public ResponseEntity<PendingCommandsInfo> getPendingCommands(
            @PathVariable String deviceId) {

        try {
            long pendingCount = commandService.getPendingCommandsCount(deviceId);
            boolean hasPending = commandService.hasPendingCommands(deviceId);

            var info = new PendingCommandsInfo(deviceId, pendingCount, hasPending, Instant.now());
            return ResponseEntity.ok(info);

        } catch (Exception e) {
            log.error("Failed to get pending commands for device: {}", deviceId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Cancel command
     */
    @DeleteMapping("/{commandId}")
    @Operation(summary = "Cancel command", description = "Cancel a pending command")
    public ResponseEntity<CancelCommandResponse> cancelCommand(
            @PathVariable String commandId) {

        try {
            boolean cancelled = commandService.cancelCommand(commandId);

            var response = new CancelCommandResponse(
                    commandId,
                    cancelled,
                    cancelled ? "Command cancelled successfully" : "Command not found or already processed",
                    Instant.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to cancel command: {}", commandId, e);
            return ResponseEntity.internalServerError()
                    .body(new CancelCommandResponse(commandId, false, "Internal error", Instant.now()));
        }
    }

    // ===== RESPONSE DTOs =====

    public record PendingCommandsInfo(
            String deviceId,
            long pendingCount,
            boolean hasPending,
            Instant timestamp) {
    }

    public record CancelCommandResponse(
            String commandId,
            boolean success,
            String message,
            Instant timestamp) {
    }
}