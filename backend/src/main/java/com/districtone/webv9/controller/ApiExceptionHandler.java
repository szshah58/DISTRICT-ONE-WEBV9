package com.districtone.webv9.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException exception) {
    String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
        .map(error -> error.getField() + ": " + error.getDefaultMessage()).orElse("Validation failed.");
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }
  @ExceptionHandler(java.util.NoSuchElementException.class)
  ResponseEntity<?> notFound() { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "The requested record was not found.")); }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException exception) {
    return ResponseEntity.status(exception.getStatusCode())
        .body(Map.of("message", exception.getReason() == null ? "The request could not be completed." : exception.getReason()));
  }
}
