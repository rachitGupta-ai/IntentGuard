package com.intentguard.api;

/**
 * Response body returned when an inbound Declared_Intent submission is rejected because translation
 * to the Engine_Language failed (Req 3.3, 3.4). The {@code message} is the localized retry/English
 * prompt in the Operator's Language_Preference; no Intent_Session was opened.
 *
 * @param message the localized retry/English prompt
 */
public record RejectedSubmissionResponse(String message) {
}
