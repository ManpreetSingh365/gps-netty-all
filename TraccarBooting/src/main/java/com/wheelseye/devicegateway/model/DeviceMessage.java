package com.wheelseye.devicegateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Device Message Model - Concise Lombok Implementation
 * 
 * Immutable data class for GPS device messages.
 * Uses Lombok for minimal, clean code.
 */
@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true)
public class DeviceMessage {

    @NotBlank String imei;
    @NotBlank @Builder.Default String protocol = "GT06";
    @NotBlank String type;
    @NotNull @Builder.Default Instant timestamp = Instant.now();
    // @Singular 
    Map<String, Object> data;
    String rawData;

    // Type-safe data accessors
    public <T> Optional<T> getData(String key, Class<T> type) {
        return Optional.ofNullable(data.get(key))
                .filter(type::isInstance)
                .map(type::cast);
    }

    // GPS data accessors
    public Optional<Double> latitude() { return getData("latitude", Double.class); }
    public Optional<Double> longitude() { return getData("longitude", Double.class); }
    public Optional<Integer> speed() { return getData("speed", Integer.class); }
    public Optional<Integer> course() { return getData("course", Integer.class); }
    public Optional<Integer> satelliteCount() { return getData("satelliteCount", Integer.class); }
    public Optional<Boolean> gpsPositioned() { return getData("gpsPositioned", Boolean.class); }
    public Optional<Boolean> gpsValid() { return getData("gpsValid", Boolean.class); }

    // Status data accessors  
    public Optional<Integer> voltageLevel() { return getData("voltageLevel", Integer.class); }
    public Optional<Integer> gsmSignal() { return getData("gsmSignalStrength", Integer.class); }
    public Optional<Boolean> charging() { return getData("charging", Boolean.class); }
    public Optional<Integer> alarmStatus() { return getData("alarmStatus", Integer.class); }

    // Validation methods
    public boolean isValid() {
        return imei != null && !imei.isBlank() && protocol != null && type != null && timestamp != null;
    }

    public boolean hasLocation() {
        return latitude().isPresent() && longitude().isPresent();
    }

    public boolean isLogin() { return "login".equalsIgnoreCase(type); }
    public boolean isAlarm() { return "alarm".equalsIgnoreCase(type); }
    public boolean isHeartbeat() { return "heartbeat".equalsIgnoreCase(type); }
}
