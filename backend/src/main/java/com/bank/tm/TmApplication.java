package com.bank.tm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TmApplication {

    public static void main(String[] args) {
        SpringApplication.run(TmApplication.class, args);
    }
}
