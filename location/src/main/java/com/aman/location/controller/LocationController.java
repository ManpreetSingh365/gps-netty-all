package com.aman.location.controller;

import com.aman.location.dto.LocationDto;
import com.aman.location.messaging.EventConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequiredArgsConstructor
public class LocationController {

    private final EventConsumer eventConsumer;

    @GetMapping(value = "/locations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<LocationDto>> streamLocations() {
        return Flux.<LocationDto>create(eventConsumer::subscribe).map(location -> ServerSentEvent.<LocationDto>builder(location).build());
    }
}