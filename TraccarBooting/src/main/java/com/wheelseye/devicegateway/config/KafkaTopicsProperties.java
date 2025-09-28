package com.wheelseye.devicegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "device-gateway.kafka.topics")
public record KafkaTopicsProperties(String deviceLocation, String deviceInfo) {

    public KafkaTopicsProperties {
        if (deviceLocation == null || deviceLocation.isBlank())
            deviceLocation = "device.location";

        if (deviceInfo == null || deviceInfo.isBlank())
            deviceInfo = "device.info";
    }
}
