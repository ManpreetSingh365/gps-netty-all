package com.wheelseye.devicegateway.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import com.wheelseye.devicegateway.protocol.Gt06Protocol;
import com.wheelseye.devicegateway.protocol.ProtocolFactory;

/**
 * Protocol Configuration - Automatically registers all available protocols.
 * This ensures protocols are properly registered in the ProtocolFactory on
 * application startup.
 */
@Configuration
public class ProtocolConfiguration {

    private final ProtocolFactory protocolFactory;
    private final Gt06Protocol gt06Protocol;

    public ProtocolConfiguration(ProtocolFactory protocolFactory, Gt06Protocol gt06Protocol) {
        this.protocolFactory = protocolFactory;
        this.gt06Protocol = gt06Protocol;
    }
    // Add other protocols as they become available
    // @Autowired private T355Protocol t355Protocol;
    // @Autowired private Gt02Protocol gt02Protocol;

    /**
     * Register all protocols when Spring context is fully loaded
     */
    @EventListener
    public void registerProtocols(ContextRefreshedEvent event) {

        // Register GT06 protocol
        protocolFactory.register(gt06Protocol);

        // Register additional protocols as needed
        // protocolFactory.register(t355Protocol);
        // protocolFactory.register(gt02Protocol);

        System.out.println("Registered protocols: " + protocolFactory.getRegisteredProtocols());
    }
}