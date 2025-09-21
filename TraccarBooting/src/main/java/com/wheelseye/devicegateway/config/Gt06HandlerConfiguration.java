package com.wheelseye.devicegateway.config;

import com.wheelseye.devicegateway.protocol.Gt06FrameDecoder;
import com.wheelseye.devicegateway.protocol.Gt06ProtocolDecoder;
import com.wheelseye.devicegateway.protocol.Gt06ProtocolEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * GT06 Handler Configuration - Production Ready
 * 
 * Provides proper Spring bean configuration for GT06 protocol handlers
 * following Spring Boot 3.5.5 standards and Netty best practices.
 */
@Configuration
public class Gt06HandlerConfiguration {

    /**
     * GT06 Frame Decoder - Prototype scoped (new instance per channel)
     * Cannot be @Sharable due to internal state in ByteToMessageDecoder
     */
    @Bean
    @Scope("prototype")
    public Gt06FrameDecoder gt06FrameDecoder() {
        System.out.println("🔧 Creating Gt06FrameDecoder bean");
        return new Gt06FrameDecoder();
    }

    /**
     * GT06 Protocol Decoder - Singleton scoped (@Sharable)
     * Stateless decoder can be shared across all channels
     */
    @Bean
    public Gt06ProtocolDecoder gt06ProtocolDecoder() {
        System.out.println("🔧 Creating Gt06ProtocolDecoder bean");
        return new Gt06ProtocolDecoder();
    }

    /**
     * GT06 Protocol Encoder - Singleton scoped (@Sharable)
     * Stateless encoder can be shared across all channels
     */
    @Bean
    public Gt06ProtocolEncoder gt06ProtocolEncoder() {
        System.out.println("🔧 Creating Gt06ProtocolEncoder bean");
        return new Gt06ProtocolEncoder();
    }
}
