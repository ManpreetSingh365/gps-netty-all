package com.aman.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.aman.location.config.KafkaTopicsProperties;

@SpringBootApplication
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class LocationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LocationApplication.class, args);
    }
}