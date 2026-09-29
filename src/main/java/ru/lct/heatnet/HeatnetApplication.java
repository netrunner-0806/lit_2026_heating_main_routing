package ru.lct.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HeatnetApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeatnetApplication.class, args);
    }
}
