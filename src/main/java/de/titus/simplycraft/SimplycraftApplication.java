package de.titus.simplycraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SimplycraftApplication {
    public static void main(String[] args) {
        SpringApplication.run(SimplycraftApplication.class, args);
    }
}
