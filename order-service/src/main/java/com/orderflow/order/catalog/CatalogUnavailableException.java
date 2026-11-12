package com.orderflow.order.catalog;

public class CatalogUnavailableException extends RuntimeException {

  public CatalogUnavailableException(Throwable cause) {
    super("catalog no disponible", cause);
  }
}
