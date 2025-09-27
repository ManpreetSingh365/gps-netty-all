package com.wheelseye.devicegateway.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class CommandResponse {
    private String commandId;
    private String deviceId;
    private boolean success;
    private String status;
    private String message;
    private Instant sentAt;
    private String errorDetails;
    
    public static CommandResponse success(String commandId, String deviceId) {
        return CommandResponse.builder()
            .commandId(commandId)
            .deviceId(deviceId)
            .success(true)
            .status("QUEUED")
            .sentAt(Instant.now())
            .build();
    }
    
    public static CommandResponse error(String message) {
        return CommandResponse.builder()
            .success(false)
            .status("ERROR")
            .message(message)
            .errorDetails(message)
            .sentAt(Instant.now())
            .build();
    }
}
