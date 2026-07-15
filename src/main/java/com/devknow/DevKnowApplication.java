package com.devknow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DevKnow — 开发者双通道知识助手
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class DevKnowApplication {
    public static void main(String[] args) {
        SpringApplication.run(DevKnowApplication.class, args);
    }
}
