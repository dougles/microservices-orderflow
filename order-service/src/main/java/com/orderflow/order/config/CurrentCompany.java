package com.orderflow.order.config;

import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** La empresa sale siempre del token, nunca del body ni de la query (SPEC §4). */
@Component
public class CurrentCompany {

  private static final String OPS = "ROLE_ops";

  public Optional<String> id() {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token) {
      return Optional.ofNullable(token.getToken().getClaimAsString("company_id"));
    }
    return Optional.empty();
  }

  /** Empresa del comprador; un token de comprador sin {@code company_id} no puede operar. */
  public String required() {
    return id().orElseThrow(() -> new AccessDeniedException("token sin company_id"));
  }

  /**
   * Empresa a la que se restringe la lectura: vacío para ops (ve todas), la del token para un
   * comprador.
   */
  public Optional<String> readScope() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean ops =
        auth != null && auth.getAuthorities().stream().anyMatch(a -> OPS.equals(a.getAuthority()));
    return ops ? Optional.empty() : Optional.of(required());
  }
}
