package com.intentguard.domain;

/**
 * The maturity state of a user's Behavioral_Profile (Req 3.3, 3.4).
 *
 * <ul>
 *   <li>{@code LEARNING} - the profile holds fewer than the configured minimum number of
 *       Command_Events; while learning, any {@code BLOCK} is downgraded to {@code ASK}.</li>
 *   <li>{@code ACTIVE} - the profile has met the minimum and scores normally.</li>
 * </ul>
 */
public enum ProfileState {
    LEARNING,
    ACTIVE
}
