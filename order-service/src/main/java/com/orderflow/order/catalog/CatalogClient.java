package com.orderflow.order.catalog;

import com.orderflow.order.config.CatalogClientConfig;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@code GET /internal/prices} de catalog-service (SPEC §6). Sin retry (SPEC §12). */
@Component
public class CatalogClient {

  private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

  /** Precios de la empresa para los skus pedidos. */
  public record Prices(String currency, Map<String, BigDecimal> unitPrices, List<String> unknownSkus) {}

  record PriceItem(String sku, String unitPrice) {}

  record PricesResponse(String currency, List<PriceItem> items, List<String> unknownSkus) {}

  private final RestClient rest;
  private final CircuitBreaker circuitBreaker;

  CatalogClient(RestClient catalogRestClient, CircuitBreakerFactory<?, ?> circuitBreakers) {
    this.rest = catalogRestClient;
    this.circuitBreaker = circuitBreakers.create(CatalogClientConfig.CIRCUIT_BREAKER);
  }

  /**
   * @throws CatalogUnavailableException si catalog falla, no responde a tiempo o el circuito está
   *     abierto
   */
  public Prices prices(String companyId, Collection<String> skus) {
    PricesResponse response = circuitBreaker.run(() -> fetch(companyId, skus), this::unavailable);
    if (response == null || response.items() == null || response.unknownSkus() == null) {
      throw new CatalogUnavailableException(new IllegalStateException("respuesta de catalog vacía"));
    }
    Map<String, BigDecimal> unitPrices =
        response.items().stream()
            .collect(Collectors.toMap(PriceItem::sku, item -> new BigDecimal(item.unitPrice())));
    return new Prices(response.currency(), unitPrices, response.unknownSkus());
  }

  private PricesResponse fetch(String companyId, Collection<String> skus) {
    return rest.get()
        .uri(
            uri ->
                uri.path("/internal/prices")
                    .queryParam("companyId", companyId)
                    .queryParam("skus", String.join(",", skus))
                    .build())
        .retrieve()
        .body(PricesResponse.class);
  }

  private PricesResponse unavailable(Throwable cause) {
    log.warn("catalog no disponible: {}", cause.toString());
    throw new CatalogUnavailableException(cause);
  }
}
