package com.intentguard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test verifying the Spring Boot application context loads with the scaffolded
 * configuration. This confirms the dependency set and module structure are wired correctly.
 */
@SpringBootTest
class IntentGuardApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: a successful context load is the assertion.
    }
}
