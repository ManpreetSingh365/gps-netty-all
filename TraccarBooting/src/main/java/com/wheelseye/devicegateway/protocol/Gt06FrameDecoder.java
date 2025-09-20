package com.wheelseye.devicegateway.protocol;

import java.time.Instant;
import com.wheelseye.devicegateway.model.MessageFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Gt06FrameDecoder {

    private static final Logger logger = LoggerFactory.getLogger(Gt06FrameDecoder.class);

    private static final int HEADER_78 = 0x7878;
    private static final int HEADER_79 = 0x7979;

    public MessageFrame parseFrame(ByteBuf buffer) {
        try {
            if (buffer.readableBytes() < 5) {
                logger.debug("Insufficient bytes for frame parsing: {}", buffer.readableBytes());
                return null;
            }

            // Store original reader index
            int originalIndex = buffer.readerIndex();

            // Read header
            int startBits = buffer.readUnsignedShort();
            boolean isExtended = (startBits == HEADER_79);

            // Read length
            int length;
            if (isExtended) {
                if (buffer.readableBytes() < 2) {
                    buffer.readerIndex(originalIndex);
                    return null;
                }
                length = buffer.readUnsignedShort();
            } else {
                if (buffer.readableBytes() < 1) {
                    buffer.readerIndex(originalIndex);
                    return null;
                }
                length = buffer.readUnsignedByte();
            }

            // Validate length
            if (length < 1 || length > 1000) {
                logger.debug("Invalid data length: {}", length);
                buffer.readerIndex(originalIndex);
                return null;
            }

            // Check if we have enough data
            int remainingForContent = length - 4; // length includes protocol, serial, and CRC
            if (buffer.readableBytes() < remainingForContent + 4) { // +4 for serial(2) + crc(2)
                buffer.readerIndex(originalIndex);
                return null;
            }

            // Read protocol number
            int protocolNumber = buffer.readUnsignedByte();

            // Read content (remaining data except serial and CRC)
            ByteBuf content = Unpooled.buffer();
            int contentLength = remainingForContent - 1; // -1 for protocol number
            if (contentLength > 0) {
                content.writeBytes(buffer, contentLength);
            }

            // Read serial number
            int serialNumber = buffer.readUnsignedShort();

            // Read CRC
            int crc = buffer.readUnsignedShort();

            // Read stop bits (if available)
            int stopBits = 0x0D0A; // Default
            if (buffer.readableBytes() >= 2) {
                stopBits = buffer.readUnsignedShort();
            }

            // Create hex dump for debugging
            buffer.readerIndex(originalIndex);
            String rawHex = "";
            if (buffer.readableBytes() >= 8) {
                byte[] hexBytes = new byte[Math.min(buffer.readableBytes(), 32)];
                buffer.getBytes(buffer.readerIndex(), hexBytes);
                rawHex = bytesToHex(hexBytes);
            }

            logger.debug("Parsed frame: startBits=0x{:04X}, length={}, protocol=0x{:02X}, serial={}, crc=0x{:04X}",
                    startBits, length, protocolNumber, serialNumber, crc);

            return new MessageFrame(startBits, length, protocolNumber, content, serialNumber, crc, stopBits, rawHex,
                    Instant.now(), null);

        } catch (Exception e) {
            logger.error("Error parsing GT06 frame: {}", e.getMessage(), e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

}
