package com.wheelseye.devicegateway.model;

import java.util.regex.Pattern;

/**
 * Immutable IMEI value object with validation.
 */
public record IMEI(String value) {

    private static final Pattern IMEI_PATTERN = Pattern.compile("\\d{14,15}");

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

    /**
     * Static factory for clearer instantiation and future extensions.
     */
    public static IMEI of(String value) {
        return new IMEI(value);
    }

    /**
     * Optional: validate 15-digit IMEI checksum via Luhn algorithm.
     */
    public boolean hasValidChecksum() {
        if (value.length() != 15) return true;
        int sum = 0;
        boolean dbl = false;
        for (int i = value.length() - 2; i >= 0; i--) {
            int d = Character.getNumericValue(value.charAt(i));
            if (dbl) {
                d *= 2;
                if (d > 9) d = d - 9;
            }
            sum += d;
            dbl = !dbl;
        }
        int check = Character.getNumericValue(value.charAt(14));
        return (10 - (sum % 10)) % 10 == check;
    }
}
