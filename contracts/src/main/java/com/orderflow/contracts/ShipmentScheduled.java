package com.orderflow.contracts;

import java.util.UUID;

public record ShipmentScheduled(UUID orderId, UUID shipmentId) {}
