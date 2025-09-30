package com.aman.location.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.FluxSink;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import com.aman.location.config.KafkaTopicsProperties;
import com.aman.location.dto.LocationDto;
import com.aman.location.mapper.LocationMapper;
import com.aman.location.protobuf.Location;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final KafkaTopicsProperties topics;

    // Keep a list of active reactive subscribers
    private final List<FluxSink<LocationDto>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(FluxSink<LocationDto> sink) {
        subscribers.add(sink);
        sink.onCancel(() -> subscribers.remove(sink));
    }

    // Use property placeholder syntax for topic names
    @KafkaListener(topics = "${location.kafka.topics.deviceLocation}", groupId = "device-location-group")
    public void consumeLocation(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            processLocationMessage(record);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("❌ Failed to process location message: key={}", record.key(), e);
            // Depending on your error handling strategy:
            // - ack.acknowledge(); // Skip problematic message
            // - throw e; // Retry via Spring Kafka retry mechanism
            ack.acknowledge(); // Skip for now
        }
    }

    @KafkaListener(topics = "${location.kafka.topics.deviceInfo}", groupId = "device-info-group")
    public void consumeDeviceInfo(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            processDeviceInfoMessage(record);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("❌ Failed to process device info message: key={}", record.key(), e);
            ack.acknowledge(); // Skip for now
        }
    }

    private void processLocationMessage(ConsumerRecord<String, byte[]> record) {
        String key = record.key();
        byte[] payload = record.value();
        
        log.debug("📥 Processing location event: key={}, partition={}, offset={}, payloadSize={}", key, record.partition(), record.offset(), payload != null ? payload.length : 0);

        if (payload == null || payload.length == 0) {
            log.warn("⚠️ Empty location payload for key={}", key);
            return;
        }

        try {
            // Parse protobuf to internal DTO
            Location protoLocation = Location.parseFrom(payload);
            LocationDto locationDto = LocationMapper.fromProto(protoLocation);
            
            // Push to all reactive subscribers
            subscribers.forEach(sink -> {
                try {
                    sink.next(locationDto);
                } catch (Exception e) {
                    log.warn("⚠️ Failed to push location to subscriber", e);
                }
            });
            
            log.info("✅ Location processed: key={}, deviceId={}, lat={}, lng={}", 
                key, locationDto.imei(), locationDto.latitude(), locationDto.longitude());
                
        } catch (Exception e) {
            log.error("❌ Failed to parse location protobuf for key={}", key, e);
            // throw e;
        }
    }

    private void processDeviceInfoMessage(ConsumerRecord<String, byte[]> record) {
        String key = record.key();
        byte[] payload = record.value();
        
        log.debug("📥 Processing device info event: key={}, payloadSize={}", 
            key, payload != null ? payload.length : 0);
            
        // TODO: Implement device info processing logic
        log.info("ℹ️ Device info event received for key={}", key);
    }
}