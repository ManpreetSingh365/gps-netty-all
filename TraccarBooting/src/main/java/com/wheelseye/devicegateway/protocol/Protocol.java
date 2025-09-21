package com.wheelseye.devicegateway.protocol;

import io.netty.channel.ChannelHandler;

/**
 * Lightweight protocol abstraction. Implementations must be stateless.
 * This interface defines the contract for all device communication protocols.
 */
public interface Protocol {
    
    /**
     * @return The unique name of this protocol (e.g., "GT06", "T355", "GT02")
     */
    String name();
    
    /**
     * @return The configuration for frame decoding/encoding for this protocol
     * NOTE: This should return ProtocolConfig (data class), NOT ProtocolConfiguration (Spring config class)
     */
    ProtocolConfig config();
    
    /**
     * @return Custom frame decoder for protocols that don't use length-field framing, 
     *         or null if using Netty's LengthFieldBasedFrameDecoder
     */
    ChannelHandler frameDecoder(); 
    
    /**
     * @return Protocol decoder that converts ByteBuf frames to DeviceMessage objects
     *         This handler is REQUIRED and must never return null
     */
    ChannelHandler protocolDecoder();   
    
    /**
     * @return Protocol encoder that converts DeviceMessage objects to ByteBuf responses
     *         This handler is REQUIRED and must never return null
     */
    ChannelHandler protocolEncoder();   
}