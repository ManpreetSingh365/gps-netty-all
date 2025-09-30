package com.wheelseye.devicegateway.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import com.wheelseye.devicegateway.config.KafkaTopicsProperties;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component 
@RequiredArgsConstructor
public class EventPublisher {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    // Publish raw binary device location payload (e.g., GT06 tracker)
    public void publishLocation(String key, byte[] payload) {
        sendMessage(topics.deviceLocation(), key, payload);
    }

    // Publish raw binary device info payload
    public void publishDeviceInfo(String key, byte[] payload) {
        sendMessage(topics.deviceInfo(), key, payload);
    }

    // Centralized send method with proper logging using CompletableFuture
    private void sendMessage(String topicName, String key, byte[] payload) {
        try {
            // Create the producer record
            ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(topicName, key, payload);

            // Send using CompletableFuture (Spring Kafka 3.x)
            CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(producerRecord);

            // Handle success and failure cases using whenComplete
            future.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    // Handle failure
                    log.error("❌ Failed to publish event to topic={}, key={}", topicName, key, throwable);
                } else {
                    // Handle success
                    log.debug("✅ Published event to topic={}, key={}, partition={}, offset={}",
                            topicName, key,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });

        } catch (Exception e) {
            log.error("❌ Exception while publishing event to topic={}, key={}", topicName, key, e);
        }
    }


    // Optional: Synchronous send method for critical operations
    public SendResult<String, byte[]> sendMessageSync(String topicName, String key, byte[] payload) {
        try {
            ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(topicName, key, payload);
            CompletableFuture<SendResult<String, byte[]>> future = kafkaTemplate.send(producerRecord);

            // Block and wait for result (use with caution in production)
            SendResult<String, byte[]> result = future.get(); // Or future.get(5, TimeUnit.SECONDS) for timeout

            log.debug("✅ Synchronously published to topic={}, key={}, partition={}, offset={}",
                    topicName, key,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

            return result;
        } catch (Exception e) {
            log.error("❌ Failed to synchronously publish to topic={}, key={}", topicName, key, e);
            throw new RuntimeException("Failed to publish message", e);
        }
    }
}
