/**
 * Decision Engine module. Maps a Divergence_Score to an allow / ask / block Corrective_Action
 * using the ordered decision rules: tamper override, threshold map, learning clamp, agent
 * containment, and ask-timeout.
 */
package com.intentguard.decision;
