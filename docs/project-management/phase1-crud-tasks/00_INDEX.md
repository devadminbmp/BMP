# BMP Phase 1 — CRUD Task Files

9 tasks. Each does ONE thing: plain create/read/update/list REST endpoints for one
service's own database tables. No third-party APIs (Razorpay/MSG91/FCM), no calls to
other modules, no login/auth yet — all of that is Phase 3, later. Work top to bottom;
the order below is easiest first, and later tasks depend on earlier ones.

| # | Task ID | File | What it builds | Difficulty | Owner | Depends on |
|---|---|---|---|---|---|---|
| 1 | BMP-30 | `01_BMP30_Notification_log.md` | bmp-notification | Easy | Shivam | BMP-6 (table design) |
| 2 | BMP-29 | `02_BMP29_Admin_module.md` | bmp-admin | Medium | Achyuth | BMP-4 (V008 migration) |
| 3 | BMP-22 | `03_BMP22_User_module.md` | bmp-user | Medium | Achyuth | none |
| 4 | BMP-23 | `04_BMP23_Salon_core_module.md` | bmp-salon | Medium | Darshan | none |
| 5 | BMP-28 | `05_BMP28_Rewards_module.md` | bmp-rewards | Medium | Achyuth | BMP-22 (needs a real user_id) |
| 6 | BMP-24 | `06_BMP24_Stylist_module.md` | bmp-salon | Medium | Achyuth | BMP-23 (same migration, needs salon first) |
| 7 | BMP-25 | `07_BMP25_Booking_module.md` | bmp-booking | Hard | Shivam | BMP-23 + BMP-24 (salon/stylist) + BMP-22 (customer) |
| 8 | BMP-26 | `08_BMP26_Payment_order_module.md` | bmp-payment | Medium | Shivam | BMP-25 (needs a real booking_id) |
| 9 | BMP-27 | `09_BMP27_Review_module.md` | bmp-review | Medium | Darshan | BMP-25 (needs a real booking_id) |
