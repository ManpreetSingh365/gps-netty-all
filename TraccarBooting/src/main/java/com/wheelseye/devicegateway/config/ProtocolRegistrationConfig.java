package com.wheelseye.devicegateway.config;

import com.wheelseye.devicegateway.protocol.Gt06Protocol;
import com.wheelseye.devicegateway.protocol.ProtocolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * Protocol Registration Configuration - Fixed with proper event handling
 */
@Configuration
public class ProtocolRegistrationConfig {

    private static final Logger logger = LoggerFactory.getLogger(ProtocolRegistrationConfig.class);
    
    private final ProtocolFactory protocolFactory;
    private final Gt06Protocol gt06Protocol;
    
    public ProtocolRegistrationConfig(ProtocolFactory protocolFactory, Gt06Protocol gt06Protocol) {
        this.protocolFactory = protocolFactory;
        this.gt06Protocol = gt06Protocol;
        
        logger.info("🔧 ProtocolRegistrationConfig created with GT06 protocol: {}", gt06Protocol);
    }
    
    /**
     * Register protocols when application is fully ready
     * Using ApplicationReadyEvent instead of ContextRefreshedEvent for better timing
     */
    @EventListener
    @Order(1) // Ensure this runs early
    public void registerProtocols(ApplicationReadyEvent event) {
        logger.info("🚀 Starting protocol registration...");
        
        try {
            // Register GT06 protocol
            protocolFactory.register(gt06Protocol);
            
            // Log successful registration
            var registeredProtocols = protocolFactory.getRegisteredProtocols();
            logger.info("✅ Protocol registration completed!");
            logger.info("📋 Registered protocols: {}", registeredProtocols);
            logger.info("📊 Total protocols registered: {}", protocolFactory.getRegisteredCount());
            
            // Verify GT06 is available
            if (protocolFactory.isRegistered("GT06")) {
                logger.info("✅ GT06 protocol is available and ready!");
            } else {
                logger.error("❌ GT06 protocol registration failed!");
            }
            
        } catch (Exception e) {
            logger.error("💥 Failed to register protocols", e);
            throw new RuntimeException("Protocol registration failed", e);
        }
    }
    
    /**
     * Fallback method to register protocols if ApplicationReadyEvent fails
     */
    @EventListener
    @Order(2) // Run after ApplicationReadyEvent
    public void validateProtocolRegistration(ApplicationReadyEvent event) {
        if (protocolFactory.getRegisteredCount() == 0) {
            logger.warn("⚠️ No protocols registered, attempting emergency registration...");
            
            try {
                protocolFactory.register(gt06Protocol);
                logger.info("🚨 Emergency protocol registration successful: {}", 
                    protocolFactory.getRegisteredProtocols());
            } catch (Exception e) {
                logger.error("💥 Emergency protocol registration failed", e);
            }
        }
    }
}