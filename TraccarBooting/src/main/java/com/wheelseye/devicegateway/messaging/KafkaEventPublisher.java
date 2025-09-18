package com.wheelseye.devicegateway.messaging;

import com.wheelseye.devicegateway.domain.events.DeviceSessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import com.wheelseye.devicegateway.domain.mappers.DeviceSessionEventMapper;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Value("${device-gateway.kafka.topics.device-sessions}")
    private String deviceSessionsTopic;

    @Value("${device-gateway.kafka.topics.telemetry-inbound}")
    private String telemetryInboundTopic;

    @Value("${device-gateway.kafka.topics.commands-outbound}")
    private String commandsOutboundTopic;

    public KafkaEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishDeviceSessionEvent(DeviceSessionEvent event) {
        try {
            kafkaTemplate.send(deviceSessionsTopic, event.imei().getValue(), DeviceSessionEventMapper.toProto(event).toByteArray())
                    .addCallback(
                            result -> logger.debug("Published session event for IMEI: {}", event.imei().getValue()),
                            failure -> logger.error("Failed to publish session event for IMEI: {}",
                                    event.imei().getValue(), failure));
        } catch (Exception e) {
            logger.error("Error publishing device session event", e);
        }
    }
}
