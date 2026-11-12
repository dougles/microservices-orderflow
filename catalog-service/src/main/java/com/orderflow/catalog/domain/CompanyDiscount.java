package com.orderflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "company_discount")
public class CompanyDiscount {

  @Id
  @Column(name = "company_id")
  private String companyId;

  @Column(name = "discount_pct", nullable = false)
  private BigDecimal discountPct;

  protected CompanyDiscount() {}

  public BigDecimal getDiscountPct() {
    return discountPct;
  }
}
