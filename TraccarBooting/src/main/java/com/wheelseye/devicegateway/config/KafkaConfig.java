package com.wheelseye.devicegateway.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import java.util.HashMap;
import java.util.Map;

/**
 * Modern Kafka Configuration - Simplified and focused
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private final KafkaTopicsProperties topics;

    public KafkaConfig(KafkaTopicsProperties topics) { 
        this.topics = topics;
    }

    // ---------------- Kafka Admin ----------------
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, topics.bootstrapServers());
        return new KafkaAdmin(configs);
    }

    @Bean
    public NewTopic deviceLocationTopic() {
        return new NewTopic(
                topics.deviceLocation(),
                topics.deviceLocationPartitions(),
                topics.deviceLocationReplication()
        );
    }

    @Bean
    public NewTopic deviceInfoTopic() {
        return new NewTopic(
                topics.deviceInfo(),
                topics.deviceInfoPartitions(),
                topics.deviceInfoReplication()
        );
    }
    
    // ---------------- Producer ----------------
    @Bean
    public ProducerFactory<String, byte[]> byteArrayProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, topics.bootstrapServers());
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.ByteArraySerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate() {
        return new KafkaTemplate<>(byteArrayProducerFactory());
    }

    // ---------------- Consumer ----------------
    // Use JsonDeserializer (Object) → for modern microservices, REST-like messaging, DTO-driven pipelines.
    // Use ByteArrayDeserializer (byte[]) → for device protocols (like GT06 GPS tracker, IoT payloads, TR-069, etc.), custom encoding, or when messages aren’t guaranteed to be JSON.
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, topics.bootstrapServers());
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, topics.consumerGroupId());
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.wheelseye.devicegateway");
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}