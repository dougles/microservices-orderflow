package com.orderflow.contracts;

import java.time.LocalDate;
import java.util.UUID;

public record ScheduleShipment(UUID orderId, String companyId, LocalDate deliveryDate) {}
