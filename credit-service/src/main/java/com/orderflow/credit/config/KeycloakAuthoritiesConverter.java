package com.orderflow.credit.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** {@code realm_access.roles} → {@code ROLE_<rol>} y claim {@code scope} → {@code SCOPE_<scope>}. */
class KeycloakAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    var authorities = new ArrayList<GrantedAuthority>(scopes.convert(jwt));
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
      roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    return authorities;
  }
}
