package com.orderflow.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DltReplayerTest {

  @Test
  void elLimiteRespetaElMaximoDeSpec() {
    assertThat(DltReplayer.effectiveLimit(100)).isEqualTo(100);
    assertThat(DltReplayer.effectiveLimit(1000)).isEqualTo(1000);
    assertThat(DltReplayer.effectiveLimit(1001)).isEqualTo(1000);
  }

  @Test
  void unLimiteNoPositivoNoReinyectaNada() {
    assertThat(DltReplayer.effectiveLimit(0)).isZero();
    assertThat(DltReplayer.effectiveLimit(-5)).isZero();
  }
}
