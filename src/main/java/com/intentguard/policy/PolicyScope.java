package com.intentguard.policy;

import java.util.Objects;

import com.intentguard.domain.ActorType;
import com.intentguard.domain.CommandEvent;

/**
 * Optional qualifier restricting a {@link PolicyRule} to a subset of Command_Events (Req 2.5).
 *
 * <p>Every facet is nullable; a {@code null} facet means "any" and is not considered when
 * matching. A scope matches a Command_Event only when <em>every</em> non-null facet equals the
 * event's corresponding facet:
 *
 * <ul>
 *   <li>{@code user} is compared to the event's {@link CommandEvent#userId() userId}.</li>
 *   <li>{@code group} is compared to the group the event is evaluated under (supplied by the
 *       caller, since group membership is not carried on the event itself).</li>
 *   <li>{@code repo} is compared to the event's {@link CommandEvent#repo() repo} <em>or</em> its
 *       {@link CommandEvent#cwd() cwd}, matching either.</li>
 *   <li>{@code actorType} is compared to the event's {@link CommandEvent#actorType() actorType}.</li>
 * </ul>
 *
 * @param user      the user the rule is restricted to, or {@code null} for any user
 * @param group     the group the rule is restricted to, or {@code null} for any group
 * @param repo      the repository or working directory the rule is restricted to, or {@code null}
 *                  for any; matches the event's repo or cwd
 * @param actorType the actor type the rule is restricted to, or {@code null} for any
 */
public record PolicyScope(String user, String group, String repo, ActorType actorType) {

    /** An unrestricted scope that matches every Command_Event. */
    public static PolicyScope any() {
        return new PolicyScope(null, null, null, null);
    }

    /**
     * Returns whether this scope applies to {@code event} when evaluated under {@code group}.
     * All non-null facets must match; a scope with no facets ({@link #any()}) matches everything.
     *
     * @param event the Command_Event being evaluated (never {@code null})
     * @param group the group the event is evaluated under, or {@code null} if unknown
     */
    public boolean matches(CommandEvent event, String group) {
        Objects.requireNonNull(event, "event must not be null");
        if (user != null && !user.equals(event.userId())) {
            return false;
        }
        if (this.group != null && !this.group.equals(group)) {
            return false;
        }
        if (repo != null && !repo.equals(event.repo()) && !repo.equals(event.cwd())) {
            return false;
        }
        if (actorType != null && actorType != event.actorType()) {
            return false;
        }
        return true;
    }
}
