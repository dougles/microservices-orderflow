package com.orderflow.order.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SagaRepository extends JpaRepository<Saga, UUID> {

  /** {@code SELECT … FOR UPDATE} (SPEC §8.2). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from Saga s where s.orderId = :orderId")
  Optional<Saga> lockById(@Param("orderId") UUID orderId);
}
