package com.orderflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "product")
public class Product {

  @Id private String sku;

  @Column(nullable = false)
  private String name;

  @Column(name = "base_price", nullable = false)
  private BigDecimal basePrice;

  @Column(nullable = false)
  private boolean active;

  protected Product() {}

  public String getSku() {
    return sku;
  }

  public String getName() {
    return name;
  }

  public BigDecimal getBasePrice() {
    return basePrice;
  }

  public boolean isActive() {
    return active;
  }
}
