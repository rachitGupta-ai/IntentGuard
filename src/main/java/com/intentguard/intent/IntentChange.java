package com.intentguard.intent;

import java.util.Objects;

/**
 * A requested modification to an open Intent_Session. For the core prototype the only mutable
 * attribute is the Declared_Intent text (an "expand" or "modify" of the goal). A change requested
 * by an Agent_Actor is always rejected (Req 13.3).
 *
 * @param newDeclaredIntent the replacement Declared_Intent text
 */
public record IntentChange(String newDeclaredIntent) {

    public IntentChange {
        Objects.requireNonNull(newDeclaredIntent, "newDeclaredIntent must not be null");
    }
}
