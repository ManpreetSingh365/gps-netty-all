// CommandRequest.java - Compatible with existing architecture
package com.wheelseye.devicegateway.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class CommandRequest {
    private String deviceId; // IMEI
    private String command; // Raw command string
    private String password; // Device password (optional)
    private String commandType; // Command category
    @Builder.Default
    private int serverFlag = 1; // Server identification flag

    @Builder.Default
    private boolean useEnglish = true; // Language setting
    private Instant requestedAt; // Request timestamp
    private String expectedResponse; // Expected response pattern

    // Timer config specific
    private int accOnInterval; // ACC ON upload interval
    private int accOffInterval; // ACC OFF upload interval

    // Server config specific
    private String serverIp; // Server IP address
    private int serverPort; // Server port number

}
