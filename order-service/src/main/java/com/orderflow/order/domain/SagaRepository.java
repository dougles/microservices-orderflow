package com.orderflow.order.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SagaRepository extends JpaRepository<Saga, UUID> {}
