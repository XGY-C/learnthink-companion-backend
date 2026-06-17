package com.learnthink.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LearnThink Web应用启动类
 */
@SpringBootApplication(scanBasePackages = {"com.learnthink"})
@MapperScan({"com.learnthink.core.repository", "com.learnthink.core.tutoring.repository"})
@EnableScheduling
public class LearnthinkWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(LearnthinkWebApplication.class, args);
    }

}
