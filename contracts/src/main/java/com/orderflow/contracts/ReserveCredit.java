package com.orderflow.contracts;

import java.math.BigDecimal;
import java.util.UUID;

public record ReserveCredit(UUID orderId, String companyId, BigDecimal amount, String currency) {}
