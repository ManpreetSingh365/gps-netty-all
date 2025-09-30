package com.aman.location.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "location.kafka.topics")
public record KafkaTopicsProperties(String bootstrapServers,
        String consumerGroupId,
        String deviceLocation,
        String deviceInfo) {

    public KafkaTopicsProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank())
            bootstrapServers = "localhost:9092";

        if (consumerGroupId == null || consumerGroupId.isBlank()) {
            consumerGroupId = "device-gateway-group";
        }

        if (deviceLocation == null || deviceLocation.isBlank())
            deviceLocation = "device.location";

        if (deviceInfo == null || deviceInfo.isBlank())
            deviceInfo = "device.info";
    }
}