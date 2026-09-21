package com.example.defusion_bcp.controller;

import com.example.defusion_bcp.service.CryptoOperationException;
import com.example.defusion_bcp.service.SapServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse badCredentials() {
        return new ErrorResponse("AUTH_INVALID", "Usuario o contraseña SAP incorrectos", LocalDateTime.now(), Map.of());
    }

    @ExceptionHandler(AuthenticationServiceException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ErrorResponse sapUnavailable() {
        return new ErrorResponse(
            "SAP_UNAVAILABLE",
            "No fue posible conectar con SAP. Intente nuevamente.",
            LocalDateTime.now(),
            Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return new ErrorResponse("VALIDATION_ERROR", "Revise los datos enviados", LocalDateTime.now(), fields);
    }

    @ExceptionHandler(CryptoOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse crypto(CryptoOperationException exception) {
        return new ErrorResponse("CRYPTO_ERROR", exception.getMessage(), LocalDateTime.now(), Map.of());
    }

    @ExceptionHandler(SapServiceException.class)
    public ResponseEntity<ErrorResponse> sapService(SapServiceException exception) {
        ErrorResponse response = new ErrorResponse(
            exception.getCode(),
            exception.getMessage(),
            LocalDateTime.now(),
            Map.of()
        );
        return ResponseEntity.status(exception.getStatus()).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(IllegalArgumentException exception) {
        return new ErrorResponse("NOT_FOUND", exception.getMessage(), LocalDateTime.now(), Map.of());
    }

    public record ErrorResponse(
        String code,
        String message,
        LocalDateTime timestamp,
        Map<String, String> fields
    ) {}
}
