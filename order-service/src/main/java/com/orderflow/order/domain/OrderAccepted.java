package com.orderflow.order.domain;

import java.util.UUID;

/** Respuesta 202 de {@code POST /api/orders}; es lo que se guarda en el registro de idempotencia. */
public record OrderAccepted(UUID orderId, String status) {}
