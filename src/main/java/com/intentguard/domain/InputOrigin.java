package com.intentguard.domain;

/**
 * How the command text of a {@code CommandEvent} entered the shell.
 *
 * <ul>
 *   <li>{@code TYPED} - the user typed the command interactively.</li>
 *   <li>{@code PASTED} - the command was pasted (higher risk; see Req 9).</li>
 *   <li>{@code UNKNOWN} - no typed-vs-pasted indicator was provided; processing continues
 *       with this value recorded (Req 2.4).</li>
 * </ul>
 */
public enum InputOrigin {
    TYPED,
    PASTED,
    UNKNOWN
}
