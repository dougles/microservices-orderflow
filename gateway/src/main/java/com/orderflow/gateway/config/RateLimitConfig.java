package com.orderflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration
public class RateLimitConfig {

  @Bean
  KeyResolver companyKeyResolver() {
    return exchange -> exchange.getPrincipal()
        .cast(JwtAuthenticationToken.class)
        .map(JwtAuthenticationToken::getToken)
        .map(RateLimitConfig::key);
  }

  private static String key(Jwt jwt) {
    String companyId = jwt.getClaimAsString("company_id");
    return companyId != null ? companyId : jwt.getSubject();
  }
}
