package com.orderflow.catalog.api;

import com.orderflow.catalog.config.CurrentCompany;
import com.orderflow.catalog.domain.CatalogService;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

  record ProductResponse(String sku, String name, String unitPrice) {}

  record ProductPage(List<ProductResponse> content, int page, int size, long totalElements) {}

  private final CatalogService catalog;
  private final CurrentCompany currentCompany;

  CatalogController(CatalogService catalog, CurrentCompany currentCompany) {
    this.catalog = catalog;
    this.currentCompany = currentCompany;
  }

  @GetMapping("/api/catalog/products")
  ProductPage products(
      @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
      @RequestParam(name = "size", defaultValue = "20") @Min(1) int size) {
    var result = catalog.activeProducts(currentCompany.id(), page, size);
    var content =
        result.getContent().stream()
            .map(
                product ->
                    new ProductResponse(
                        product.sku(), product.name(), product.unitPrice().toPlainString()))
            .toList();
    return new ProductPage(content, page, size, result.getTotalElements());
  }
}
