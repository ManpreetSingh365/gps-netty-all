package com.wheelseye.devicegateway.protocol.gt06;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import java.util.List;

/**
 * GT06 Frame Decoder - Modern Java 21 with proper protocol handling
 * Based on official GT06 specification and working reference implementation.
 * Frame format: Start(2) + Length(1) + [Protocol(1) + Info(N) + Serial(2) + CRC(2)] + Stop(2)

 * GT06 Protocol Format:
 * ┌─────────────┬──────────────┬─────────────┬─────────────┬─────────────┬─────────────┬─────────────┐
 * │ Start Bits  │ Packet Length│ Protocol No │ Information │ Serial No   │ Error Check │ Stop Bits   │
 * │ (2 bytes)   │ (1 byte)     │ (1 byte)    │ Content     │ (2 bytes)   │ (2 bytes)   │ (2 bytes)   │
 * │ 0x78 0x78   │ Length       │ Type        │ Variable    │ Sequence    │ CRC-ITU     │ 0x0D 0x0A   │
 * └─────────────┴──────────────┴─────────────┴─────────────┴─────────────┴─────────────┴─────────────┘
 * Login Frame: 78 78 0D 01 01234567890123450001234 5 89AB CD 0D 0A
             │  │  │  │  └─ Info Content (13 bytes) ─┘ │  │  │  │
             │  │  │  └─ Protocol (0x01 = Login)      │  │  │  │
             │  │  └─ Packet Length (0x0D = 13)       │  │  │  │
             │  └─ Start Bits (0x7878)                │  │  │  │
             └─ Total Frame Size = 18 bytes           │  │  │  │
                                                      │  │  │  │
                                                      │  │  └──┴─ Stop Bits (0x0D0A)
                                                      └──┴─ CRC (2 bytes) 
 * Length = Protocol Number + Information Content + Serial Number + Error Check (excludes start bits and length itself)
 */

@Component
@Scope("prototype")  
// Each channel must get a new instance | ByteToMessageDecoder maintains internal buffer state, so it is not sharable across multiple channels.
public class Gt06FrameDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(Gt06FrameDecoder.class);

    // GT06 Protocol constants from specification
    private static final int START_BITS = 0x7878;
    private static final int STOP_BITS = 0x0D0A;
    
    private static final int MIN_FRAME_SIZE = 10; // Minimum GT06 frame size
    private static final int MAX_PACKET_LENGTH = 255; // Max value for length byte

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        
        // Wait for minimum frame size
        if (in.readableBytes() < MIN_FRAME_SIZE) {
            return;
        }

        in.markReaderIndex();
        final int readerIndex = in.readerIndex();

        try {
            // Look for start bits (0x7878)
            if (!findStartBits(in)) {
                return;
            }

            final int startIndex = in.readerIndex();
            
            // Skip start bits and read packet length
            in.skipBytes(2);
            final int packetLength = in.readUnsignedByte();
            
            // Validate packet length
            if (packetLength < 5 || packetLength > MAX_PACKET_LENGTH) {
                logger.debug("Invalid packet length: {} from {}", 
                    packetLength, ctx.channel().remoteAddress());
                resyncFrame(in, startIndex);
                return;
            }
            
            // Calculate total frame size
            // Frame = Start(2) + Length(1) + PacketContent(packetLength) + Stop(2)
            final int frameSize = 2 + 1 + packetLength + 2;
            
            // Check if complete frame is available
            if (in.readableBytes() < frameSize - 3) { // -3 because we already read start+length
                in.readerIndex(startIndex); // Reset to start of frame
                return; // Wait for more data
            }
            
            // Verify stop bits at the correct position
            final int stopPosition = startIndex + frameSize - 2;
            final int actualStopBits = in.getUnsignedShort(stopPosition);
            
            if (actualStopBits != STOP_BITS) {
                logger.debug("Invalid GT06 tail at position {}, expected 0x{:04X}, got 0x{:04X} from {}", 
                    stopPosition, STOP_BITS, actualStopBits, ctx.channel().remoteAddress());
                resyncFrame(in, startIndex);
                return;
            }
            
            // Extract complete valid frame
            in.readerIndex(startIndex);
            final ByteBuf frame = in.readRetainedSlice(frameSize);
            out.add(frame);
            
            logger.debug("Decoded GT06 frame: packetLength={}, frameSize={} from {}",
                packetLength, frameSize, ctx.channel().remoteAddress());
                
        } catch (Exception e) {
            logger.error("Error decoding GT06 frame from {}: {}", 
                ctx.channel().remoteAddress(), e.getMessage(), e);
            // Reset and try to resync
            in.readerIndex(readerIndex);
            if (in.readableBytes() > 0) {
                in.skipBytes(1);
            }
        }
    }

    /**
     * Find and position at GT06 start bits (0x7878)
     */
    private boolean findStartBits(ByteBuf in) {
        final int searchEnd = in.writerIndex() - 1; // Need at least 2 bytes for start bits
        
        while (in.readerIndex() < searchEnd) {
            if (in.getUnsignedShort(in.readerIndex()) == START_BITS) {
                return true; // Found start bits, reader is positioned at them
            }
            in.skipBytes(1); // Move forward one byte and continue searching
        }
        
        return false; // Start bits not found
    }

    /**
     * Resync frame by skipping one byte and continuing search
     */
    private void resyncFrame(ByteBuf in, int failedStartIndex) {
        in.readerIndex(failedStartIndex + 1); // Skip one byte from failed position
        logger.debug("Resyncing GT06 frame from position {}", failedStartIndex + 1);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("GT06 Frame decoder error from {}: {}", 
            ctx.channel().remoteAddress(), cause.getMessage(), cause);
        super.exceptionCaught(ctx, cause);
    }
}