package com.wheelseye.devicegateway.protocol;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol Factory - Fixed and complete implementation
 */
@Component
public class ProtocolFactory {

    private final Map<String, Protocol> registry = new ConcurrentHashMap<>();

    /**
     * Register a protocol in the factory
     */
    public void register(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("Protocol cannot be null");
        }
        
        String protocolName = protocol.name().toUpperCase();
        registry.put(protocolName, protocol);
        
        System.out.println("✅ Registered protocol: " + protocolName);
    }

    /**
     * Get a protocol by name
     */
    public Optional<Protocol> get(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        
        return Optional.ofNullable(registry.get(name.toUpperCase()));
    }

    /**
     * Check if a protocol is registered
     */
    public boolean isRegistered(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        
        return registry.containsKey(name.toUpperCase());
    }

    /**
     * Get all registered protocol names
     */
    public Set<String> getRegisteredProtocols() {
        return Set.copyOf(registry.keySet());
    }

    /**
     * Get the number of registered protocols
     */
    public int getRegisteredCount() {
        return registry.size();
    }

    /**
     * Clear all registered protocols (for testing)
     */
    public void clear() {
        registry.clear();
    }
}