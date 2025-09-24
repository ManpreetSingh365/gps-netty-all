package com.wheelseye.devicegateway.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * 🌟 Standardized API Response Wrapper
 * 
 * 🎯 Provides consistent response format across all endpoints
 * ⚡ FIXED: Proper Instant serialization to prevent YearOfEra errors
 * 🛡️ Production-ready with validation and error handling
 * 
 * @param <T> Response data type
 * @author GPS Device Gateway Team
 * @version 2.1.0
 * @since Java 21
 */
@Slf4j
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiResponse<T> {

    // 🎯 Core Response Fields
    @JsonProperty("success")
    @NotNull
    @Builder.Default
    private Boolean success = true;

    @JsonProperty("data")
    private T data;

    @JsonProperty("message")
    private String message;

    @JsonProperty("error")
    private ErrorDetails error;

    /**
     * ⚡ CRITICAL FIX: Timestamp with proper Jackson serialization
     */
    @JsonProperty("timestamp")
    @JsonFormat(
        shape = JsonFormat.Shape.STRING, 
        pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", 
        timezone = "UTC"
    )
    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    @Builder.Default
    private Instant timestamp = Instant.now();

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("version")
    @Builder.Default
    private String version = "2.1.0";

    // 🏭 Factory Methods for Success Responses

    /**
     * 🎉 Creates successful response with data
     */
    public static <T> ApiResponse<T> success(T data) {
        log.debug("✅ Creating success response with data type: {}", 
                 data != null ? data.getClass().getSimpleName() : "null");
        
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(Instant.now())
                .requestId(generateRequestId())
                .build();
    }

    /**
     * 🎉 Creates successful response with data and message
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        log.debug("✅ Creating success response: {}", message);
        
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .timestamp(Instant.now())
                .requestId(generateRequestId())
                .build();
    }

    /**
     * 📊 Creates successful response with metadata
     */
    public static <T> ApiResponse<T> successWithMeta(T data, String message, String requestId) {
        log.debug("✅ Creating success response with metadata");
        
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .message(message)
                .requestId(requestId != null ? requestId : generateRequestId())
                .timestamp(Instant.now())
                .build();
    }

    // 🚨 Factory Methods for Error Responses

    /**
     * ❌ Creates error response with message and code
     */
    public static <T> ApiResponse<T> error(String message, String code) {
        log.debug("❌ Creating error response: {} [{}]", message, code);
        
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .error(ErrorDetails.builder()
                        .code(code)
                        .message(message)
                        .timestamp(Instant.now())
                        .build())
                .timestamp(Instant.now())
                .requestId(generateRequestId())
                .build();
    }

    /**
     * ❌ Creates error response with detailed error information
     */
    public static <T> ApiResponse<T> error(ErrorDetails error) {
        log.debug("❌ Creating error response with details: {}", error.getCode());
        
        return ApiResponse.<T>builder()
                .success(false)
                .message(error.getMessage())
                .error(error)
                .timestamp(Instant.now())
                .requestId(generateRequestId())
                .build();
    }

    /**
     * ❌ Creates error response with exception information
     */
    public static <T> ApiResponse<T> error(String message, String code, String details) {
        log.debug("❌ Creating detailed error response: {} [{}]", message, code);
        
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .error(ErrorDetails.builder()
                        .code(code)
                        .message(message)
                        .details(details)
                        .timestamp(Instant.now())
                        .build())
                .timestamp(Instant.now())
                .requestId(generateRequestId())
                .build();
    }

    // 🔧 Utility Methods

    /**
     * 🎲 Generates unique request ID
     */
    private static String generateRequestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 🔍 Checks if this response represents success
     */
    public boolean isSuccessful() {
        return Boolean.TRUE.equals(success);
    }

    /**
     * 🔍 Checks if this response contains data
     */
    public boolean hasData() {
        return data != null;
    }

    /**
     * 🔍 Checks if this response contains error details
     */
    public boolean hasError() {
        return error != null;
    }

    // 🏗️ Nested ErrorDetails Class

    /**
     * 🚨 Error Details Structure
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorDetails {

        @JsonProperty("code")
        private String code;

        @JsonProperty("message")
        private String message;

        @JsonProperty("details")
        private String details;

        @JsonProperty("field")
        private String field;

        @JsonProperty("path")
        private String path;

        /**
         * ⚡ CRITICAL FIX: Error timestamp with proper serialization
         */
        @JsonProperty("timestamp")
        @JsonFormat(
            shape = JsonFormat.Shape.STRING, 
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", 
            timezone = "UTC"
        )
        @JsonSerialize(using = InstantSerializer.class)
        @JsonDeserialize(using = InstantDeserializer.class)
        @Builder.Default
        private Instant timestamp = Instant.now();

        @JsonProperty("trace")
        private String trace;

        // 🏭 Factory Methods for Common Errors

        public static ErrorDetails validationError(String field, String message) {
            return ErrorDetails.builder()
                    .code("VALIDATION_ERROR")
                    .message(message)
                    .field(field)
                    .timestamp(Instant.now())
                    .build();
        }

        public static ErrorDetails notFound(String resource) {
            return ErrorDetails.builder()
                    .code("NOT_FOUND")
                    .message(resource + " not found")
                    .timestamp(Instant.now())
                    .build();
        }

        public static ErrorDetails internalError(String details) {
            return ErrorDetails.builder()
                    .code("INTERNAL_ERROR")
                    .message("An internal error occurred")
                    .details(details)
                    .timestamp(Instant.now())
                    .build();
        }

        public static ErrorDetails serializationError(String details) {
            return ErrorDetails.builder()
                    .code("SERIALIZATION_ERROR")
                    .message("Data serialization error")
                    .details(details)
                    .timestamp(Instant.now())
                    .build();
        }
    }

    // 🔧 Builder Pattern Enhancements

    /**
     * 🎨 Fluent builder method to add request ID
     */
    public ApiResponse<T> withRequestId(String requestId) {
        return this.toBuilder()
                .requestId(requestId)
                .build();
    }

    /**
     * 🎨 Fluent builder method to add message
     */
    public ApiResponse<T> withMessage(String message) {
        return this.toBuilder()
                .message(message)
                .build();
    }

    /**
     * 📝 Creates a log-friendly string representation
     */
    public String toLogString() {
        return String.format("ApiResponse[success=%s, hasData=%s, hasError=%s, requestId=%s]",
                success, hasData(), hasError(), requestId);
    }
}