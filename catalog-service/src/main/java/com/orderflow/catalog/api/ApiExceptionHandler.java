package com.orderflow.catalog.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ProblemDetail invalidRequest() {
    var problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setProperty("code", "VALIDATION_ERROR");
    return problem;
  }
}
