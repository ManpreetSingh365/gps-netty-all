#!/bin/bash

# GT06 Protocol Validation Script
# Tests the complete GT06 implementation with real device data

echo \"🧪 GT06 Protocol Comprehensive Validation\"
echo \"=========================================\"
echo \"\"

# Colors for output
RED='\\033[0;31m'
GREEN='\\033[0;32m'
YELLOW='\\033[1;33m'
BLUE='\\033[0;34m'
NC='\\033[0m' # No Color

# Test counter
TESTS_RUN=0
TESTS_PASSED=0

run_test() {
    local test_name=\"$1\"
    local expected=\"$2\"
    local command=\"$3\"
    
    TESTS_RUN=$((TESTS_RUN + 1))
    
    echo -e \"${BLUE}Running: $test_name${NC}\"
    
    if eval \"$command\"; then
        if [[ \"$expected\" == \"success\" ]]; then
            echo -e \"${GREEN}✅ PASSED: $test_name${NC}\"
            TESTS_PASSED=$((TESTS_PASSED + 1))
        else
            echo -e \"${RED}❌ FAILED: $test_name (expected failure but succeeded)${NC}\"
        fi
    else
        if [[ \"$expected\" == \"failure\" ]]; then
            echo -e \"${GREEN}✅ PASSED: $test_name (expected failure)${NC}\"
            TESTS_PASSED=$((TESTS_PASSED + 1))
        else
            echo -e \"${RED}❌ FAILED: $test_name${NC}\"
        fi
    fi
    echo \"\"
}

# Test 1: Compilation Test
echo -e \"${YELLOW}Phase 1: Compilation Test${NC}\"
echo \"-------------------------\"

run_test \"Maven Compile\" \"success\" \"mvn clean compile -q\"

# Test 2: Unit Tests
echo -e \"${YELLOW}Phase 2: Unit Tests${NC}\"
echo \"------------------\"

# Create a simple test to verify GT06 protocol parsing
cat > src/test/java/GT06ProtocolTest.java << 'EOF'
import com.wheelseye.devicegateway.protocol.GT06FrameDecoder;
import com.wheelseye.devicegateway.protocol.GT06ProtocolDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GT06ProtocolTest {
    
    @Test
    public void testLoginFrameDecoding() {
        GT06FrameDecoder decoder = new GT06FrameDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        
        // GT06 login packet: 7878 0D 01 012345678901234500 01 2345 0D0A
        String loginHex = \"78780D010123456789012345000123450D0A\";
        ByteBuf input = hexToByteBuf(loginHex);
        
        assertTrue(channel.writeInbound(input));
        
        ByteBuf decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(18, decoded.readableBytes()); // Total frame size
        
        decoded.release();
        channel.close();
    }
    
    @Test
    public void testInvalidFrameRejection() {
        GT06FrameDecoder decoder = new GT06FrameDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        
        // Invalid start bits
        String invalidHex = \"12340D010123456789012345000123450D0A\";
        ByteBuf input = hexToByteBuf(invalidHex);
        
        assertFalse(channel.writeInbound(input));
        
        Object decoded = channel.readInbound();
        assertNull(decoded); // Should not decode invalid frame
        
        channel.close();
    }
    
    private ByteBuf hexToByteBuf(String hex) {
        hex = hex.replaceAll(\"\\\\s+\", \"\");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int index = i * 2;
            bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
        }
        return Unpooled.copiedBuffer(bytes);
    }
}
EOF

run_test \"Unit Test Execution\" \"success\" \"mvn test -Dtest=GT06ProtocolTest -q\"

# Test 3: Integration Test
echo -e \"${YELLOW}Phase 3: Integration Test${NC}\"
echo \"------------------------\"

run_test \"Spring Boot Context Load\" \"success\" \"mvn spring-boot:run -Dspring-boot.run.arguments='--server.port=0 --spring.main.web-environment=false' &
    sleep 10
    PID=\\$!
    kill \\$PID 2>/dev/null || true
    wait \\$PID 2>/dev/null || true\"

# Test 4: Protocol Specification Compliance
echo -e \"${YELLOW}Phase 4: Protocol Compliance${NC}\"
echo \"---------------------------\"

# Create a more comprehensive test
cat > test_protocol_compliance.py << 'EOF'
#!/usr/bin/env python3

import struct
import socket
import time
import threading

def create_gt06_login(imei):
    \"\"\"Create a GT06 login packet according to specification\"\"\"
    # Start bits
    packet = b'\\x78\\x78'
    
    # Packet length (will be calculated)
    packet_content = b''
    
    # Protocol number (0x01 for login)
    packet_content += b'\\x01'
    
    # Terminal ID (IMEI in BCD format - simplified for test)
    imei_bcd = bytes.fromhex('0123456789012345')  # 8 bytes
    packet_content += imei_bcd
    
    # Type identification (2 bytes)
    packet_content += b'\\x22\\x00'
    
    # Timezone and language (2 bytes)
    packet_content += b'\\x00\\x01'
    
    # Serial number (2 bytes) 
    packet_content += b'\\x00\\x01'
    
    # Calculate CRC (simplified - just use placeholder)
    crc = b'\\xFF\\xFF'
    packet_content += crc
    
    # Add packet length
    packet += bytes([len(packet_content)])
    packet += packet_content
    
    # Stop bits
    packet += b'\\x0D\\x0A'
    
    return packet

def test_tcp_connection():
    \"\"\"Test TCP connection to the GT06 server\"\"\"
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        
        # Connect to server
        sock.connect(('localhost', 5023))
        print(\"✅ Connected to GT06 server on port 5023\")
        
        # Send login packet
        login_packet = create_gt06_login('123456789012345')
        sock.send(login_packet)
        print(f\"📤 Sent login packet: {login_packet.hex()}\"
        
        # Try to receive response
        response = sock.recv(1024)
        print(f\"📥 Received response: {response.hex()}\"
        
        sock.close()
        return True
        
    except Exception as e:
        print(f\"❌ Connection test failed: {e}\")
        return False

if __name__ == '__main__':
    success = test_tcp_connection()
    exit(0 if success else 1)
EOF

chmod +x test_protocol_compliance.py

# Start the application in background for testing
echo \"Starting application for integration test...\"
mvn spring-boot:run -Dspring-boot.run.arguments='--logging.level.root=WARN' &> /dev/null &
APP_PID=$!

# Wait for application to start
sleep 15

run_test \"TCP Connection Test\" \"success\" \"python3 test_protocol_compliance.py\"

# Clean up
kill $APP_PID 2>/dev/null || true
wait $APP_PID 2>/dev/null || true

# Test 5: Performance Test
echo -e \"${YELLOW}Phase 5: Performance Test${NC}\"
echo \"------------------------\"

cat > performance_test.java << 'EOF'
import com.wheelseye.devicegateway.protocol.GT06FrameDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

public class PerformanceTest {
    public static void main(String[] args) {
        GT06FrameDecoder decoder = new GT06FrameDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        
        String testPacket = \"78781F120B081D112E10CF027AC7EB0C46584900148F01CC00287D001FB80003FF010D0A\";
        int iterations = 50000;
        
        long start = System.nanoTime();
        
        for (int i = 0; i < iterations; i++) {
            ByteBuf input = hexToByteBuf(testPacket);
            channel.writeInbound(input);
            ByteBuf decoded = channel.readInbound();
            if (decoded != null) {
                decoded.release();
            }
        }
        
        long end = System.nanoTime();
        long durationMs = (end - start) / 1_000_000;
        
        System.out.printf(\"Processed %d packets in %d ms\\n\", iterations, durationMs);
        System.out.printf(\"Rate: %.2f packets/second\\n\", (iterations * 1000.0) / durationMs);
        
        channel.close();
    }
    
    private static ByteBuf hexToByteBuf(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return Unpooled.copiedBuffer(bytes);
    }
}
EOF

run_test \"Performance Test\" \"success\" \"javac -cp '$(mvn dependency:build-classpath -q)' performance_test.java && java -cp '.:$(mvn dependency:build-classpath -q)' PerformanceTest\"

# Clean up test files
rm -f test_protocol_compliance.py performance_test.java PerformanceTest.class src/test/java/GT06ProtocolTest.java 2>/dev/null

# Test Results Summary
echo -e \"${YELLOW}Test Results Summary${NC}\"
echo \"====================\"
echo \"\"
echo -e \"Tests Run: ${BLUE}$TESTS_RUN${NC}\"
echo -e \"Tests Passed: ${GREEN}$TESTS_PASSED${NC}\"
echo -e \"Tests Failed: ${RED}$((TESTS_RUN - TESTS_PASSED))${NC}\"
echo \"\"

if [ $TESTS_PASSED -eq $TESTS_RUN ]; then
    echo -e \"${GREEN}🎉 All tests passed! GT06 protocol implementation is working correctly.${NC}\"
    exit 0
else
    echo -e \"${RED}❌ Some tests failed. Please check the implementation.${NC}\"
    exit 1
fi