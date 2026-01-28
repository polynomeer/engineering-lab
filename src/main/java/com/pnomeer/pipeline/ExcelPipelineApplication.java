package com.pnomeer.pipeline;

import com.pnomeer.pipeline.config.PipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PipelineProperties.class)
public class ExcelPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExcelPipelineApplication.class, args);
    }

}
