package com.aegis.rating.api;

import com.aegis.rating.application.QuoteNotFoundException;
import com.aegis.rating.domain.UnratableRiskException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem details with stable {@code type} URIs, so callers branch on those, not on text. */
@RestControllerAdvice
public class RatingExceptionHandler {

    private static final String PROBLEM_BASE = "https://aegis.example/problems/";

    @ExceptionHandler(QuoteNotFoundException.class)
    public ProblemDetail handleQuoteNotFound(QuoteNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "quote-not-found", "Quote not found", e.getMessage());
        problem.setProperty("quoteNumber", e.quoteNumber());
        return problem;
    }

    /**
     * 422, not 400. The request was well formed and understood; the risk simply cannot be priced
     * with the rates on file. A client that gets a 400 goes looking for a serialisation bug it
     * does not have.
     */
    @ExceptionHandler(UnratableRiskException.class)
    public ProblemDetail handleUnratable(UnratableRiskException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unratable-risk", "Risk cannot be rated", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "validation-failed",
                "Request validation failed",
                "One or more fields are invalid.");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
