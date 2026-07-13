package com.bmp.common.ids;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 — time-ordered UUIDs. LOCKED DECISION for all primary keys.
 *
 * <p>Why v7 over v4: index locality. New rows land at the end of the B-tree
 * instead of random pages, which matters for booking/payment tables that are
 * written constantly and queried by recency.
 *
 * <p>Layout (RFC 9562): 48-bit unix millis | ver(7) | 12 random | var | 62 random.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long millis = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16;      // 48-bit timestamp
        msb |= 0x7000L;                                       // version 7
        msb |= (rnd[0] & 0x0FL) << 8 | (rnd[1] & 0xFFL);      // 12 random bits

        long lsb = 0x8000_0000_0000_0000L;                    // variant 10
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (rnd[i] & 0xFF);
        }
        lsb &= 0xBFFF_FFFF_FFFF_FFFFL;
        lsb |= 0x8000_0000_0000_0000L;

        return new UUID(msb, lsb);
    }
}
