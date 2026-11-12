package com.orderflow.catalog.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

  public record PricedProduct(String sku, String name, BigDecimal unitPrice) {}

  public record SkuPrice(String sku, BigDecimal unitPrice) {}

  public record PriceList(List<SkuPrice> items, List<String> unknownSkus) {}

  private final ProductRepository products;
  private final CompanyDiscountRepository discounts;

  public CatalogService(ProductRepository products, CompanyDiscountRepository discounts) {
    this.products = products;
    this.discounts = discounts;
  }

  /** Sin empresa (ops) devuelve el precio base. */
  @Transactional(readOnly = true)
  public Page<PricedProduct> activeProducts(Optional<String> companyId, int page, int size) {
    BigDecimal discountPct = companyId.map(this::discountPct).orElse(BigDecimal.ZERO);
    return products
        .findByActiveTrue(PageRequest.of(page, size, Sort.by("sku")))
        .map(
            product ->
                new PricedProduct(
                    product.getSku(),
                    product.getName(),
                    Pricing.unitPrice(product.getBasePrice(), discountPct)));
  }

  /** Un sku inactivo se informa como desconocido: el pedido responde UNKNOWN_SKU (SPEC §5.2). */
  @Transactional(readOnly = true)
  public PriceList prices(String companyId, List<String> skus) {
    BigDecimal discountPct = discountPct(companyId);
    var requested = new LinkedHashSet<>(skus);
    Map<String, Product> found =
        products.findBySkuInAndActiveTrue(requested).stream()
            .collect(Collectors.toMap(Product::getSku, Function.identity()));

    var items = new ArrayList<SkuPrice>();
    var unknown = new ArrayList<String>();
    for (String sku : requested) {
      Product product = found.get(sku);
      if (product == null) {
        unknown.add(sku);
      } else {
        items.add(new SkuPrice(sku, Pricing.unitPrice(product.getBasePrice(), discountPct)));
      }
    }
    return new PriceList(items, unknown);
  }

  private BigDecimal discountPct(String companyId) {
    return discounts
        .findById(companyId)
        .map(CompanyDiscount::getDiscountPct)
        .orElse(BigDecimal.ZERO);
  }
}
