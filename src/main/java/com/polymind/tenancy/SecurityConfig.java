package com.polymind.tenancy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless API-key security. Swagger UI, actuator and the liveness probe stay open; the
 * OpenAI-compatible {@code /v1/*} surface requires a valid key, and {@code /v1/admin/*} requires
 * an admin key. Enforcement is toggled by {@code polymind.tenancy.auth-enabled}.
 */
@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
public class SecurityConfig {

    private static final String[] OPEN_PATHS = {
            "/v1/health",
            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/actuator/health", "/actuator/info", "/actuator/prometheus"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ApiKeyAuthFilter apiKeyAuthFilter,
                                           TenancyProperties props) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        if (props.isAuthEnabled()) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers(OPEN_PATHS).permitAll()
                    .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/v1/**").hasRole("USER")
                    .anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }
}
