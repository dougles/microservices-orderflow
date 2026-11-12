package com.orderflow.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saga")
public class Saga {

  @Id
  @Column(name = "order_id")
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SagaState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_reason")
  private FailureReason failureReason;

  @Column(name = "step_deadline")
  private Instant stepDeadline;

  @Column(name = "stock_reserved", nullable = false)
  private boolean stockReserved;

  @Column(name = "credit_reserved", nullable = false)
  private boolean creditReserved;

  @Column(name = "stock_release_pending", nullable = false)
  private boolean stockReleasePending;

  @Column(name = "credit_release_pending", nullable = false)
  private boolean creditReleasePending;

  @Column(name = "shipment_cancel_pending", nullable = false)
  private boolean shipmentCancelPending;

  @Column(name = "compensation_attempts", nullable = false)
  private int compensationAttempts;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Saga() {}

  private Saga(UUID orderId, SagaState state, Instant stepDeadline, Instant now) {
    this.orderId = orderId;
    this.state = state;
    this.stepDeadline = stepDeadline;
    this.updatedAt = now;
  }

  /** Creación desde {@code POST /api/orders}: primer paso, a la espera del stock (SPEC §8.3). */
  public static Saga awaitingStock(UUID orderId, Instant now, Duration stepTimeout) {
    return new Saga(orderId, SagaState.AWAITING_STOCK, now.plus(stepTimeout), now);
  }

  public UUID getOrderId() {
    return orderId;
  }

  public SagaState getState() {
    return state;
  }

  public FailureReason getFailureReason() {
    return failureReason;
  }

  public Instant getStepDeadline() {
    return stepDeadline;
  }

  public boolean isStockReserved() {
    return stockReserved;
  }

  public boolean isCreditReserved() {
    return creditReserved;
  }

  public boolean isStockReleasePending() {
    return stockReleasePending;
  }

  public boolean isCreditReleasePending() {
    return creditReleasePending;
  }

  public boolean isShipmentCancelPending() {
    return shipmentCancelPending;
  }

  public boolean hasPendingReleases() {
    return stockReleasePending || creditReleasePending || shipmentCancelPending;
  }

  public int getCompensationAttempts() {
    return compensationAttempts;
  }

  void moveTo(SagaState next, Instant deadline, Instant now) {
    this.state = next;
    this.stepDeadline = deadline;
    this.updatedAt = now;
  }

  void markStockReserved() {
    this.stockReserved = true;
  }

  void markCreditReserved() {
    this.creditReserved = true;
  }

  void setStockReleasePending(boolean pending) {
    this.stockReleasePending = pending;
  }

  void setCreditReleasePending(boolean pending) {
    this.creditReleasePending = pending;
  }

  void setShipmentCancelPending(boolean pending) {
    this.shipmentCancelPending = pending;
  }

  void setFailureReason(FailureReason reason) {
    this.failureReason = reason;
  }

  void incrementCompensationAttempts() {
    this.compensationAttempts++;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
