/**
 * Control_Tower API module. Exposes REST endpoints for history queries, threshold updates, and
 * ask-state resolution, plus a WebSocket/SSE channel that pushes live session, score, and alert
 * events to the web dashboard within the 3-second latency budget.
 */
package com.intentguard.api;
