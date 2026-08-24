/**
 * Self-Protection / Monitoring-Gap Watchdog module. Rejects unprivileged stop/pause/reconfigure
 * attempts, force-blocks requests targeting engine config/process/Datastore, and raises
 * monitoring-gap and monitoring-resumed alerts based on Audit_Feed liveness.
 */
package com.intentguard.watchdog;
