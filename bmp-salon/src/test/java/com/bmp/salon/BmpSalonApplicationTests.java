package com.bmp.salon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Minimal smoke test — confirms the Spring context loads (all beans wire up, no missing
 * config). Real unit/integration tests per endpoint belong alongside their service/controller
 * as this module's test coverage grows; this file just proves the app boots.
 */
@SpringBootTest
class BmpSalonApplicationTests {

    @Test
    void contextLoads() {
    }
}
