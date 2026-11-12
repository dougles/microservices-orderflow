package com.orderflow.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxTracingTest {

  private static final String TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  private final Tracer tracer = mock(Tracer.class);
  private final Propagator propagator = mock(Propagator.class);
  private final CurrentTraceContext currentContext = mock(CurrentTraceContext.class);
  private final OutboxTracing tracing = new OutboxTracing(tracer, propagator);

  @BeforeEach
  void setUp() {
    when(tracer.currentTraceContext()).thenReturn(currentContext);
  }

  @Test
  void sinSpanActivoNoHayTraceparent() {
    assertThat(tracing.currentTraceparent()).isNull();
    verifyNoInteractions(propagator);
  }

  @Test
  void conSpanActivoDevuelveElTraceparentInyectado() {
    var context = mock(TraceContext.class);
    when(currentContext.context()).thenReturn(context);
    doAnswer(
            call -> {
              Propagator.Setter<Object> setter = call.getArgument(2);
              setter.set(call.getArgument(1), "traceparent", TRACEPARENT);
              return null;
            })
        .when(propagator)
        .inject(any(), any(), any());

    assertThat(tracing.currentTraceparent()).isEqualTo(TRACEPARENT);
  }

  @Test
  void sinTraceparentEjecutaSinAbrirSpan() throws Exception {
    var ran = new AtomicBoolean();

    tracing.runInTrace(null, () -> ran.set(true));

    assertThat(ran).isTrue();
    verifyNoInteractions(propagator);
  }

  @Test
  void conTraceparentEjecutaDentroDeUnSpanHijoYLoCierra() throws Exception {
    var span = stubExtractedSpan();
    var scope = mock(Tracer.SpanInScope.class);
    when(tracer.withSpan(span)).thenReturn(scope);
    var ran = new AtomicBoolean();

    tracing.runInTrace(TRACEPARENT, () -> ran.set(true));

    assertThat(ran).isTrue();
    var order = inOrder(tracer, scope, span);
    order.verify(tracer).withSpan(span);
    order.verify(scope).close();
    order.verify(span).end();
  }

  @Test
  void siFallaElEnvioMarcaElSpanYPropagaLaExcepcion() {
    var span = stubExtractedSpan();
    when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
    var failure = new IllegalStateException("kafka caído");

    assertThatThrownBy(
            () ->
                tracing.runInTrace(
                    TRACEPARENT,
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    verify(span).error(failure);
    verify(span).end();
  }

  private Span stubExtractedSpan() {
    var span = mock(Span.class);
    var builder = mock(Span.Builder.class, RETURNS_SELF);
    when(builder.start()).thenReturn(span);
    when(propagator.extract(any(), any())).thenReturn(builder);
    return span;
  }
}
