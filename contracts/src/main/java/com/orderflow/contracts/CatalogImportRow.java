package com.orderflow.contracts;

import java.math.BigDecimal;

public record CatalogImportRow(String sku, String name, BigDecimal basePrice, String sourceFile, int line) {}
