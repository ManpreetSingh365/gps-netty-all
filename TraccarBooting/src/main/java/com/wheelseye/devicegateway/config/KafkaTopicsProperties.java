package com.wheelseye.devicegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "device-gateway.kafka.topics")
public record KafkaTopicsProperties(
        String bootstrapServers,
        String consumerGroupId,
        String deviceLocation,
        String deviceInfo,
        int deviceLocationPartitions,
        short deviceLocationReplication,
        int deviceInfoPartitions,
        short deviceInfoReplication) {

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

        if (deviceLocationPartitions <= 0)
            deviceLocationPartitions = 50;

        if (deviceLocationReplication <= 0)
            deviceLocationReplication = 3;

        if (deviceInfoPartitions <= 0)
            deviceInfoPartitions = 3;

        if (deviceInfoReplication <= 0)
            deviceInfoReplication = 1;
    }

    public int getLocationPartition(String imei) {
        return Math.abs(imei.hashCode()) % deviceLocationPartitions;
    }

    // public int getDeviceInfoPartition(String imei) {
    // return Math.abs(imei.hashCode()) % deviceInfoPartitions;
    // }
}
