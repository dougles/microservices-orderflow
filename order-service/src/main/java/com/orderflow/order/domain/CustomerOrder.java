package com.orderflow.order.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class CustomerOrder {

  @Id private UUID id;

  @Column(name = "company_id", nullable = false)
  private String companyId;

  @Column(name = "delivery_date", nullable = false)
  private LocalDate deliveryDate;

  @Column(nullable = false)
  private String currency;

  @Column(nullable = false)
  private BigDecimal total;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ElementCollection
  @CollectionTable(name = "order_line", joinColumns = @JoinColumn(name = "order_id"))
  private List<OrderLine> lines = new ArrayList<>();

  protected CustomerOrder() {}

  public CustomerOrder(
      UUID id,
      String companyId,
      LocalDate deliveryDate,
      String currency,
      List<OrderLine> lines,
      Instant createdAt) {
    this.id = id;
    this.companyId = companyId;
    this.deliveryDate = deliveryDate;
    this.currency = currency;
    this.lines = new ArrayList<>(lines);
    this.total = Money.total(lines);
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCompanyId() {
    return companyId;
  }

  public LocalDate getDeliveryDate() {
    return deliveryDate;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public List<OrderLine> getLines() {
    return List.copyOf(lines);
  }
}
