package com.sedai.spot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories("com.sedai.spot.repository")
public class SpotIntelligenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotIntelligenceApplication.class, args);
    }
}