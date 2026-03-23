package com.jhsup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OAuthConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .anyRequest().authenticated() // Everything requires login
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("http://localhost:5173/dashboard", true) // REDIRECT HERE
            );
        
        return http.build();
    }
}
