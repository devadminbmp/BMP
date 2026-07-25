# local-secrets.example.ps1  —  TEMPLATE (safe to commit; contains no real secrets).
#
# Copy this to  local-secrets.ps1  (which is gitignored) and fill in your own values, then
# dot-source it before running the service that needs those creds:
#     Copy-Item local-secrets.example.ps1 local-secrets.ps1   # once
#     . .\local-secrets.ps1                                    # each new terminal
#     mvn -pl bmp-notification spring-boot:run
#
# You only need these if you want REAL delivery. Without them, email/SMS just log to the
# console (and OTP 000000 always works in dev), so this is optional.

# ---- Email (real OTP delivery via SMTP) ----
# Gmail: username = your gmail, password = a 16-char App Password (NOT your login password;
#        needs 2FA on, then myaccount.google.com/apppasswords). email-from must equal username.
# Brevo/SES: set HOST/PORT to the provider's SMTP relay and use their SMTP key as the password.
$env:BMP_EMAIL_PROVIDER = "smtp"
$env:BMP_SMTP_HOST      = "smtp.gmail.com"
$env:BMP_SMTP_PORT      = "587"
$env:BMP_SMTP_USERNAME  = "your-email@gmail.com"
$env:BMP_SMTP_PASSWORD  = "your-16-char-app-password"
$env:BMP_EMAIL_FROM     = "your-email@gmail.com"

# ---- (later) SMS gateway, Razorpay, etc. go here too ----
# $env:BMP_MSG91_AUTH_KEY = "..."
