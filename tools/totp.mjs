#!/usr/bin/env node
/**
 * Print the current 6-digit two-factor code for a Base32 secret. Session 61.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * The console's second factor cannot be skipped — `StaffAuthService` routes an un-enrolled account
 * into enrolment rather than past it, and that is deliberate: a 2FA bypass flag is the thing that
 * eventually ships enabled. The consequence is that a developer with no authenticator app on their
 * phone cannot sign in to their own local console at all.
 *
 * This closes that without weakening anything. It is an authenticator, not a bypass: it needs the
 * same shared secret the real app would hold, and it computes the same RFC 6238 code. If you do not
 * have the secret, this gives you nothing.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * LOCAL DEVELOPMENT ONLY
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Keeping a TOTP secret in a place a script can read it defeats the point of a *second* factor —
 * the whole idea is that the second factor lives somewhere the first one doesn't. That trade is
 * fine for a throwaway secret printed by `DevStaffSeeder` into your own terminal, and is not fine
 * for anything else.
 *
 * **Never paste a real staff member's secret into this.** If you find yourself wanting to, the
 * thing you actually want is an authenticator app.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * USAGE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *   node tools/totp.mjs JBSWY3DPEHPK3PXP          # print the code once
 *   node tools/totp.mjs JBSWY3DPEHPK3PXP --watch  # keep printing as it rolls
 *
 * It accepts the bare Base32 secret, or the whole `otpauth://` URI the seeder logs — pasting the
 * URI and having it fail on the `otpauth://` prefix would be a pointless five minutes.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * PARAMETERS MATCH TotpService — DO NOT "TIDY" THEM
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * HMAC-SHA1, 6 digits, 30-second step. Those are the near-universal defaults and the server's
 * `TotpService` uses them; changing any one here produces codes the server rejects, and the failure
 * looks like a wrong secret rather than a wrong algorithm.
 *
 * No dependencies — `node:crypto` only, so this runs in a clean checkout with nothing installed.
 */
import { createHmac } from 'node:crypto';

const DIGITS = 6;
const STEP_SECONDS = 30;
const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

/**
 * Base32 → bytes, RFC 4648 without padding.
 *
 * Accepts lowercase and ignores spaces and `=`, because a secret read off a terminal and pasted
 * back arrives with all three, and refusing it would be technically correct and useless.
 */
function base32Decode(input) {
  const clean = input.toUpperCase().replace(/[\s=]/g, '');
  let bits = 0;
  let value = 0;
  const out = [];

  for (const char of clean) {
    const index = BASE32_ALPHABET.indexOf(char);
    if (index === -1) {
      throw new Error(
        `"${char}" is not a Base32 character. A TOTP secret uses A–Z and 2–7 only — ` +
          `if yours contains 0, 1 or 8, it has probably been transcribed by eye.`,
      );
    }
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  if (out.length === 0) throw new Error('That secret decoded to nothing.');
  return Buffer.from(out);
}

/** RFC 4226 §5.3 — HMAC-SHA1 over the counter, dynamic truncation, modulo 10^digits. */
function codeForStep(key, step) {
  // 8-byte big-endian counter. Written as two 32-bit halves because a JS number cannot hold a
  // 64-bit integer exactly — `writeBigUInt64BE` would work too, but this avoids a BigInt cast on
  // a value that is only ever ~1.8 billion.
  const counter = Buffer.alloc(8);
  counter.writeUInt32BE(Math.floor(step / 2 ** 32), 0);
  counter.writeUInt32BE(step >>> 0, 4);

  const hmac = createHmac('sha1', key).update(counter).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const binary =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);

  return String(binary % 10 ** DIGITS).padStart(DIGITS, '0');
}

/** Pull the secret out of an `otpauth://` URI, or return the input unchanged. */
function extractSecret(raw) {
  if (!raw.startsWith('otpauth://')) return raw;
  const match = raw.match(/[?&]secret=([^&]+)/i);
  if (!match) throw new Error('That looks like an otpauth:// URI but has no secret= parameter.');
  return match[1];
}

function main() {
  const args = process.argv.slice(2);
  const watch = args.includes('--watch');
  const raw = args.find((a) => !a.startsWith('--'));

  if (!raw) {
    console.error(`
Print the current console 2FA code, for local development.

  node tools/totp.mjs <base32-secret-or-otpauth-uri> [--watch]

Get the secret from the bmp-admin startup log — the block headed
"DEV STAFF ACCOUNTS SEEDED" prints it, right under the shared password.

To make that block appear:
  $env:BMP_ADMIN_DEV_STAFF = "true"
  $env:BMP_ADMIN_DEV_STAFF_PASSWORD = "<at least 16 characters>"
  mvn -pl bmp-admin spring-boot:run

Local database only — the seeder refuses to run against anything but localhost.
`);
    process.exitCode = 1;
    return;
  }

  const key = base32Decode(extractSecret(raw));

  const show = () => {
    const now = Math.floor(Date.now() / 1000);
    const step = Math.floor(now / STEP_SECONDS);
    const remaining = STEP_SECONDS - (now % STEP_SECONDS);
    const code = codeForStep(key, step);

    /*
     * The countdown is the point, not decoration.
     *
     * The server accepts ±1 step, so a code is good for roughly 90 seconds — but a code shown with
     * 2 seconds left will still be typed too slowly by somebody reading it off a screen. Printing
     * the remaining seconds turns "it says my code is wrong" into "ah, wait for the next one".
     */
    const line = `  ${code}   (${remaining}s left)`;
    if (watch) process.stdout.write(`\r${line}   `);
    else console.log(`\n${line}\n`);
  };

  show();
  if (watch) {
    console.log('\n  Watching — Ctrl+C to stop.\n');
    setInterval(show, 1000);
  }
}

main();
