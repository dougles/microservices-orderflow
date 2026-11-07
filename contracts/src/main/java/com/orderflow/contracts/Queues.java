package com.orderflow.contracts;

public final class Queues {

  public static final String CATALOG_IMPORT = "catalog-import";
  public static final String CATALOG_IMPORT_DLQ = "catalog-import-dlq";
  public static final String INVOICE_JOBS = "invoice-jobs";
  public static final String INVOICE_JOBS_DLQ = "invoice-jobs-dlq";

  private Queues() {}
}
