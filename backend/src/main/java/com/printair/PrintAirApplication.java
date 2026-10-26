package com.printair;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PrintAirApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrintAirApplication.class, args);
    }
}
