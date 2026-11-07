package com.orderflow.e2e.support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base de los {@code *E2E}. Precondición: stack levantado con {@code ORDERFLOW_PROFILE=e2e}
 * (CLAUDE.md, Comandos). La suite corre secuencial.
 */
public abstract class E2EBase {

  @BeforeAll
  static void restAssured() {
    RestAssured.baseURI = Api.BASE_URL;
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
  }
}
