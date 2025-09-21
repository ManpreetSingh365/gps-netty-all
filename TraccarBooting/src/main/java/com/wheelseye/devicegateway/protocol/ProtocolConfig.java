package com.wheelseye.devicegateway.protocol;

import lombok.Builder;
import lombok.Getter;

/**
 * Immutable protocol configuration for frame decoding and encoding.
 * Contains settings for length-field based framing and protocol-specific options.
 */

@Getter
@Builder
public final class ProtocolConfig {

    private final boolean useLengthFieldFraming;
    private final int lengthFieldOffset;
    private final int lengthFieldLength;
    private final int lengthAdjustment;
    private final int initialBytesToStrip;
    private final boolean prependLengthOnOutbound;

    /**
     * Constructor for ProtocolConfig
     * 
     * @param useLengthFieldFraming Whether to use Netty's LengthFieldBasedFrameDecoder
     * @param lengthFieldOffset The offset of the length field
     * @param lengthFieldLength The length of the length field (1, 2, 3, 4, or 8 bytes)
     * @param lengthAdjustment The compensation value to add to the length
     * @param initialBytesToStrip The number of bytes to strip from the decoded frame
     * @param prependLengthOnOutbound Whether to prepend length field on outbound messages
     */
    public ProtocolConfig(boolean useLengthFieldFraming,
                         int lengthFieldOffset,
                         int lengthFieldLength,
                         int lengthAdjustment,
                         int initialBytesToStrip,
                         boolean prependLengthOnOutbound) {
        this.useLengthFieldFraming = useLengthFieldFraming;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.lengthAdjustment = lengthAdjustment;
        this.initialBytesToStrip = initialBytesToStrip;
        this.prependLengthOnOutbound = prependLengthOnOutbound;
    }

    /**
     * @return true if protocol uses length-field based framing, false for custom frame decoder
     */
    public boolean useLengthFieldFraming() { 
        return useLengthFieldFraming; 
    }

    /**
     * @return the offset of the length field in bytes from start of frame
     */
    public int lengthFieldOffset() { 
        return lengthFieldOffset; 
    }

    /**
     * @return the length of the length field in bytes (1, 2, 3, 4, or 8)
     */
    public int lengthFieldLength() { 
        return lengthFieldLength; 
    }

    /**
     * @return the compensation value to add to the length field value
     */
    public int lengthAdjustment() { 
        return lengthAdjustment; 
    }

    /**
     * @return the number of bytes to strip from the beginning of the decoded frame
     */
    public int initialBytesToStrip() { 
        return initialBytesToStrip; 
    }

    /**
     * @return true if length field should be prepended on outbound messages
     */
    public boolean prependLengthOnOutbound() { 
        return prependLengthOnOutbound; 
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

    /**
     * Create a configuration for protocols that don't use length-field framing (like GT06)
     */
    public static ProtocolConfig customFrameDecoder() {
        return new ProtocolConfig(false, 0, 0, 0, 0, false);
    }

    /**
     * Create a configuration for protocols that use length-field framing
     * 
     * @param offset Offset of length field
     * @param length Length of length field (1, 2, 3, 4, or 8 bytes)
     * @param adjustment Adjustment to add to length value
     * @param strip Number of bytes to strip from decoded frame
     * @return ProtocolConfig for length-field framing
     */
    public static ProtocolConfig lengthFieldFraming(int offset, int length, int adjustment, int strip) {
        return new ProtocolConfig(true, offset, length, adjustment, strip, false);
    }

    /**
     * Create a configuration for protocols that use length-field framing with outbound prepending
     * 
     * @param offset Offset of length field
     * @param length Length of length field (1, 2, 3, 4, or 8 bytes)
     * @param adjustment Adjustment to add to length value
     * @param strip Number of bytes to strip from decoded frame
     * @param prependOnOutbound Whether to prepend length on outbound messages
     * @return ProtocolConfig for length-field framing with outbound configuration
     */
    public static ProtocolConfig lengthFieldFraming(int offset, int length, int adjustment, int strip, boolean prependOnOutbound) {
        return new ProtocolConfig(true, offset, length, adjustment, strip, prependOnOutbound);
    }
}