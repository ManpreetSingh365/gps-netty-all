package com.aman.location.controller;


import com.aman.location.entity.Location;
import com.aman.location.consumer.LocationConsumer;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class LocationController {

    private final LocationConsumer locationConsumer;

    public LocationController(LocationConsumer locationConsumer) {
        this.locationConsumer = locationConsumer;
    }

    @GetMapping(value = "/locations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Location>> streamLocations() {
        return Flux.<Location>create(locationConsumer::subscribe)
                .map(location ->
                        ServerSentEvent.<Location>builder(location).build()
                );
    }
}

