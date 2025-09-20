package com.wheelseye.devicegateway.repository;

import io.netty.channel.Channel;
import java.util.Optional;
import com.wheelseye.devicegateway.model.IMEI;

import com.wheelseye.devicegateway.model.DeviceSession;

import java.util.Collection;

public interface SessionRepository {
    void save(DeviceSession session);
    Optional<DeviceSession> findById(String sessionId);
    Optional<DeviceSession> findByImei(IMEI imei);
    Optional<DeviceSession> findByChannel(Channel channel);
    void deleteById(String sessionId);
    void deleteByChannel(Channel channel);
    Collection<DeviceSession> findAll();
    Collection<DeviceSession> findIdleSessions(long maxIdleSeconds);
}
