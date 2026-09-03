package com.intentguard.api;

/**
 * Thrown when a {@code GET /api/users/{userId}/profile} request arrives with a missing or
 * all-whitespace {@code userId} path variable. The controller maps this to HTTP 400 with a
 * {@link ProfileErrorResponse} body (Req 10.1).
 */
public class MissingUserIdException extends RuntimeException {

    /** Constructs the exception with a fixed diagnostic message. */
    public MissingUserIdException() {
        super("userId must not be blank");
    }
}
