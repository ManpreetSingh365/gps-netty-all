package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * IMEI (International Mobile Equipment Identity) Value Object
 * 
 * Represents a device IMEI with validation according to the IMEI specification.
 * This is an immutable value object following DDD principles.
 * 
 * Java 21 Features Used:
 * - Record-style immutability
 * - Pattern matching for validation
 * - Modern exception handling
 * 
 * @since Spring Boot 3.5.5
 */
public final class IMEI {
    
    private static final Pattern IMEI_PATTERN = Pattern.compile("^\\d{15}$");
    private static final String UNKNOWN_IMEI = "000000000000000";
    
    private final String value;
    
    private IMEI(String value) {
        this.value = Objects.requireNonNull(value, "IMEI value cannot be null");
    }
    
    /**
     * Create IMEI from string value with validation
     * 
     * @param imeiValue the IMEI string (must be exactly 15 digits)
     * @return validated IMEI instance
     * @throws IllegalArgumentException if IMEI is invalid
     */
    public static IMEI of(String imeiValue) {
        if (imeiValue == null || imeiValue.trim().isEmpty()) {
            throw new IllegalArgumentException("IMEI cannot be null or empty");
        }
        
        String cleanImei = imeiValue.trim();
        
        // Validate IMEI format
        if (!isValid(cleanImei)) {
            throw new IllegalArgumentException(
                String.format("Invalid IMEI format: '%s'. IMEI must be exactly 15 digits", cleanImei)
            );
        }
        
        return new IMEI(cleanImei);
    }
    
    /**
     * Create IMEI with optional validation (for system use)
     * 
     * @param imeiValue the IMEI string
     * @param validate whether to validate the format
     * @return IMEI instance
     */
    public static IMEI of(String imeiValue, boolean validate) {
        if (!validate) {
            return new IMEI(Objects.requireNonNullElse(imeiValue, UNKNOWN_IMEI));
        }
        return of(imeiValue);
    }
    
    /**
     * Create IMEI for unknown devices
     * 
     * @return IMEI instance for unknown device
     */
    public static IMEI unknown() {
        return new IMEI(UNKNOWN_IMEI);
    }
    
    /**
     * Validate IMEI format
     * 
     * @param imei IMEI string to validate
     * @return true if valid IMEI format
     */
    public static boolean isValid(String imei) {
        if (imei == null || imei.trim().isEmpty()) {
            return false;
        }
        
        String cleanImei = imei.trim();
        
        // Check basic format (15 digits)
        if (!IMEI_PATTERN.matcher(cleanImei).matches()) {
            return false;
        }
        
        // Check not all zeros (invalid IMEI)
        if (UNKNOWN_IMEI.equals(cleanImei)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Get the IMEI value
     * 
     * @return IMEI string value
     */
    @JsonValue
    public String value() {
        return value;
    }
    
    /**
     * Check if this is an unknown IMEI
     * 
     * @return true if this represents an unknown device
     */
    public boolean isUnknown() {
        return UNKNOWN_IMEI.equals(value);
    }
    
    /**
     * Get masked IMEI for logging (shows only last 4 digits)
     * 
     * @return masked IMEI string
     */
    public String masked() {
        if (value.length() < 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
    
    @Override
    public boolean equals(Object obj) {
        return obj instanceof IMEI other && Objects.equals(this.value, other.value);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
    
    @Override
    public String toString() {
        return value;
    }
    
    /**
     * JSON deserialization support
     */
    @JsonCreator
    public static IMEI fromJson(String value) {
        return of(value, false); // Don't validate during deserialization
    }
}