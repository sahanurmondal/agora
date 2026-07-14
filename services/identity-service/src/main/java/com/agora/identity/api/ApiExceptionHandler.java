package com.agora.identity.api;

import com.agora.identity.user.DuplicateUsernameException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Uniform JSON error model: {"error": "..."} with a meaningful status code. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String error) {
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError duplicateUsername(DuplicateUsernameException e) {
        return new ApiError(e.getMessage());
    }

    /** Concurrent-registration race: the unique constraint fires at commit time. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError integrityViolation(DataIntegrityViolationException e) {
        return new ApiError("username already taken");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError invalidCredentials(InvalidCredentialsException e) {
        return new ApiError(e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError badRequest(Exception e) {
        return new ApiError(e instanceof IllegalArgumentException ? e.getMessage() : "malformed request body");
    }
}
