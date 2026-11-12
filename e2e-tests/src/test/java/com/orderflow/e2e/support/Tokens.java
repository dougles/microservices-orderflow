package com.orderflow.e2e.support;

import static io.restassured.RestAssured.given;

import io.restassured.path.json.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens por password grant con el cliente público {@code orderflow-cli} (SPEC §4). Contraseña =
 * nombre de usuario (solo local). Se cachean hasta 30 s antes de expirar.
 */
public final class Tokens {

  private static final String TOKEN_URL =
      "http://localhost:8180/realms/orderflow/protocol/openid-connect/token";
  private static final long MARGIN_SECONDS = 30;

  private record Cached(String accessToken, Instant expiresAt) {}

  private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

  private Tokens() {}

  /** {@code Tokens.buyer("acme")} → usuario {@code buyer-acme}. */
  public static String buyer(String company) {
    return forUser("buyer-" + company.toLowerCase());
  }

  public static String ops() {
    return forUser("ops");
  }

  public static String forUser(String username) {
    Cached cached = CACHE.get(username);
    if (cached == null || Instant.now().isAfter(cached.expiresAt())) {
      cached = fetch(username);
      CACHE.put(username, cached);
    }
    return cached.accessToken();
  }

  private static Cached fetch(String username) {
    JsonPath json =
        given()
            .formParam("grant_type", "password")
            .formParam("client_id", "orderflow-cli")
            .formParam("username", username)
            .formParam("password", username)
            .when()
            .post(TOKEN_URL)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    long expiresIn = json.getLong("expires_in");
    return new Cached(
        json.getString("access_token"), Instant.now().plusSeconds(expiresIn - MARGIN_SECONDS));
  }
}
