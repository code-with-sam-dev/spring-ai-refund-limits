package com.example.refunds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RefundLimitsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefundLimitsApplication.class, args);
    }
}
