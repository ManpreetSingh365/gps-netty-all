package com.wheelseye.devicegateway.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for Protocol implementations.
 */
@Component
@Slf4j
public class ProtocolFactory {
    
    // Thread-safe map to hold protocol name to Protocol instance mappings
    private final Map<String, Protocol> registry = new ConcurrentHashMap<>();

    // Register a non-null protocol by its uppercase name
    public void register(Protocol protocol) {
        Assert.notNull(protocol, "Protocol cannot be null");
        String name = protocol.name().toUpperCase();
        registry.put(name, protocol);
        log.info("✅ Registered protocol: {}", name);
    }

    // Retrieve a protocol by name (case-insensitive)
    public Optional<Protocol> get(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(registry.get(name.toUpperCase()));
    }

    // Check if a protocol is registered
    public boolean isRegistered(String name) {
        return get(name).isPresent();
    }

    // List all registered protocol names
    public Set<String> getRegisteredProtocols() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    // Get the count of registered protocols
    public int getRegisteredCount() {
        return registry.size();
    }

    // Clear all registered protocols from the registry
    public void clear() {
        registry.clear();
        log.warn("🗑️ All protocols have been cleared from registry");
    }

    // Return a sorted comma-separated list of registered protocol names
    @Override
    public String toString() {
        String names = registry.keySet().stream().sorted().collect(Collectors.joining(", "));
        return "ProtocolFactory{registered=" + names + "}";
    }
}
