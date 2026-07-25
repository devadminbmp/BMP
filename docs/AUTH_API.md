# BMP Auth API — Frontend Contract

Everything the frontend needs to build login, signup and session handling for all four
roles: **customer, salon owner, salon manager, stylist**. This is the single reference —
the live Swagger UI (`http://localhost:8081/swagger-ui/index.html`) mirrors it.

- **Base URL (via gateway):** `http://localhost:8080` in local dev → routes `/api/v1/auth/**`
  to bmp-auth. You can also hit bmp-auth directly on `http://localhost:8081`.
- **Auth model:** OTP-only (phone + 6-digit code). There is **no password** anywhere —
  "forgot password" doesn't exist because there's nothing to forget; re-logging in (request
  a fresh OTP, verify it) IS account recovery.
- **Tokens:** a short-lived **access token** (JWT, ~15 min) sent as
  `Authorization: Bearer <accessToken>` on every authenticated call, and a long-lived opaque
  **refresh token** (~30 days, `selector.verifier` format) exchanged for new access tokens.
- **Local dev shortcut:** the OTP `000000` **always works** for any phone (see §7). You
  still must call `/otp/request` first so a code record exists.

---

## 1. The three core endpoints (every role uses these)

### `POST /api/v1/auth/otp/request` — send a code

```jsonc
// request
{ "phone": "+919876543210", "email": "you@example.com" }
```
- `phone` — **required**, E.164 (`+` country code, 8–15 digits).
- `email` — **required only the first time a phone is seen** (signup). For an existing user
  it's ignored and their stored email is reused.

```jsonc
// 200 response
{
  "otpRequestId": "0193...",          // opaque, you don't need it to verify
  "expiresAt": "2026-07-25T10:05:00Z", // code stops working at this time (~5 min)
  "resendAvailableAt": "2026-07-25T10:00:55Z" // earliest you may request again (~55s)
}
```
Drive a "code expires in / resend in N s" countdown off `expiresAt` and `resendAvailableAt`.

Errors: `400` email required (first-time phone with no email), `429` requested again before
`resendAvailableAt`.

### `POST /api/v1/auth/otp/verify` — log in OR sign up

The **same** endpoint logs in an existing phone or signs up a new one. Extra fields
(`role`, `name`, `inviteToken`, `email`) only matter for a **brand-new** phone (signup);
they're ignored when logging into an existing account.

```jsonc
// request (fields beyond phone+otp are signup-only)
{
  "phone": "+919876543210",
  "otp": "000000",
  "deviceFingerprint": "optional-device-id",  // optional, for per-device refresh tokens
  "email": "you@example.com",  // signup only (required for new phone)
  "role": "customer",          // signup only: customer | salon_owner | manager | stylist
  "name": "Ravi Kumar",        // signup only: used by stylist for their profile
  "inviteToken": "…",          // signup only: REQUIRED for manager (from salon owner)
  "googleSubject": "…"         // signup only: link a Google account (see §4)
}
```

```jsonc
// 200 response  (Session 14: role/salonId/isNewUser added)
{
  "userId": "0193...",
  "role": "customer",          // ← route your UI off this
  "salonId": null,             // owner/manager only; null for customer & stylist
  "isNewUser": true,           // true = just signed up → onboarding; false = login → home
  "refreshToken": "sel.ver",
  "accessToken": "eyJhbGciOi...",
  "expiresIn": 900
}
```

Errors: `400` incorrect OTP (message says attempts remaining), `410` code expired,
`423` locked (too many wrong attempts — 5 tries → 15-min lock), `400` unknown role /
manager missing inviteToken.

### `GET /api/v1/auth/me` — restore session on app startup

Requires `Authorization: Bearer <accessToken>`. Call it when the app launches with a stored
token, to learn who the user is and which UI to show — one call, no JWT decoding.

```jsonc
// 200 response
{
  "userId": "0193...",
  "phone": "+919876543210",
  "name": "Ravi Kumar",
  "email": "you@example.com",
  "role": "customer",
  "salonId": null,
  "isVerified": true
}
```
`401` if the token is missing/expired/invalid. Need the fuller profile (gender, age, photo,
hair type/length)? Call `GET /api/v1/users/{userId}`.

---

## 2. Session lifecycle (refresh & logout)

### `POST /api/v1/auth/refresh`
```jsonc
// request
{ "refreshToken": "sel.ver" }
// 200 response  (Session 14: role/salonId added)
{ "accessToken": "eyJ...", "expiresIn": 900, "role": "salon_owner", "salonId": "0193..." }
```
When the access token expires (or `401`s), exchange the refresh token for a new one. The
refresh token itself is **not** rotated — keep using it until it expires. **Re-read
`role`/`salonId` from this response** — they self-correct a stale session (e.g. a salon
owner who just created their first salon now gets their real `salonId` here). `401` if the
refresh token is expired/revoked/unknown → send the user back to login.

