package com.orderflow.order.domain;

import java.util.List;

public class UnknownSkuException extends RuntimeException {

  private final List<String> skus;

  public UnknownSkuException(List<String> skus) {
    super("skus desconocidos o inactivos: " + skus);
    this.skus = List.copyOf(skus);
  }

  public List<String> skus() {
    return skus;
  }
}
