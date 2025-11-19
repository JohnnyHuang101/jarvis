package com.jhsup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JarvisApplication {

    public static void main(String[] args) {
        // This line boots up the entire server automatically
        SpringApplication.run(JarvisApplication.class, args);
    }
}