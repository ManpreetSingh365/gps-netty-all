package com.wheelseye.devicegateway.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class CommandStatus {
    private String commandId;
    private String deviceId;
    private String commandType;
    private String command;
    private String status;          // CREATED, PENDING, SENT, ACKNOWLEDGED, FAILED, CANCELLED
    private String message;
    private String response;        // Device response
    private String expectedResponse;
    private Instant createdAt;
    private Instant sentAt;
    private Instant acknowledgedAt;
    private Instant cancelledAt;
    private int retryCount;
    private String errorDetails;
}
