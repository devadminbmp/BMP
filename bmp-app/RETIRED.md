# bmp-app — RETIRED (Session 5, microservices pivot)

This module was "the one deployable" when BMP was a modular monolith (Sessions 2-4).

**Session 5:** Darshan asked for a full microservices split — each business module as
its own independently-buildable Spring Boot service, plus a Eureka service registry
and a Spring Cloud API Gateway. This REVERSES the "modular monolith" decision that was
LOCKED in CONTEXT.md and made by all 3 founders together (see CONTEXT.md Session 2 log,
and the "DO NOT SUGGEST: microservices" line). This session's change was made with
Darshan only — **Shivam and Achyuth have not reviewed or approved this reversal.**

What changed:
- Every `bmp-*` module now has its own `pom.xml` (spring-boot-maven-plugin, independently
  packageable), its own `@SpringBootApplication` main class, its own `application.yml`
  with a unique port, and its own copy of its Flyway migration (scoped to its own schema).
- New modules: `bmp-auth` (OTP + JWT, previously going to live inside bmp-user),
  `eureka-server` (service registry, port 8761), `api-gateway` (Spring Cloud Gateway,
  port 8080, single entry point routing to every service by name via Eureka).
- Spring Modulith annotations removed from every module's `package-info.java` — Modulith
  enforces boundaries WITHIN one deployable, which no longer applies once each module is
  its own process.
- `ModularityTests.java` (in this folder) no longer runs (bmp-app is out of the build) —
  it tested exactly the "one deployable, enforced boundaries" property this pivot removes.

This file and the rest of bmp-app/ are left as-is, not deleted, so the monolith version
is still readable if the team wants to compare or revert. See CONTEXT.md Session 5 log
for the full discussion and the operational tradeoffs that were flagged before building this.
