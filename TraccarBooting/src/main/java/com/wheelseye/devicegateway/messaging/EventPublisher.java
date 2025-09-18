package com.wheelseye.devicegateway.messaging;

import com.wheelseye.devicegateway.domain.events.DeviceSessionEvent;

public interface EventPublisher {
    void publishDeviceSessionEvent(DeviceSessionEvent event);
}
