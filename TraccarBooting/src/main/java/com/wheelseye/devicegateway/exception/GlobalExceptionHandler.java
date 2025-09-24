package com.wheelseye.devicegateway.exception;

import com.wheelseye.devicegateway.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import java.time.temporal.UnsupportedTemporalTypeException;

/**
 * 🛡️ Global Exception Handler for GPS Device Gateway Service
 * 
 * ⚡ CRITICAL FIX: Handles YearOfEra serialization errors and INTERNAL_ERROR responses
 * 🎯 Provides consistent error responses across all endpoints
 * 🔧 Modern exception handling following SOLID principles
 * 
 * @author GPS Device Gateway Team
 * @version 2.1.0
 * @since Java 21
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 🚨 CRITICAL: Jackson Serialization Error Handler

    /**
     * ⚡ CRITICAL FIX: Handles Jackson YearOfEra serialization errors
     * 
     * This is the PRIMARY fix for INTERNAL_ERROR responses caused by 
     * "Unsupported field: YearOfEra" when serializing DeviceSessionDto
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ApiResponse<Void>> handleSerializationError(
            HttpMessageNotWritableException ex, 
            HttpServletRequest request) {
        
        String errorDetails = ex.getMessage();
        String message = "Data serialization error";
        
        // 🔍 Detect specific YearOfEra error
        if (errorDetails != null && errorDetails.contains("YearOfEra")) {
            message = "DateTime field serialization error - YearOfEra not supported";
            log.error("🚨 CRITICAL: YearOfEra serialization error at {}: {}", 
                     request.getRequestURI(), errorDetails);
                     
            // Log the full stack trace for debugging
            log.debug("Full YearOfEra error stack trace:", ex);
            
        } else if (errorDetails != null && errorDetails.contains("Unsupported field")) {
            message = "Unsupported datetime field during serialization";
            log.error("🚨 Unsupported field serialization error at {}: {}", 
                     request.getRequestURI(), errorDetails);
                     
        } else if (errorDetails != null && errorDetails.contains("InstantSeconds")) {
            message = "Instant serialization error - unsupported temporal field";
            log.error("🚨 InstantSeconds serialization error at {}: {}", 
                     request.getRequestURI(), errorDetails);
                     
        } else {
            log.error("🚨 Generic serialization error at {}: {}", 
                     request.getRequestURI(), errorDetails);
        }

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code("SERIALIZATION_ERROR")
                .message(message)
                .details("Check datetime field formats and Jackson configuration")
                .path(request.getRequestURI())
                .build()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * ⚡ Handles Java temporal type errors specifically
     */
    @ExceptionHandler(UnsupportedTemporalTypeException.class)
    public ResponseEntity<ApiResponse<Void>> handleTemporalError(
            UnsupportedTemporalTypeException ex,
            HttpServletRequest request) {
        
        log.error("🚨 Temporal type error at {}: {}", request.getRequestURI(), ex.getMessage());
        log.debug("Temporal error details:", ex);

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code("TEMPORAL_TYPE_ERROR")
                .message("Invalid datetime field operation")
                .details("Temporal field '" + ex.getMessage() + "' is not supported for this operation")
                .path(request.getRequestURI())
                .build()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 🔧 Custom Application Exceptions

    /**
     * 🎯 Handles custom DeviceGatewayException
     */
    @ExceptionHandler(DeviceGatewayException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeviceGatewayException(
            DeviceGatewayException ex,
            HttpServletRequest request) {
        
        log.error("🚨 Device gateway error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code(ex.getErrorCode())
                .message(ex.getMessage())
                .details(ex.getDetails())
                .path(request.getRequestURI())
                .trace(ex.getClass().getSimpleName())
                .build()
        );

        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    // 🔍 Validation and Type Errors

    /**
     * 📝 Handles validation errors from @Valid annotations
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationError(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        
        log.warn("📝 Validation error at {}: {}", request.getRequestURI(), ex.getMessage());

        // Extract validation error messages
        String validationMessage = ex.getBindingResult().getAllErrors().stream()
            .map(error -> error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code("VALIDATION_ERROR")
                .message("Request validation failed")
                .details(validationMessage)
                .path(request.getRequestURI())
                .build()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 🔧 Handles method argument type mismatch
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        
        log.warn("🔧 Type mismatch error at {}: {}", request.getRequestURI(), ex.getMessage());

        String expectedType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        
        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code("TYPE_MISMATCH")
                .message("Invalid parameter type")
                .details(String.format("Parameter '%s' should be of type %s", ex.getName(), expectedType))
                .path(request.getRequestURI())
                .field(ex.getName())
                .build()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 🚨 Generic Exception Handlers

    /**
     * 🏃‍♂️ Handles runtime exceptions
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(
            RuntimeException ex,
            HttpServletRequest request) {
        
        log.error("🏃‍♂️ Runtime error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        // Check if it's a serialization-related runtime exception
        String message = "Runtime error occurred";
        String code = "RUNTIME_ERROR";
        
        if (ex.getMessage() != null && 
            (ex.getMessage().contains("YearOfEra") || ex.getMessage().contains("serializ"))) {
            message = "Data serialization runtime error";
            code = "SERIALIZATION_RUNTIME_ERROR";
        }

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code(code)
                .message(message)
                .details(ex.getMessage())
                .path(request.getRequestURI())
                .trace(ex.getClass().getSimpleName())
                .build()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 🌐 Catches all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            Exception ex,
            HttpServletRequest request) {
        
        log.error("🌐 Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        // Special handling for Jackson-related exceptions that might slip through
        String message = "An internal error occurred";
        String code = "INTERNAL_ERROR";
        
        if (ex.getCause() != null && ex.getCause().getMessage() != null) {
            String causeMessage = ex.getCause().getMessage();
            if (causeMessage.contains("YearOfEra") || causeMessage.contains("InstantSeconds")) {
                message = "DateTime serialization system error";
                code = "DATETIME_SYSTEM_ERROR";
                log.error("🚨 CRITICAL: DateTime system error detected in generic handler");
            }
        }

        ApiResponse<Void> response = ApiResponse.error(
            ApiResponse.ErrorDetails.builder()
                .code(code)
                .message(message)
                .details("Please contact system administrator if this persists")
                .path(request.getRequestURI())
                .trace(ex.getClass().getSimpleName())
                .build()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // 🔧 Utility Methods

    /**
     * 📊 Extracts the root cause message from exception chain
     */
    private String extractRootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getMessage();
    }

    /**
     * 🔍 Checks if exception is related to datetime serialization
     */
    private boolean isDateTimeSerializationError(Exception ex) {
        String message = ex.getMessage();
        String rootCause = extractRootCauseMessage(ex);
        
        return (message != null && (message.contains("YearOfEra") || 
                                   message.contains("InstantSeconds") ||
                                   message.contains("temporal"))) ||
               (rootCause != null && (rootCause.contains("YearOfEra") ||
                                     rootCause.contains("InstantSeconds") ||
                                     rootCause.contains("temporal")));
    }
}