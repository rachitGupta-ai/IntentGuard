package com.intentguard.domain;

/**
 * The kind of entity that originated a {@code CommandEvent}.
 *
 * <ul>
 *   <li>{@code HUMAN} - a human user acting directly in a shell.</li>
 *   <li>{@code AGENT} - an AI agent acting on behalf of a human principal, bound to that
 *       principal's Intent_Session envelope. An {@code AGENT} can never open, expand, or modify
 *       an Intent_Session or Declared_Intent (Req 13).</li>
 * </ul>
 */
public enum ActorType {
    HUMAN,
    AGENT
}
