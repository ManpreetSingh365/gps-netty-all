package com.wheelseye.devicegateway.controller;

import com.wheelseye.devicegateway.dto.DeviceSessionDto;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.service.DeviceSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete Device Controller - Production-Ready Implementation
 * 
 * Modern REST API controller with:
 * - All APIs fully functional and tested
 * - Fixed method calls to DeviceSessionService
 * - Complete error handling and validation
 * - OpenAPI 3 documentation
 * - Java 21 features and Spring Boot 3.5.5
 * - Production-ready logging and caching
 * 
 * @author WheelsEye Development Team
 * @version 2.1.0 - Complete Implementation
 */
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Device Management", description = "Complete device session management and monitoring APIs")
@Validated
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    private final DeviceSessionService deviceSessionService;
    private final ModelMapper modelMapper;

    public DeviceController(DeviceSessionService deviceSessionService, ModelMapper modelMapper) {
        this.deviceSessionService = deviceSessionService;
        this.modelMapper = modelMapper;
    }

    // === SESSION MANAGEMENT APIS ===

    /**
     * Get all active device sessions
     */
    @GetMapping("/sessions")
    @Operation(summary = "Get all active device sessions", description = "Retrieves all currently active device sessions")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Successfully retrieved sessions"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Cacheable(value = "device-sessions", unless = "#result.body.isEmpty()")
    public ResponseEntity<List<DeviceSessionDto>> getAllSessions() {
        try {
            List<DeviceSession> sessions = deviceSessionService.getAllSessions();
            List<DeviceSessionDto> sessionDtos = sessions.stream()
                    .map(session -> modelMapper.map(session, DeviceSessionDto.class))
                    .toList(); // Java 16+ feature

            logger.debug("Retrieved {} active sessions", sessions.size());
            return ResponseEntity.ok(sessionDtos);

        } catch (Exception e) {
            logger.error("Failed to retrieve sessions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get session by IMEI
     */
    @GetMapping("/{imei}/session")
    @Operation(summary = "Get session by IMEI", description = "Retrieves device session for specific IMEI")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session found"),
        @ApiResponse(responseCode = "404", description = "Session not found"),
        @ApiResponse(responseCode = "400", description = "Invalid IMEI format")
    })
    public ResponseEntity<DeviceSessionDto> getSessionByImei(
            @Parameter(description = "Device IMEI (15 digits)", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{15}", message = "IMEI must be exactly 15 digits")
            String imei) {
        try {
            Optional<DeviceSession> session = deviceSessionService.getSessionByImei(imei);

            return session
                    .map(s -> modelMapper.map(s, DeviceSessionDto.class))
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid IMEI format: {}", imei);
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error retrieving session for IMEI: {}", imei, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Disconnect device session (FIXED)
     */
    @DeleteMapping("/{imei}/session")
    @Operation(summary = "Disconnect device session", description = "Forcefully disconnect a device session")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session disconnected successfully"),
        @ApiResponse(responseCode = "404", description = "Session not found"),
        @ApiResponse(responseCode = "400", description = "Invalid IMEI format")
    })
    public ResponseEntity<DisconnectResponse> disconnectDevice(
            @Parameter(description = "Device IMEI (15 digits)", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{15}", message = "IMEI must be exactly 15 digits")
            String imei) {
        try {
            // Using the correct method name from DeviceSessionService
            boolean disconnected = deviceSessionService.disconnectDevice(imei);

            if (disconnected) {
                var response = new DisconnectResponse(
                        true,
                        "Device disconnected successfully",
                        imei,
                        Instant.now()
                );
                logger.info("✅ Disconnected device: {}", imei);
                return ResponseEntity.ok(response);
            } else {
                var response = new DisconnectResponse(
                        false,
                        "Device session not found",
                        imei,
                        Instant.now()
                );
                logger.warn("⚠️ Session not found for disconnect: {}", imei);
                return ResponseEntity.status(404).body(response);
            }

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid IMEI format: {}", imei);
            var response = new DisconnectResponse(
                    false,
                    "Invalid IMEI format",
                    imei,
                    Instant.now()
            );
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error disconnecting device: {}", imei, e);
            var response = new DisconnectResponse(
                    false,
                    "Internal server error",
                    imei,
                    Instant.now()
            );
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // === MONITORING AND STATISTICS APIS ===

    /**
     * Get service health status
     */
    @GetMapping("/health")
    @Operation(summary = "Get service health", description = "Returns service health status and metrics")
    public ResponseEntity<ServiceHealth> getHealth() {
        try {
            var stats = deviceSessionService.getSessionStats();
            var health = new ServiceHealth(
                    "UP",
                    stats.getTotalSessions(),
                    stats.getAuthenticatedSessions(),
                    stats.getUnauthenticatedSessions(),
                    Instant.now()
            );
            return ResponseEntity.ok(health);
        } catch (Exception e) {
            logger.error("Health check failed", e);
            var errorHealth = new ServiceHealth(
                    "DOWN",
                    0,
                    0,
                    0,
                    Instant.now()
            );
            return ResponseEntity.internalServerError().body(errorHealth);
        }
    }

    /**
     * Get detailed session statistics
     */
    @GetMapping("/stats")
    @Operation(summary = "Get detailed session statistics", description = "Returns comprehensive session statistics")
    @Cacheable(value = "session-stats", unless = "#result.body.totalSessions() == 0")
    public ResponseEntity<SessionStatistics> getSessionStatistics() {
        try {
            var stats = deviceSessionService.getSessionStats();
            var sessions = deviceSessionService.getAllSessions();

            // Calculate protocol breakdown
            var protocolBreakdown = sessions.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            s -> s.getProtocolVersion() != null ? s.getProtocolVersion() : "unknown",
                            java.util.stream.Collectors.counting()
                    ));

            var statistics = new SessionStatistics(
                    stats.getTotalSessions(),
                    stats.getAuthenticatedSessions(),
                    stats.getUnauthenticatedSessions(),
                    protocolBreakdown,
                    Instant.now()
            );

            return ResponseEntity.ok(statistics);

        } catch (Exception e) {
            logger.error("Failed to get session statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get session count
     */
    @GetMapping("/sessions/count")
    @Operation(summary = "Get session count", description = "Returns the total number of active sessions")
    public ResponseEntity<SessionCount> getSessionCount() {
        try {
            long count = deviceSessionService.getSessionCount();
            var sessionCount = new SessionCount(count, Instant.now());
            return ResponseEntity.ok(sessionCount);
        } catch (Exception e) {
            logger.error("Failed to get session count", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Check if device is active
     */
    @GetMapping("/{imei}/active")
    @Operation(summary = "Check device active status", description = "Check if a device session is currently active")
    public ResponseEntity<DeviceStatus> isDeviceActive(
            @Parameter(description = "Device IMEI (15 digits)", required = true)
            @PathVariable
            @Pattern(regexp = "\\d{15}", message = "IMEI must be exactly 15 digits")
            String imei) {
        try {
            boolean active = deviceSessionService.isSessionActive(imei);
            var status = new DeviceStatus(imei, active, Instant.now());
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error checking device active status for IMEI: {}", imei, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // === MAINTENANCE APIS ===

    /**
     * Trigger manual session cleanup
     */
    @PostMapping("/sessions/cleanup")
    @Operation(summary = "Trigger manual cleanup", description = "Manually trigger cleanup of old sessions")
    public ResponseEntity<CleanupResult> triggerCleanup() {
        try {
            int cleaned = deviceSessionService.cleanupOldSessions();
            var result = new CleanupResult(
                    true,
                    cleaned,
                    "Manual cleanup completed successfully",
                    Instant.now()
            );
            logger.info("✅ Manual cleanup completed: {} sessions removed", cleaned);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Failed to perform manual cleanup", e);
            var result = new CleanupResult(
                    false,
                    0,
                    "Cleanup failed: " + e.getMessage(),
                    Instant.now()
            );
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // === RECORDS (DTOs) ===

    /**
     * Service health status
     */
    public record ServiceHealth(
            String status,
            int activeSessions,
            int authenticatedSessions,
            int unauthenticatedSessions,
            Instant timestamp
    ) {}

    /**
     * Session statistics
     */
    public record SessionStatistics(
            int totalSessions,
            int authenticatedSessions,
            int unauthenticatedSessions,
            Map<String, Long> protocolBreakdown,
            Instant timestamp
    ) {}

    /**
     * Disconnect response
     */
    public record DisconnectResponse(
            boolean success,
            String message,
            String imei,
            Instant timestamp
    ) {}

    /**
     * Session count
     */
    public record SessionCount(
            long count,
            Instant timestamp
    ) {}

    /**
     * Device status
     */
    public record DeviceStatus(
            String imei,
            boolean active,
            Instant timestamp
    ) {}

    /**
     * Cleanup result
     */
    public record CleanupResult(
            boolean success,
            int cleanedCount,
            String message,
            Instant timestamp
    ) {}
}