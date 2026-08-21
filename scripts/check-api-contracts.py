#!/usr/bin/env python3
"""
Compare every frontend Zod schema against the backend record it parses.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHY THIS EXISTS
═══════════════════════════════════════════════════════════════════════════════════════════════
Twice now, a schema has asked for fields the server never sends:

  Session 38  getSlots         parsed SlotGroup[]; the server returns a flat SlotResponse list.
  Session 40  SalonSchema      wanted 8 fields NearbySalonResponse didn't have. Same for
                               SalonDetail, Service and Stylist.

Both throw at the Zod boundary on the FIRST real response. Together they meant the entire
customer discovery journey — browse, salon page, pick a stylist, pick a slot — had never worked
against a real backend. Only "Confirm" did.

Neither was noticed, because `USE_MOCKS` defaults ON and the mocks were written from the
CLIENT's assumptions rather than the server's contract. A mock that never has to agree with the
server guarantees the two will diverge, and hides it while they do.

The first time was treated as a one-off. It wasn't. This script is what should have been written
then.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHY IT IS A LOCAL SCRIPT AND NOT A CI JOB
═══════════════════════════════════════════════════════════════════════════════════════════════
BMP and BMP-FE are separate repositories with separate workflows. Neither CI run has the other
repo checked out, so neither can perform this comparison honestly. Options considered:

  · A checked-in contract snapshot — becomes a third thing to keep in sync, and the one nobody
    updates.
  · A cross-repo checkout in CI — real, and worth doing when there's a reason to spend the
    complexity. Not yet.

So: run it before you touch an API shape on either side. It is deliberately dependency-free
(stdlib only, no node, no maven) so there is no excuse not to.

    python3 scripts/check-api-contracts.py            # assumes ../BMP-FE
    python3 scripts/check-api-contracts.py /path/to/BMP-FE

Exit code 1 if any schema asks for a field the server doesn't send.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHAT IT DELIBERATELY DOESN'T CATCH
═══════════════════════════════════════════════════════════════════════════════════════════════
  · Type mismatches (schema says number, server sends String). Would need real type resolution.
  · Fields the SERVER sends that the client ignores — harmless, and flagging them would create
    noise that trains people to ignore the output.
  · Renamed endpoints. It matches by record name, from the PAIRS table below, which is the one
    part a human maintains. A schema absent from that table is REPORTED, not skipped silently —
    an unchecked schema is exactly how the third instance of this bug would arrive.
"""
import re
import sys
import glob
import os

HERE = os.path.dirname(os.path.abspath(__file__))
BACKEND = os.path.dirname(HERE)

# The one hand-maintained thing: which Zod schema parses which Java record.
# Add a row when you add a schema. An unmapped schema is reported as UNCHECKED, not ignored.
PAIRS = {
    "BookingSchema": "BookingResponse",
    "BookingItemSchema": "ItemResponse",
    "PagedBookingsSchema": "PagedBookings",
    "ScheduleEntrySchema": "ScheduleEntryResponse",
    "DaySummarySchema": "SalonDaySummaryResponse",
    "SalonDaySchema": "SalonDayResponse",
    "DeskStylistSchema": "StylistSalonResponse",
    "DeskServiceSchema": "ServiceResponse",
    "SalonBookingSchema": "BookingResponse",
    "SalonHistoryPageSchema": "PagedBookings",
    "CustomerAtSalonSchema": "CustomerAtSalonResponse",
    "CancelPreviewSchema": "CancelPreviewResponse",
    "RescheduleEligibilitySchema": "RescheduleEligibility",
    "BookingEventSchema": "EventResponse",
    "ContactRevealSchema": "ContactRevealResponse",
    "SalonPolicySchema": "PolicyResponse",
    "RawSlotSchema": "SlotResponse",
    "WeeklyTemplateSchema": "WeeklyTemplateResponse",
    "DayTemplateSchema": "DayTemplate",
    "AvailabilityRuleSchema": "AvailabilityRuleResponse",
    "TimeWindowSchema": "TimeWindow",
    "StaffMemberSchema": "StaffMemberResponse",
    "InviteSchema": "InviteResponse",
    "MeResponseSchema": "MeResponse",
    "OtpRequestResponseSchema": "OtpRequestResponse",
    "OtpVerifyResponseSchema": "OtpVerifyResponse",
    "RefreshResponseSchema": "RefreshResponse",
    "GoogleAuthResponseSchema": "GoogleAuthResponse",
    "ApiErrorSchema": "ErrorResponse",
    "SalonSchema": "NearbySalonResponse",
    "SalonDetailSchema": "SalonDetailResponse",
    # The ADMINISTRATIVE salon shape — status + the booking-alert contact, which no customer
    # should read off the public page. Session 40.
    "SalonAdminSchema": "SalonResponse",
    "ServiceSchema": "ServiceResponse",
    "StylistSchema": "PublicStylistResponse",
    "SlotGroupSchema": None,      # built client-side by groupSlots() — no server equivalent
    "OfferSchema": "CouponRequestDto",
    "CouponQuoteSchema": "CouponQuoteResponse",
    "CustomerAtSalonPageSchema": None,
}


