package com.orderflow.catalog.api;

import com.orderflow.catalog.domain.CatalogService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalPricesController {

  private static final String CURRENCY = "BOB";

  record PriceItem(String sku, String unitPrice) {}

  record PricesResponse(String currency, List<PriceItem> items, List<String> unknownSkus) {}

  private final CatalogService catalog;

  InternalPricesController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/internal/prices")
  PricesResponse prices(
      @RequestParam("companyId") @NotBlank String companyId,
      @RequestParam("skus") @NotEmpty List<String> skus) {
    var prices = catalog.prices(companyId, skus);
    var items =
        prices.items().stream()
            .map(item -> new PriceItem(item.sku(), item.unitPrice().toPlainString()))
            .toList();
    return new PricesResponse(CURRENCY, items, prices.unknownSkus());
  }
}
