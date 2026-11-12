package com.backroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BackroomApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackroomApplication.class, args);
    }
}
