package com.orderflow.contracts;

public final class Topics {

  public static final String DLT_SUFFIX = ".DLT";

  public static final String INVENTORY_COMMANDS = "inventory.commands";
  public static final String INVENTORY_REPLIES = "inventory.replies";
  public static final String CREDIT_COMMANDS = "credit.commands";
  public static final String CREDIT_REPLIES = "credit.replies";
  public static final String SHIPPING_COMMANDS = "shipping.commands";
  public static final String SHIPPING_REPLIES = "shipping.replies";

  public static final String INVENTORY_COMMANDS_DLT = INVENTORY_COMMANDS + DLT_SUFFIX;
  public static final String INVENTORY_REPLIES_DLT = INVENTORY_REPLIES + DLT_SUFFIX;
  public static final String CREDIT_COMMANDS_DLT = CREDIT_COMMANDS + DLT_SUFFIX;
  public static final String CREDIT_REPLIES_DLT = CREDIT_REPLIES + DLT_SUFFIX;
  public static final String SHIPPING_COMMANDS_DLT = SHIPPING_COMMANDS + DLT_SUFFIX;
  public static final String SHIPPING_REPLIES_DLT = SHIPPING_REPLIES + DLT_SUFFIX;

  private Topics() {}
}
