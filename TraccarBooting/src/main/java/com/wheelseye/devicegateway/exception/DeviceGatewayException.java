package com.wheelseye.devicegateway.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 🚨 Custom GPS Device Gateway Exception
 * 
 * 🎯 Provides structured error handling with HTTP status codes
 * 🛡️ Production-ready exception design with SOLID principles
 * 🔧 Supports error chaining and detailed error information
 * 
 * @author GPS Device Gateway Team
 * @version 2.1.0
 * @since Java 21
 */
@Getter
public class DeviceGatewayException extends RuntimeException {
    
    private final String errorCode;
    private final String details;
    private final HttpStatus httpStatus;
    
    // 🏗️ Core Constructors
    
    public DeviceGatewayException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
        this.httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    
    public DeviceGatewayException(String message, String errorCode, String details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
        this.httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    
    public DeviceGatewayException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
        this.httpStatus = httpStatus;
    }
    
    public DeviceGatewayException(String message, String errorCode, String details, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
        this.httpStatus = httpStatus;
    }
    
    public DeviceGatewayException(String message, Throwable cause, String errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = cause.getMessage();
        this.httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    
    public DeviceGatewayException(String message, Throwable cause, String errorCode, HttpStatus httpStatus) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = cause.getMessage();
        this.httpStatus = httpStatus;
    }
    
    // 🏭 Static Factory Methods for Common Errors
    
    public static DeviceGatewayException notFound(String message) {
        return new DeviceGatewayException(message, "NOT_FOUND", HttpStatus.NOT_FOUND);
    }
    
    public static DeviceGatewayException badRequest(String message) {
        return new DeviceGatewayException(message, "BAD_REQUEST", HttpStatus.BAD_REQUEST);
    }
    
    public static DeviceGatewayException unauthorized(String message) {
        return new DeviceGatewayException(message, "UNAUTHORIZED", HttpStatus.UNAUTHORIZED);
    }
    
    public static DeviceGatewayException forbidden(String message) {
        return new DeviceGatewayException(message, "FORBIDDEN", HttpStatus.FORBIDDEN);
    }
    
    public static DeviceGatewayException conflict(String message) {
        return new DeviceGatewayException(message, "CONFLICT", HttpStatus.CONFLICT);
    }
    
    public static DeviceGatewayException internalError(String message) {
        return new DeviceGatewayException(message, "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
    }
    
    public static DeviceGatewayException serviceUnavailable(String message) {
        return new DeviceGatewayException(message, "SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }
    
    // 🔧 Specific Device Gateway Errors
    
    public static DeviceGatewayException deviceNotFound(String imei) {
        return new DeviceGatewayException(
            "Device not found with IMEI: " + imei, 
            "DEVICE_NOT_FOUND", 
            "The specified device does not exist or is not accessible",
            HttpStatus.NOT_FOUND
        );
    }
    
    public static DeviceGatewayException sessionNotFound(String sessionId) {
        return new DeviceGatewayException(
            "Session not found: " + sessionId, 
            "SESSION_NOT_FOUND", 
            "The specified session does not exist or has expired",
            HttpStatus.NOT_FOUND
        );
    }
    
    public static DeviceGatewayException invalidImei(String imei) {
        return new DeviceGatewayException(
            "Invalid IMEI format: " + imei, 
            "INVALID_IMEI", 
            "IMEI must be exactly 15 digits",
            HttpStatus.BAD_REQUEST
        );
    }
    
    public static DeviceGatewayException serializationError(String details) {
        return new DeviceGatewayException(
            "Data serialization error", 
            "SERIALIZATION_ERROR", 
            details,
            HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
    
    public static DeviceGatewayException protocolError(String protocol, String details) {
        return new DeviceGatewayException(
            "Protocol error for " + protocol, 
            "PROTOCOL_ERROR", 
            details,
            HttpStatus.BAD_REQUEST
        );
    }
    
    public static DeviceGatewayException connectionError(String details) {
        return new DeviceGatewayException(
            "Device connection error", 
            "CONNECTION_ERROR", 
            details,
            HttpStatus.SERVICE_UNAVAILABLE
        );
    }
    
    // 🔍 Utility Methods
    
    /**
     * 📝 Returns a formatted error message for logging
     */
    public String getFormattedMessage() {
        return String.format("[%s] %s - %s", errorCode, getMessage(), details != null ? details : "");
    }
    
    /**
     * 🔍 Checks if this is a client error (4xx)
     */
    public boolean isClientError() {
        return httpStatus.is4xxClientError();
    }
    
    /**
     * 🔍 Checks if this is a server error (5xx)  
     */
    public boolean isServerError() {
        return httpStatus.is5xxServerError();
    }
    
    /**
     * 🎯 Gets the HTTP status code as integer
     */
    public int getStatusCode() {
        return httpStatus.value();
    }
    
    /**
     * 📋 Gets the HTTP status reason phrase
     */
    public String getStatusReason() {
        return httpStatus.getReasonPhrase();
    }
}