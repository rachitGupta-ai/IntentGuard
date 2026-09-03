package com.intentguard.api;

/**
 * Thrown when the {@code days} query parameter on {@code GET /api/users/{userId}/profile} is
 * outside the accepted range [1, 365]. The controller maps this to HTTP 400 with a
 * {@link ProfileErrorResponse} body stating the accepted range (Req 7.3).
 */
public class InvalidWindowException extends RuntimeException {

    /** The invalid value that was supplied. */
    private final int invalidDays;

    /**
     * Constructs the exception recording the rejected value.
     *
     * @param invalidDays the {@code days} value that was out of range (Req 7.3)
     */
    public InvalidWindowException(int invalidDays) {
        super("days=" + invalidDays + " is out of the accepted range [1, 365]");
        this.invalidDays = invalidDays;
    }

    /**
     * Returns the invalid {@code days} value that triggered this exception.
     *
     * @return the rejected days value
     */
    public int getInvalidDays() {
        return invalidDays;
    }
}
