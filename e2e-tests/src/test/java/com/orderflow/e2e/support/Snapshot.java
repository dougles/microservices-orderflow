package com.orderflow.e2e.support;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stock de unos SKU y crédito usado de una empresa: para verificar "sin cambios" por deltas. */
public record Snapshot(Map<String, Long> stock, BigDecimal used) {

  public static Snapshot of(String companyId, List<String> skus) {
    var stock = new LinkedHashMap<String, Long>();
    for (String sku : skus) {
      stock.put(
          sku,
          Db.count(Db.Database.INVENTORY, "SELECT available FROM stock WHERE sku = ?", sku));
    }
    Object used =
        Db.scalar(
            Db.Database.CREDIT, "SELECT used FROM credit_line WHERE company_id = ?", companyId);
    return new Snapshot(stock, (BigDecimal) used);
  }
}
