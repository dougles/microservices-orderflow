package com.orderflow.messaging.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/** Guarda el contexto de traza en la fila y lo reabre al publicar (SPEC §13). */
class OutboxTracing {

  private static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  OutboxTracing(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  String currentTraceparent() {
    var context = tracer.currentTraceContext().context();
    if (context == null) {
      return null;
    }
    var carrier = new HashMap<String, String>();
    propagator.inject(context, carrier, Map::put);
    return carrier.get(TRACEPARENT);
  }

  void runInTrace(String traceparent, PublishAction action) throws Exception {
    if (traceparent == null) {
      action.run();
      return;
    }
    Span span =
        propagator
            .extract(Map.of(TRACEPARENT, traceparent), Map::get)
            .name("outbox publish")
            .kind(Span.Kind.PRODUCER)
            .start();
    try (var scope = tracer.withSpan(span)) {
      action.run();
    } catch (Exception e) {
      span.error(e);
      throw e;
    } finally {
      span.end();
    }
  }

  @FunctionalInterface
  interface PublishAction {
    void run() throws Exception;
  }
}
