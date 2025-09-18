package com.wheelseye.devicegateway.model;

import java.util.regex.Pattern;

/**
 * Immutable IMEI value object with validation.
 */
public record IMEI(String value) {
    private static final Pattern IMEI_PATTERN = Pattern.compile("\\d{15}");

    public IMEI {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IMEI cannot be null or empty");
        }
        String trimmed = value.trim();
        if (!IMEI_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid IMEI format: " + trimmed);
        }
        value = trimmed;
    }
}
