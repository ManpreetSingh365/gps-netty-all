package com.aman.location.consumer;

import com.aman.location.entity.Location;
import com.aman.location.mapper.LocationMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class LocationConsumer {

    // Keep a list of active subscribers
    private final List<FluxSink<Location>> subscribers = new CopyOnWriteArrayList<>();

    public void subscribe(FluxSink<Location> sink) {
        subscribers.add(sink);
        sink.onCancel(() -> subscribers.remove(sink));
    }

    @KafkaListener(topics = "${KAFKA_TOPIC:location.device}", groupId = "${KAFKA_CONSUMER_GROUP_ID}")
    public void consume(ConsumerRecord<String, byte[]> record) {
        try {
            byte[] data = record.value();

            // Parse protobuf bytes
            com.aman.location.protobuf.Location protoLocation = com.aman.location.protobuf.Location.parseFrom(data);

            // Convert protobuf -> internal Location using mapper
            Location location = LocationMapper.fromProto(protoLocation);
            System.out.println("Received location: " + location);
            // Push to all active subscribers
            subscribers.forEach(sink -> sink.next(location));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
