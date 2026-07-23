package com.bmp.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic on everything EXCEPT /actuator/health and /monitor (the GitHub webhook target
 * — protected instead by spring-cloud-config-monitor's own HMAC signature check against
 * {@code spring.cloud.config.monitor.github.secret}, since GitHub can't do HTTP Basic on a
 * webhook call). Every service fetching its config (spring.config.import=configserver:...)
 * needs BMP_CONFIG_USERNAME/BMP_CONFIG_PASSWORD set to match, or add them inline in the
 * URL — see each service's own application.yml comment once wired.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/monitor").permitAll()
                .anyRequest().authenticated())
            .httpBasic(withDefaults -> {});
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(
            PasswordEncoder encoder,
            @Value("${bmp.config-server.username:bmp-config}") String username,
            @Value("${bmp.config-server.password:dev-only-config-password-change-in-real-environment}") String password) {
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("CONFIG_CLIENT").build());
    }
}
