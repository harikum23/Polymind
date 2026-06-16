package com.polymind.governance;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the rate-limit filter after Spring Security's filter chain (which runs at order -100),
 * so the authenticated API key is available when the limiter resolves the bucket key.
 */
@Configuration
public class GovernanceConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitService rateLimit) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(new RateLimitFilter(rateLimit));
        reg.addUrlPatterns("/v1/*");
        reg.setOrder(0); // after security chain (-100), before MVC dispatch
        return reg;
    }
}
