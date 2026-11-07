package com.orderflow.catalog.config;

import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** La empresa sale siempre del token, nunca del body ni de la query (SPEC §4). */
@Component
public class CurrentCompany {

  public Optional<String> id() {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token) {
      return Optional.ofNullable(token.getToken().getClaimAsString("company_id"));
    }
    return Optional.empty();
  }
}
