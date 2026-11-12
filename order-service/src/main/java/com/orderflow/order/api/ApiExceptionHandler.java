package com.orderflow.order.api;

import com.orderflow.order.catalog.CatalogUnavailableException;
import com.orderflow.order.domain.IdempotencyKeyReusedException;
import com.orderflow.order.domain.InvalidOrderException;
import com.orderflow.order.domain.OrderNotFoundException;
import com.orderflow.order.domain.UnknownSkuException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errores con {@code code} de SPEC §5.4. */
@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler({
    InvalidOrderException.class,
    HttpMessageNotReadableException.class,
    HandlerMethodValidationException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail invalidRequest() {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
  }

  @ExceptionHandler(MissingIdempotencyKeyException.class)
  ProblemDetail missingIdempotencyKey() {
    return problem(HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");
  }

  @ExceptionHandler(OrderNotFoundException.class)
  ProblemDetail notFound() {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ProblemDetail idempotencyKeyReused() {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED");
  }

  @ExceptionHandler(UnknownSkuException.class)
  ProblemDetail unknownSku(UnknownSkuException e) {
    var problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_SKU");
    problem.setProperty("skus", e.skus());
    return problem;
  }

  @ExceptionHandler(CatalogUnavailableException.class)
  ProblemDetail catalogUnavailable() {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE");
  }

  private static ProblemDetail problem(HttpStatus status, String code) {
    var problem = ProblemDetail.forStatus(status);
    problem.setProperty("code", code);
    return problem;
  }
}
