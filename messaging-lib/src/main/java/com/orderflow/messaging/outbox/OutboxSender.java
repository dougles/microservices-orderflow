package com.orderflow.messaging.outbox;

/** Envío bloqueante a un destino: al volver sin excepción, el mensaje quedó entregado. */
interface OutboxSender {

  String destinationType();

  void send(OutboxRow row) throws Exception;
}