def _split_top_level(text):
    """Split on commas that aren't inside brackets."""
    out, depth, cur = [], 0, ""
    for ch in text:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def backend_records():
    """Every `record Name(...)` in the backend -> set of component names."""
    recs = {}
    for path in glob.glob(os.path.join(BACKEND, "*/src/main/java/**/*.java"), recursive=True):
        src = open(path, encoding="utf-8").read()
        for m in re.finditer(r"record (\w+)\(", src):
            name, i, depth = m.group(1), m.end(), 1
            start = i
            while depth and i < len(src):
                if src[i] == "(":
                    depth += 1
                elif src[i] == ")":
                    depth -= 1
                i += 1
            body = re.sub(r"//.*", "", re.sub(r"/\*.*?\*/", "", src[start:i - 1], flags=re.S))
            fields = []
            for f in _split_top_level(body):
                f = re.sub(r"@\w+(\([^)]*\))?", "", f).strip()
                if f:
                    fields.append(f.split()[-1])
            recs.setdefault(name, set()).update(fields)
    return recs


def frontend_schemas(fe_root):
    """Every exported Zod object schema -> {keys, base, source}."""
    out = {}
    for path in glob.glob(os.path.join(fe_root, "src/api/*.ts")):
        src = open(path, encoding="utf-8").read()
        for m in re.finditer(
            r"export const (\w+Schema)\s*=\s*(?:z\.object\(\{|(\w+)\.extend\(\{)", src
        ):
            name, base, i, depth = m.group(1), m.group(2), m.end(), 1
            start = i
            while depth and i < len(src):
                if src[i] == "{":
                    depth += 1
                elif src[i] == "}":
                    depth -= 1
                i += 1
            body = re.sub(r"//.*", "", re.sub(r"/\*.*?\*/", "", src[start:i - 1], flags=re.S))
            out[name] = {
                "keys": re.findall(r"^\s{2}(\w+):", body, re.M),
                "base": base,
                "src": body,
                "file": os.path.basename(path),
            }
    return out


def main():
    fe_root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BACKEND, "..", "BMP-FE")
    if not os.path.isdir(os.path.join(fe_root, "src", "api")):
        print(f"Can't find BMP-FE at {fe_root}. Pass its path as an argument.")
        return 2

    recs = backend_records()
    schemas = frontend_schemas(fe_root)
    problems, unchecked = [], []

    for name, info in sorted(schemas.items()):
        if name not in PAIRS:
            unchecked.append(name)
            continue
        record = PAIRS[name]
        if record is None:
            continue
        if record not in recs:
            problems.append((name, record, ["<record not found in backend>"]))
            continue

        keys = set(info["keys"])
        # An `X.extend({...})` schema inherits X's keys AND X's optionality markers. The source
        # text has to be unioned too, or an inherited `.nullish()` is invisible here and the
        # checker reports a mismatch that isn't one. (It did, on SalonDetailSchema.distanceKm.)
        src = info["src"]
        base = info["base"]
        if base and base in schemas:
            keys |= set(schemas[base]["keys"])
            src += "\n" + schemas[base]["src"]

        missing = sorted(keys - recs[record])
        # A key the client declares optional/nullish/default is fine — absence is by design.
        hard = [
            k for k in missing
            if not re.search(rf"\b{k}:.*(optional|nullish|default)\(", src)
        ]
        if hard:
            problems.append((name, record, hard))

    if unchecked:
        print("UNCHECKED — add these to PAIRS in this script:")
        for n in unchecked:
            print(f"  · {n}")
        print()

    if problems:
        print("MISMATCHES — the client asks for fields the server does not send:")
        for schema, record, fields in problems:
            print(f"  ✗ {schema}  ->  {record}")
            for f in fields:
                print(f"        missing: {f}")
        print()
        print("Fix one of three ways, in this order of preference:")
        print("  1. The server should send it   -> add it to the record (and a migration if new)")
        print("  2. The client shouldn't ask    -> remove it, or derive it client-side")
        print("  3. It's genuinely optional     -> .nullish() on the schema AND handle null in the UI")
        print()
        print("If you pick 3, check the RENDER path too: a schema that permits null and a")
        print("component that calls .toFixed() on it trades a parse error for a crash.")
        return 1

    print(f"OK — {len([s for s in schemas if PAIRS.get(s)])} schema(s) match their backend record.")
    if unchecked:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
