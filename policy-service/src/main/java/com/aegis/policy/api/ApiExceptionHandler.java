package com.aegis.policy.api;

import com.aegis.policy.domain.AmbiguousPolicyHistoryException;
import com.aegis.policy.domain.DuplicatePolicyNumberException;
import com.aegis.policy.domain.PolicyNotFoundException;
import com.aegis.policy.domain.PolicyNotInForceException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into RFC 9457 problem details.
 *
 * <p>Every response carries a stable {@code type} URI. Callers — claims-service in particular —
 * branch on that URI rather than on the status code or the message text, because both of those
 * change: several distinct failures share a status, and messages get reworded. A machine-readable
 * discriminator is the difference between claims retrying a transient fault and claims wrongly
 * denying a claim.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://aegis.example/problems/";

    @ExceptionHandler(PolicyNotFoundException.class)
    public ProblemDetail handlePolicyNotFound(PolicyNotFoundException e) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "policy-not-found", "Policy not found", e.getMessage());
        problem.setProperty("policyNumber", e.policyNumber());
        return problem;
    }

    @ExceptionHandler(NoVersionInForceException.class)
    public ProblemDetail handleNoVersionInForce(NoVersionInForceException e) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND, "no-version-in-force", "No version in force on that date", e.getMessage());
        problem.setProperty("policyNumber", e.policyNumber());
        problem.setProperty("asOf", e.asOf().toString());
        return problem;
    }

    @ExceptionHandler(DuplicatePolicyNumberException.class)
    public ProblemDetail handleDuplicate(DuplicatePolicyNumberException e) {
        ProblemDetail problem =
                problem(HttpStatus.CONFLICT, "duplicate-policy-number", "Policy number already in use", e.getMessage());
        problem.setProperty("policyNumber", e.policyNumber());
        return problem;
    }

    /**
     * 422 rather than 400: the request is syntactically valid and well-formed JSON — the server
     * understood it perfectly. It is the business rule that rejects it, and conflating the two
     * would tell a client to fix its serialisation when it should be fixing its dates.
     */
    @ExceptionHandler(PolicyNotInForceException.class)
    public ProblemDetail handleNotInForce(PolicyNotInForceException e) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "policy-not-in-force",
                "Policy not in force on that date",
                e.getMessage());
        problem.setProperty("policyNumber", e.policyNumber());
        problem.setProperty("effectiveFrom", e.date().toString());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return problem(
                HttpStatus.CONFLICT,
                "concurrent-modification",
                "Concurrent modification",
                "The policy was modified by another request while this one was in flight. "
                        + "Re-read the policy and retry; the change has NOT been applied.");
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

    /** Domain invariant rejections that were not worth a dedicated exception type. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    /**
     * A data-integrity fault, not a client error, so it is a 500 and it is logged at ERROR. The
     * message deliberately does not reach the client: it names internal version identifiers, and
     * there is nothing the caller could do with it anyway.
     */
    @ExceptionHandler(AmbiguousPolicyHistoryException.class)
    public ProblemDetail handleAmbiguousHistory(AmbiguousPolicyHistoryException e) {
        log.error("Policy history integrity violation", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ambiguous-policy-history",
                "Policy history is inconsistent",
                "The policy's version history contains overlapping periods and cannot be resolved. "
                        + "This has been logged for investigation.");
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + type));
        problem.setTitle(title);
        return problem;
    }
}
