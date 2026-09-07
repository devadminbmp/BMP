# ═══════════════════════════════════════════════════════════════════════════════════════════════
# Start one BMP service WITH the local secrets loaded. Session 47.
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
#   .\run-service.ps1 bmp-notification
#   .\run-service.ps1 bmp-auth
#
# WHY THIS EXISTS
# ---------------
# `local-secrets.ps1` sets BMP_EMAIL_PROVIDER=smtp and the Gmail credentials. But PowerShell's
# `$env:X = "..."` only affects the CURRENT shell and its children — so dot-sourcing it in one
# terminal and starting bmp-notification in another leaves the service with none of them.
#
# When that happens the service does NOT fail. `bmp.notification.email-provider` falls back to its
# default of `log`, LoggingEmailSender wins the @ConditionalOnProperty, and every email is printed
# to the console instead of sent. Signup looks like it worked. The OTP never arrives. Nothing in
# the app says why.
#
# This script removes the gap: it loads the secrets and starts the service in the SAME process, so
# the two cannot drift apart.
#
# Nothing here is secret — the values live in local-secrets.ps1, which is gitignored.

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Service
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# ── 0. Force JDK 21 ─────────────────────────────────────────────────────────────────────────────
# The project targets java.version=21 (root pom.xml), but a plain new terminal often inherits a
# system-wide JAVA_HOME pinned to an older JDK (e.g. 17). Maven then runs the forked JVM with that
# older JDK against classes already compiled for 21, which fails fast with:
#   UnsupportedClassVersionError: ... compiled by a more recent version of the Java Runtime
# Pin JAVA_HOME here so every invocation of this script is correct regardless of the shell's
# ambient environment.
$jdk21 = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
if (Test-Path $jdk21) {
    $env:JAVA_HOME = $jdk21
    $env:PATH = "$jdk21\bin;$env:PATH"
} else {
    Write-Host "WARNING: expected JDK 21 not found at $jdk21 - using whatever JAVA_HOME/PATH already resolve to." -ForegroundColor Yellow
}

# ── 1. Load the secrets ─────────────────────────────────────────────────────────────────────────
$secrets = Join-Path $root 'local-secrets.ps1'
if (Test-Path $secrets) {
    # Dot-source: runs IN this scope, so the $env: assignments persist for the child process below.
    . $secrets
    Write-Host "Loaded local-secrets.ps1" -ForegroundColor DarkGray
} else {
    Write-Host "WARNING: local-secrets.ps1 not found." -ForegroundColor Yellow
    Write-Host "         Copy local-secrets.example.ps1 to local-secrets.ps1 and fill it in." -ForegroundColor Yellow
    Write-Host "         Without it, EMAIL WILL NOT BE SENT - it only prints to this console." -ForegroundColor Yellow
}

# ── 2. Say out loud what the service is about to do ─────────────────────────────────────────────
# The single most useful line in this script. "Not receiving mail" has exactly two common causes
# and this distinguishes them before the service even starts.
$provider = if ($env:BMP_EMAIL_PROVIDER) { $env:BMP_EMAIL_PROVIDER } else { 'log (default)' }
$smtpUser = if ($env:BMP_SMTP_USERNAME)  { $env:BMP_SMTP_USERNAME }  else { '<unset>' }

Write-Host ""
if ($env:BMP_EMAIL_PROVIDER -eq 'smtp') {
    Write-Host "  EMAIL: REAL DELIVERY (smtp as $smtpUser)" -ForegroundColor Green
} else {
    Write-Host "  EMAIL: LOG ONLY - provider is '$provider'. No mail will be sent." -ForegroundColor Red
    Write-Host "         Set BMP_EMAIL_PROVIDER=smtp in local-secrets.ps1." -ForegroundColor Red
}
Write-Host ""

# ── 3. Start it ─────────────────────────────────────────────────────────────────────────────────
$target = Join-Path $root $Service
if (-not (Test-Path $target)) {
    Write-Host "No such service folder: $Service" -ForegroundColor Red
    Write-Host "Expected one of:" -ForegroundColor DarkGray
    Get-ChildItem -Path $root -Directory -Filter 'bmp-*' | ForEach-Object { Write-Host "  $($_.Name)" -ForegroundColor DarkGray }
    exit 1
}

Write-Host "Starting $Service ..." -ForegroundColor Cyan
Push-Location $root
try {
    # -pl runs one module from the aggregator, so the reactor resolves bmp-common correctly
    # rather than needing it installed to the local repo first.
    & mvn spring-boot:run -pl $Service
} finally {
    Pop-Location
}
