package com.jhsup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//java -jar target/drive-fetcher-1.0-SNAPSHOT.jar
@Configuration
@EnableWebSecurity // Good practice to include this
public class OAuthConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 1. Disable CSRF so you can send POST requests without a token
            .csrf(csrf -> csrf.disable())
            
            // 2. Enable CORS so your Vue app (5173) can talk to Spring (8080)
            .cors(org.springframework.security.config.Customizer.withDefaults())
            
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login/**", "/oauth2/**", "/api/**").permitAll() 
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("http://localhost:5173/chat", true)
            );
            
        return http.build();
    }
}