package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * IMEI Value Object - Concise Lombok Implementation
 * 
 * Immutable IMEI representation with validation.
 * Uses Lombok for clean, minimal code.
 */
@Getter
@EqualsAndHashCode
@ToString
@Accessors(fluent = true)
public final class IMEI {

    private static final String UNKNOWN_IMEI = "000000000000000";

    private final String value;

    private IMEI(String value) {
        this.value = Objects.requireNonNull(value, "IMEI cannot be null");
    }

    public static IMEI of(String imei) {
        var cleaned = Objects.requireNonNullElse(imei, "").trim();
        if (!isValid(cleaned)) {
            throw new IllegalArgumentException("Invalid IMEI: " + cleaned);
        }
        return new IMEI(cleaned);
    }

    public static IMEI of(String imei, boolean validate) {
        return validate ? of(imei) : new IMEI(Objects.requireNonNullElse(imei, UNKNOWN_IMEI));
    }

    public static IMEI unknown() {
        return new IMEI(UNKNOWN_IMEI);
    }

    public static boolean isValid(String imei) {
        return imei != null && 
               imei.matches("\\d{15}") && 
               !UNKNOWN_IMEI.equals(imei);
    }

    @JsonValue
    public String value() {
        return value;
    }

    public String masked() {
        return value.length() < 4 ? "****" : 
               "*".repeat(11) + value.substring(11);
    }

    public boolean isUnknown() {
        return UNKNOWN_IMEI.equals(value);
    }

    @JsonCreator
    public static IMEI fromJson(String value) {
        return of(value, false);
    }
}
