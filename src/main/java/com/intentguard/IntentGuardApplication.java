package com.intentguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the IntentGuard Enforcement_Engine.
 *
 * <p>IntentGuard is a semantic firewall for the Linux command layer. It runs as a single
 * always-on reference-monitor process (a modular monolith) that ingests command signals,
 * scores each action for divergence from declared intent and learned behavior, applies an
 * allow / ask / block Corrective_Action, persists everything to MongoDB, and pushes live
 * updates to the web Control_Tower.
 */
@SpringBootApplication
@EnableScheduling
public class IntentGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntentGuardApplication.class, args);
    }
}
