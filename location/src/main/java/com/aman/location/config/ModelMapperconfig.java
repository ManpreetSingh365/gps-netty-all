package com.aman.location.config;

import org.modelmapper.ModelMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelMapperconfig {
    
     @Bean
    public ModelMapper modelMapper() {
        ModelMapper mapper = new ModelMapper();
        // Optional: add mappings, converters, or skip nulls, etc.
        return mapper;
    }

}
