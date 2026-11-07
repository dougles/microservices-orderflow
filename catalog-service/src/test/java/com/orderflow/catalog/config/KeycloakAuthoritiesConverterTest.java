package com.orderflow.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakAuthoritiesConverterTest {

  private final KeycloakAuthoritiesConverter converter = new KeycloakAuthoritiesConverter();

  @Test
  void mapeaRolesDeRealmYScopes() {
    var jwt =
        token()
            .claim("realm_access", Map.of("roles", List.of("buyer", "ops")))
            .claim("scope", "catalog.internal profile")
            .build();

    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder(
            "ROLE_buyer", "ROLE_ops", "SCOPE_catalog.internal", "SCOPE_profile");
  }

  @Test
  void sinRealmAccessSoloMapeaScopes() {
    var jwt = token().claim("scope", "catalog.internal").build();

    assertThat(converter.convert(jwt))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("SCOPE_catalog.internal");
  }

  private static Jwt.Builder token() {
    return Jwt.withTokenValue("token").header("alg", "none");
  }
}
