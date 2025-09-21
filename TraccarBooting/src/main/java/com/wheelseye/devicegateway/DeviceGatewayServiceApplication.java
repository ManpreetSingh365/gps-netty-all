package com.wheelseye.devicegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main Application Class - Fixed with proper package scanning
 */
@SpringBootApplication(scanBasePackages = {
        "com.wheelseye.devicegateway.config",
        "com.wheelseye.devicegateway.service",
        "com.wheelseye.devicegateway.repository",
        "com.wheelseye.devicegateway.handler",
        "com.wheelseye.devicegateway.protocol",
        "com.wheelseye.devicegateway.controller"
})
@EnableAsync
@EnableScheduling
public class DeviceGatewayServiceApplication {

    public static void main(String[] args) {
        System.out.println("🚀 Starting WheelsEye Device Gateway Service...");
        System.out.println("☕ Java Version: " + System.getProperty("java.version"));
        System.out.println("🌱 Spring Boot: Starting application context...");

        try {
            SpringApplication app = new SpringApplication(DeviceGatewayServiceApplication.class);

            // Enable detailed logging for debugging
            System.setProperty("logging.level.com.wheelseye.devicegateway", "DEBUG");
            System.setProperty("logging.level.org.springframework.context", "INFO");

            app.run(args);

        } catch (Exception e) {
            System.err.println("💥 Application startup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}