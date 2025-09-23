package com.wheelseye.devicegateway.protocol.gt06;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.wheelseye.devicegateway.protocol.Protocol;
import com.wheelseye.devicegateway.protocol.ProtocolConfig;

import io.netty.channel.ChannelHandler;

/**
 * Gt06 Protocol Implementation - Production Ready
 * 
 * Complete Gt06 protocol implementation following Spring Boot 3.5.5 standards.
 * Provides proper handler lifecycle management and configuration.
 */
@Component("gt06Protocol") // Use consistent naming
public final class Gt06Protocol implements Protocol {
 
    private final ApplicationContext applicationContext;
    private final Gt06ProtocolDecoder protocolDecoder;
    private final Gt06ProtocolEncoder protocolEncoder;
    private final ProtocolConfig config;
 
    public Gt06Protocol(ApplicationContext applicationContext, Gt06ProtocolDecoder protocolDecoder, Gt06ProtocolEncoder protocolEncoder) {
        this.applicationContext = applicationContext;
        this.protocolDecoder = protocolDecoder;
        this.protocolEncoder = protocolEncoder;
     
        // Gt06 uses custom frame decoder, not length-field framing
        this.config = ProtocolConfig.builder()
            .useLengthFieldFraming(false) // Custom Gt06 framing
            .lengthFieldOffset(0)
            .lengthFieldLength(0)
            .lengthAdjustment(0)
            .initialBytesToStrip(0)
            .prependLengthOnOutbound(false)
            .build();
    }    
    @Override
    public String name() {
        return "Gt06";
    }
    
    @Override
    public ProtocolConfig config() {
        return config;
    }
    
    @Override
    public ChannelHandler frameDecoder() {
        // Create new instance for each channel (not @Sharable)
        return applicationContext.getBean(Gt06FrameDecoder.class);
    }
    
    @Override
    public ChannelHandler protocolDecoder() {
        // Reuse shared instance (@Sharable)
        return protocolDecoder;
    }
    
    @Override
    public ChannelHandler protocolEncoder() {
        // Reuse shared instance (@Sharable) 
        return protocolEncoder;
    }
    
    @Override
    public String toString() {
        return String.format("Gt06Protocol{name='%s', config=%s}", name(), config);
    }
}