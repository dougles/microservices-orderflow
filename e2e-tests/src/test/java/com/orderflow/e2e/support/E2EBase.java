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

  /** Las semillas no se re-ejecutan entre corridas: repone stock y capacidad. */
  @BeforeAll
  static void ensureSeeds() {
    Db.update(
        Db.Database.INVENTORY,
        """
        INSERT INTO stock (sku, available)
        SELECT 'SKU-' || to_char(n, 'FM0000'), 1000 FROM generate_series(1, 20) AS n
        ON CONFLICT (sku) DO UPDATE SET available = EXCLUDED.available
        """);
    Db.update(
        Db.Database.SHIPPING,
        """
        INSERT INTO shipping_capacity (delivery_date, max_orders, booked)
        SELECT current_date + n, 100, 0 FROM generate_series(0, 90) AS n
        ON CONFLICT (delivery_date) DO UPDATE SET max_orders = 100, booked = 0
        """);
  }
}
