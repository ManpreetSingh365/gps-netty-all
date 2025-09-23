package com.wheelseye.devicegateway.protocol;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable protocol configuration for frame decoding and encoding.
 * 
 * Provides settings for Netty's LengthFieldBasedFrameDecoder and related encoding options.
 * Used to define how raw byte streams are framed into protocol-specific messages.
 */
@Value
@Builder
public class ProtocolConfig {

    // Enable/disable LengthFieldBasedFrameDecoder → true = framed by length, false = custom decoder
    boolean useLengthFieldFraming;  
    
    // Byte offset where length field starts → wrong value causes misaligned frame parsing
    int lengthFieldOffset;           
    
    // Size of length field (1/2/3/4/8 bytes) → must match protocol spec or decoding fails
    int lengthFieldLength;          
    
    // Extra bytes to add/subtract from reported length → corrects discrepancies in total frame size
    int lengthAdjustment;            
    
    // Number of bytes removed from decoded frame → strips protocol headers not needed downstream
    int initialBytesToStrip;         
    
    // Whether to add length field on outgoing messages → required if peer expects framed payloads
    boolean prependLengthOnOutbound; 

    public ProtocolConfig(boolean useLengthFieldFraming,int lengthFieldOffset,int lengthFieldLength,int lengthAdjustment,int initialBytesToStrip,boolean prependLengthOnOutbound) {
        this.useLengthFieldFraming = useLengthFieldFraming;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        this.initialBytesToStrip = initialBytesToStrip;
        this.prependLengthOnOutbound = prependLengthOnOutbound;
    }

    // Factory method for custom decoders (no length field, e.g., GT06)
    public static ProtocolConfig customFrameDecoder() {
        return new ProtocolConfig(false, 0, 0, 0, 0, false);
    }

    // Factory method for length-field framing (inbound only)
    public static ProtocolConfig lengthFieldFraming(int offset, int length, int adjustment, int strip) {
        return new ProtocolConfig(true, offset, length, adjustment, strip, false);
    }

    // Factory method for length-field framing with outbound prepend
    public static ProtocolConfig lengthFieldFraming(int offset, int length, int adjustment, int strip, boolean prependOnOutbound) {
        return new ProtocolConfig(true, offset, length, adjustment, strip, prependOnOutbound);
    }

    @Override
    public String toString() {
        return "ProtocolConfig{" +
                "useLengthFieldFraming=" + useLengthFieldFraming +
                ", lengthFieldOffset=" + lengthFieldOffset +
                ", lengthFieldLength=" + lengthFieldLength +
                ", lengthAdjustment=" + lengthAdjustment +
                ", initialBytesToStrip=" + initialBytesToStrip +
                ", prependLengthOnOutbound=" + prependLengthOnOutbound +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProtocolConfig)) return false;

        ProtocolConfig that = (ProtocolConfig) o;

        if (useLengthFieldFraming != that.useLengthFieldFraming) return false;
        if (lengthFieldOffset != that.lengthFieldOffset) return false;
        if (lengthFieldLength != that.lengthFieldLength) return false;
        if (lengthAdjustment != that.lengthAdjustment) return false;
        if (initialBytesToStrip != that.initialBytesToStrip) return false;
        return prependLengthOnOutbound == that.prependLengthOnOutbound;
    }

    @Override
    public int hashCode() {
        int result = (useLengthFieldFraming ? 1 : 0);
        result = 31 * result + lengthFieldOffset;
        result = 31 * result + lengthFieldLength;
        result = 31 * result + lengthAdjustment;
        result = 31 * result + initialBytesToStrip;
        result = 31 * result + (prependLengthOnOutbound ? 1 : 0);
        return result;
    }
}