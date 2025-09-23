// Protocol.java
package com.wheelseye.devicegateway.protocol;

import io.netty.channel.ChannelHandler;
import lombok.NonNull;

/**
 * Lightweight protocol abstraction. Implementations must be stateless.
 */
public interface Protocol {

    // The unique name of this protocol (e.g., "GT06", "T355", "GT02")
    @NonNull
    String name();

    // The configuration for frame decoding/encoding
    @NonNull
    ProtocolConfig config();

    // Custom frame decoder, or null if using length-field framing
    ChannelHandler frameDecoder();

    // Protocol decoder; must never return null
    @NonNull
    ChannelHandler protocolDecoder();

    // Protocol encoder; must never return null
    @NonNull
    ChannelHandler protocolEncoder();
}