### `POST /api/v1/auth/logout`
```jsonc
{ "refreshToken": "sel.ver" }   // → 204 No Content
```
Revokes the refresh token. Already-issued access tokens aren't invalidated — they just
expire on their own ~15-min TTL, so also drop the token client-side.

---

## 3. Per-role signup & login journeys

### Customer
1. `POST /otp/request` `{ phone, email }`
2. `POST /otp/verify` `{ phone, otp }` (role defaults to `customer`)
3. Response `isNewUser=true` → send into onboarding; `false` → home. Done.

Optional Google sign-in — see §4.

### Salon owner
1. Signup: `POST /otp/verify` `{ phone, otp, email, role: "salon_owner" }` → token, `salonId: null`.
2. Create the salon (authenticated): `POST /api/v1/salons` with the access token — the
   creator automatically becomes the salon's OWNER.
3. `POST /api/v1/auth/refresh` → the response now carries the real `salonId`. (Or the next
   natural token refresh picks it up.)

### Salon manager
1. The **owner** first generates an invite for the manager's phone (authenticated, owner's
   token): `POST /api/v1/salons/{salonId}/invites` `{ phone }` → returns
   `{ token, expiresAt, ... }`. The owner shares that `token` with the manager.
2. Manager signup: `POST /otp/verify` `{ phone, otp, email, role: "manager", inviteToken: "<token>" }`.
   The response carries the `salonId` they were invited to.
   - `400` if `inviteToken` is missing/blank; the invite is validated against the manager's phone.

### Stylist
1. Signup: `POST /otp/verify` `{ phone, otp, email, role: "stylist", name: "Ravi Kumar" }`.
   A portable Stylist profile is created. `salonId` is **null** by design — a stylist's
   identity/reviews follow them across salons (the portable-identity model), they aren't
   scoped to one salon in the token. Salon linkage is a separate step
   (`POST /api/v1/salons/{salonId}/stylists`).

---

## 4. Google sign-in (customers) — `POST /api/v1/auth/oauth2/google`

```jsonc
{ "idToken": "<google-id-token-from-the-client-sdk>", "deviceFingerprint": "optional" }
```
The client obtains the Google ID token via the Google Sign-In SDK; this endpoint verifies
it server-side. Two outcomes:

- **Already linked** → `{ "linked": true, userId, role, salonId, refreshToken, accessToken, expiresIn, email, googleSubject }` — treat exactly like an OTP login.
- **First time seen** → `{ "linked": false, userId: null, role: null, salonId: null, refreshToken: null, accessToken: null, expiresIn: 0, email, googleSubject }`.
  Google gives no phone number and BMP requires one, so **no account is created yet**.
  Collect a phone, run the normal `/otp/request` + `/otp/verify` signup, and pass the
  returned `email` and `googleSubject` through on `/otp/verify` to link the two.

> ⚠️ Returns **501 Not Implemented** until `BMP_GOOGLE_CLIENT_ID` is configured (no Google
> Cloud OAuth client exists yet). Safe to defer Google in the frontend for now.

---

## 5. Using the access token

Every authenticated request across all services:
```
Authorization: Bearer <accessToken>
```
In **Swagger UI**, click **Authorize** 🔒 (top-right), paste the raw access token (no
`Bearer ` prefix — Swagger adds it), then "Try it out".

---

## 6. Error shape

All auth errors come back as:
```jsonc
{ "error": "SHORT_CODE_OR_REASON", "message": "human-readable detail" }
```
with the HTTP status carrying the real meaning (`400/401/410/423/429/501`).

---

## 7. Local dev notes

- **`otp: "000000"` always verifies**, for any phone, on the local (default) profile —
  `bmp.auth.dev-master-otp`, disabled on staging/prod. You still call `/otp/request` first.
- Prefer the real code? It's printed in the **bmp-notification** console log after
  `/otp/request` (SMS + email stubs) — same expiry/attempt rules apply to it.
- SMS/WhatsApp/email are console-log stubs; Razorpay/Google aren't wired. None of that
  blocks building/testing the auth flows.

---

## 8. Quick reference

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/otp/request` | public | Send OTP (SMS + email) |
| POST | `/api/v1/auth/otp/verify` | public | Log in / sign up → tokens |
| POST | `/api/v1/auth/oauth2/google` | public | Google sign-in (customers) |
| POST | `/api/v1/auth/refresh` | public (refresh token in body) | New access token |
| POST | `/api/v1/auth/logout` | public (refresh token in body) | Revoke refresh token |
| GET  | `/api/v1/auth/me` | **Bearer** | Restore session / whoami |
| GET  | `/api/v1/users/{userId}` | Bearer (self or service) | Full profile |
| PUT  | `/api/v1/users/{userId}` | Bearer (self or service) | Update profile |
| POST | `/api/v1/salons` | Bearer (SALON_OWNER) | Owner creates their salon |
| POST | `/api/v1/salons/{salonId}/invites` | Bearer (SALON_OWNER) | Generate a manager invite |
