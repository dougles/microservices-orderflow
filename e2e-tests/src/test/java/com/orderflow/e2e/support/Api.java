package com.orderflow.e2e.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API pública vía gateway, {@code http://localhost:8080} (SPEC §5). */
public final class Api {

  public static final String BASE_URL = "http://localhost:8080";
  public static final ZoneId ZONE = ZoneId.of("America/La_Paz");

  public record Created(UUID orderId, String status) {}

  public record Order(
      UUID orderId,
      String companyId,
      String status,
      String reason,
      String total,
      List<Map<String, Object>> items) {}

  private Api() {}

  public static RequestSpecification as(String token) {
    return given().baseUri(BASE_URL).auth().oauth2(token);
  }

  /** POST con una Idempotency-Key nueva; exige 202. */
  public static Created createOrder(String token, String body) {
    return createOrder(token, body, UUID.randomUUID());
  }

  /** POST con la key indicada; exige 202. */
  public static Created createOrder(String token, String body, UUID idempotencyKey) {
    JsonPath json =
        postOrder(token, body, idempotencyKey).then().statusCode(202).extract().jsonPath();
    return new Created(UUID.fromString(json.getString("orderId")), json.getString("status"));
  }

  /** POST sin aserciones, para verificar errores (503, 422, 429…). */
  public static Response postOrder(String token, String body, UUID idempotencyKey) {
    return as(token)
        .header("Idempotency-Key", idempotencyKey.toString())
        .contentType(ContentType.JSON)
        .body(body)
        .post("/api/orders");
  }

  public static Response getOrderResponse(String token, UUID orderId) {
    return as(token).get("/api/orders/{id}", orderId);
  }

  /** GET por id; exige 200. */
  public static Order getOrder(String token, UUID orderId) {
    JsonPath json = getOrderResponse(token, orderId).then().statusCode(200).extract().jsonPath();
    return new Order(
        UUID.fromString(json.getString("orderId")),
        json.getString("companyId"),
        json.getString("status"),
        json.getString("reason"),
        json.getString("total"),
        json.getList("items"));
  }

  public static Response listOrders(String token) {
    return as(token).get("/api/orders");
  }

  /** Fecha de entrega válida por defecto: hoy + 7 en La Paz. */
  public static LocalDate defaultDeliveryDate() {
    return LocalDate.now(ZONE).plusDays(7);
  }

  /** {@code order("SKU-0001", 2, "SKU-0002", 3)} con entrega {@link #defaultDeliveryDate()}. */
  public static String order(Object... skuQuantity) {
    return order(defaultDeliveryDate(), skuQuantity);
  }

  public static String order(LocalDate deliveryDate, Object... skuQuantity) {
    if (skuQuantity.length == 0 || skuQuantity.length % 2 != 0) {
      throw new IllegalArgumentException("se esperan pares sku, cantidad");
    }
    var items = new ArrayList<String>();
    for (int i = 0; i < skuQuantity.length; i += 2) {
      items.add("{\"sku\":\"%s\",\"quantity\":%s}".formatted(skuQuantity[i], skuQuantity[i + 1]));
    }
    return "{\"deliveryDate\":\"%s\",\"items\":[%s]}"
        .formatted(deliveryDate, String.join(",", items));
  }
}
