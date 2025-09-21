package com.wheelseye.devicegateway.controller;

import com.wheelseye.devicegateway.dto.DeviceSessionDto;
import com.wheelseye.devicegateway.model.DeviceSession;
import com.wheelseye.devicegateway.model.IMEI;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Modern Device Controller using Java 21 features and Spring Boot 3.5.5 best practices.
 * 
 * Features:
 * - Java 21 text blocks, pattern matching, records
 * - Spring Boot 3.5.5 with native compilation support
 * - OpenAPI 3 documentation
 * - Validation with Jakarta Bean Validation
 * - Caching with Spring Cache abstraction
 * - Structured logging with correlation IDs
 * - Reactive-style error handling
 */
@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Device Management", description = "Device session management and monitoring APIs")
@Validated
public class DeviceController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceController.class);

    private final DeviceSessionService deviceSessionService;
    private final ModelMapper modelMapper;

    public DeviceController(DeviceSessionService deviceSessionService, ModelMapper modelMapper) {
        this.deviceSessionService = deviceSessionService;
        this.modelMapper = modelMapper;
    }

    @GetMapping("/sessions")
    @Operation(summary = "Get all active device sessions", description = "Retrieves all currently active device sessions")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Successfully retrieved sessions"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Cacheable(value = "device-sessions", unless = "#result.isEmpty()")
    public ResponseEntity<List<DeviceSessionDto>> getAllSessions() {
        try {
            Collection<DeviceSession> sessions = deviceSessionService.getAllSessions();
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
            IMEI deviceImei = IMEI.of(imei);
            Optional<DeviceSession> session = deviceSessionService.getSession(deviceImei);

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

    @GetMapping("/health")
    @Operation(summary = "Get service health", description = "Returns service health status and metrics")
    public ResponseEntity<ServiceHealth> getHealth() {
        try {
            var stats = deviceSessionService.getSessionStats();
            var health = new ServiceHealth(
                "UP",
                stats.totalSessions(),
                stats.authenticatedSessions(),
                stats.unauthenticatedSessions(),
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

    // @GetMapping("/stats")
    // @Operation(summary = "Get detailed session statistics")
    // @Cacheable(value = "session-stats", unless = "#result.totalSessions == 0")
    // public ResponseEntity<SessionStatistics> getSessionStatistics() {
    //     try {
    //         var stats = deviceSessionService.getSessionStats();
    //         var sessions = deviceSessionService.getAllSessions();
            
    //         var protocolBreakdown = sessions.stream()
    //             .collect(java.util.stream.Collectors.groupingBy(
    //                 s -> s.getAttributes().getOrDefault("protocol", "unknown").toString(),
    //                 java.util.stream.Collectors.counting()
    //             ));

    //         var statistics = new SessionStatistics(
    //             stats.totalSessions(),
    //             stats.authenticatedSessions(),
    //             stats.unauthenticatedSessions(),
    //             protocolBreakdown,
    //             Instant.now()
    //         );

    //         return ResponseEntity.ok(statistics);

    //     } catch (Exception e) {
    //         logger.error("Failed to get session statistics", e);
    //         return ResponseEntity.internalServerError().build();
    //     }
    // }

    @GetMapping("/appinfo")
    @Operation(summary = "Get service information", description = "Returns basic service information and version")
    public ResponseEntity<ServiceInfo> getRoot() {
        var info = new ServiceInfo(
            "Device Gateway Service is running!",
            "2.0.0",
            "Java 21 + Spring Boot 3.5.5",
            Instant.now()
        );
        return ResponseEntity.ok(info);
    }

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
            IMEI deviceImei = IMEI.of(imei);
            boolean disconnected = deviceSessionService.disconnectDevice(deviceImei);
            
            if (disconnected) {
                var response = new DisconnectResponse(
                    true,
                    "Device disconnected successfully",
                    imei,
                    Instant.now()
                );
                return ResponseEntity.ok(response);
            } else {
                var response = new DisconnectResponse(
                    false,
                    "Device session not found",
                    imei,
                    Instant.now()
                );
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

    // Modern record-based DTOs (Java 14+ feature)
    public record ServiceHealth(
        String status,
        int activeSessions,
        int authenticatedSessions,
        int unauthenticatedSessions,
        Instant timestamp
    ) {}

    public record SessionStatistics(
        int totalSessions,
        int authenticatedSessions,
        int unauthenticatedSessions,
        Map<String, Long> protocolBreakdown,
        Instant timestamp
    ) {}

    public record ServiceInfo(
        String message,
        String version,
        String runtime,
        Instant timestamp
    ) {}

    public record DisconnectResponse(
        boolean success,
        String message,
        String imei,
        Instant timestamp
    ) {}

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        var error = new ErrorResponse(
            "INVALID_REQUEST",
            e.getMessage(),
            Instant.now()
        );
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        logger.error("Unhandled exception in controller", e);
        var error = new ErrorResponse(
            "INTERNAL_ERROR",
            "An internal error occurred",
            Instant.now()
        );
        return ResponseEntity.internalServerError().body(error);
    }

    public record ErrorResponse(
        String code,
        String message,
        Instant timestamp
    ) {}
}