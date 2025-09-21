package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.Objects;

/**
 * IMEI Value Object - Immutable and validated
 * 
 * Represents a 15-digit IMEI (International Mobile Equipment Identity) number.
 * Provides validation and factory methods for creating IMEI instances.
 * 
 * @author WheelsEye Development Team
 * @since 1.0.0
 */
@EqualsAndHashCode
@ToString
public final class IMEI implements Serializable {

    private static final long serialVersionUID = 1L;

    // IMEI pattern: 15 digits
    private static final String IMEI_PATTERN = "^\\d{15}$";
    private static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile(IMEI_PATTERN);

    @NotNull
    @Pattern(regexp = IMEI_PATTERN, message = "IMEI must be exactly 15 digits")
    private final String value;

    /**
     * Private constructor to enforce factory method usage
     */
    private IMEI(String value) {
        this.value = Objects.requireNonNull(value, "IMEI value cannot be null");
    }

    /**
     * Factory method to create IMEI with validation
     * 
     * @param value the IMEI string value
     * @param validate whether to perform validation
     * @return validated IMEI instance
     * @throws IllegalArgumentException if IMEI is invalid and validation is enabled
     */
    public static IMEI of(String value, boolean validate) {
        Objects.requireNonNull(value, "IMEI value cannot be null");

        String cleanValue = value.trim();

        if (validate && !isValid(cleanValue)) {
            throw new IllegalArgumentException(
                String.format("Invalid IMEI format: '%s'. Must be exactly 15 digits.", cleanValue)
            );
        }

        return new IMEI(cleanValue);
    }

    /**
     * Factory method to create IMEI with validation enabled by default
     */
    public static IMEI of(String value) {
        return of(value, true);
    }

    /**
     * Jackson JSON creator for deserialization
     */
    @JsonCreator
    public static IMEI fromJson(String value) {
        return of(value, true);
    }

    /**
     * Validate IMEI format
     */
    public static boolean isValid(String imei) {
        return imei != null && PATTERN.matcher(imei.trim()).matches();
    }

    /**
     * Get the IMEI value
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * Get masked IMEI for logging (shows only last 4 digits)
     */
    public String masked() {
        if (value == null || value.length() < 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    /**
     * Get IMEI with custom masking
     */
    public String masked(int visibleDigits) {
        if (value == null || visibleDigits <= 0) {
            return "*".repeat(value != null ? value.length() : 15);
        }
        if (visibleDigits >= value.length()) {
            return value;
        }

        int maskLength = value.length() - visibleDigits;
        return "*".repeat(maskLength) + value.substring(maskLength);
    }

    /**
     * Check if IMEI belongs to a specific manufacturer TAC range
     */
    public String getTAC() {
        return value != null && value.length() >= 8 ? value.substring(0, 8) : null;
    }

    /**
     * Get serial number portion of IMEI
     */
    public String getSerialNumber() {
        return value != null && value.length() >= 14 ? value.substring(8, 14) : null;
    }

    /**
     * Get check digit (Luhn digit)
     */
    public String getCheckDigit() {
        return value != null && value.length() == 15 ? value.substring(14) : null;
    }

    /**
     * Validate check digit using Luhn algorithm
     */
    public boolean isCheckDigitValid() {
        if (value == null || value.length() != 15) {
            return false;
        }

        try {
            int sum = 0;
            boolean alternate = false;

            // Process first 14 digits (excluding check digit)
            for (int i = 13; i >= 0; i--) {
                int digit = Character.getNumericValue(value.charAt(i));

                if (alternate) {
                    digit *= 2;
                    if (digit > 9) {
                        digit = (digit % 10) + 1;
                    }
                }

                sum += digit;
                alternate = !alternate;
            }

            int checkDigit = Character.getNumericValue(value.charAt(14));
            int calculatedCheckDigit = (10 - (sum % 10)) % 10;

            return checkDigit == calculatedCheckDigit;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        IMEI imei = (IMEI) obj;
        return Objects.equals(value, imei.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return String.format("IMEI{value='%s'}", masked());
    }
}