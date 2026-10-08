package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * amp-starter 入口。金路径：Harness 装配 + Chat 三接口 + HITL confirm。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StarterApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarterApplication.class, args);
    }
}
