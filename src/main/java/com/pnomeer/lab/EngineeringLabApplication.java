package com.pnomeer.lab;

import com.pnomeer.lab.experiments.pipeline.config.PipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PipelineProperties.class)
public class EngineeringLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngineeringLabApplication.class, args);
    }

}
