package com.intentguard.api;

/**
 * Error response body returned for validation failures on the User_Profile_Api endpoints.
 * Used for missing/blank {@code userId} (Req 10.1) and invalid {@code days} window values
 * (Req 7.3).
 *
 * @param error  short machine-readable error code (e.g. {@code "MISSING_USER_ID"},
 *               {@code "INVALID_WINDOW"})
 * @param detail human-readable description including accepted values where applicable
 */
public record ProfileErrorResponse(String error, String detail) {}
