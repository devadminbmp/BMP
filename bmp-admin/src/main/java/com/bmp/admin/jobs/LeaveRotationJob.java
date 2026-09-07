package com.bmp.admin.jobs;

import com.bmp.admin.services.StaffLeaveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the leave sweep once a night. Session 59.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS CLASS EXISTS AT ALL
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * {@link StaffLeaveService#applyTodaysLeave()} was written, documented and tested — and nothing
 * called it. Without a caller the whole leave feature is decorative for the case that matters:
 * leave is requested in ADVANCE, so approving it does nothing to the rotation on the day it starts.
 * An agent approved for next Friday would still have been handed tickets next Friday, and the only
 * symptom would have been a customer waiting on somebody who is at a wedding.
 *
 * <p>That is worth naming as a pattern rather than a one-off: a method whose javadoc describes a
 * schedule is not scheduled by the javadoc. Anything that says "nightly" needs a class like this
 * one, and the absence of it is invisible in review because the code reads as if it runs.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHY 00:05 AND NOT MIDNIGHT
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Midnight is the busiest cron minute on any machine, and the sweep reads {@code LocalDate.now()}.
 * Running exactly at the boundary risks a job that starts at 23:59:59.8 and computes yesterday's
 * date for today's work. Five minutes past costs nothing — nobody is assigned a ticket between
 * 00:00 and 00:05 whose fate depends on this — and removes the boundary entirely.
 *
 * <p>The timezone is Asia/Kolkata explicitly, not the JVM default. A server that moves to UTC would
 * otherwise run this at 5:30am IST, putting people back into the rotation five and a half hours
 * into a day they are absent for.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * AND ONCE AT STARTUP
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * A deployment at 09:00 restarts the service after that night's sweep would have run. Without the
 * startup pass, a service that is redeployed daily — which is normal — could go a long time never
 * running it at all. The sweep is idempotent (it only writes rows whose state actually differs), so
 * running it twice costs two queries and changes nothing.
 */
@Component
public class LeaveRotationJob {

    private static final Logger log = LoggerFactory.getLogger(LeaveRotationJob.class);

    private final StaffLeaveService leave;

    public LeaveRotationJob(StaffLeaveService leave) {
        this.leave = leave;
    }

    /** Every day at 00:05 IST: everyone starting leave is out, everyone returning is back in. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Kolkata")
    public void nightly() {
        run("nightly");
    }

    /**
     * One pass shortly after boot, for the redeploy-after-midnight case described above.
     *
     * <p>Fixed delay rather than {@code @PostConstruct}: at construction time the datasource and
     * Flyway may not have finished, and a job that throws during startup takes the service with it.
     * Thirty seconds in, everything is up and a failure here is just a logged warning.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = Long.MAX_VALUE)
    public void onStartup() {
        run("startup");
    }

    /**
     * Never throws.
     *
     * <p>A scheduled method that throws is silently unscheduled by some executors and, worse,
     * produces a stack trace nobody reads at 5am. The rotation being briefly wrong is recoverable
     * — the next run fixes it — so the failure mode is a warning, not a crash.
     */
    private void run(String trigger) {
        try {
            int changed = leave.applyTodaysLeave();
            log.info("Leave sweep ({}) complete — {} staff moved in or out of the rotation.",
                    trigger, changed);
        } catch (Exception e) {
            log.error("Leave sweep ({}) failed: {}. The rotation may include somebody who is away, "
                    + "or exclude somebody who is back. The next run will correct it.",
                    trigger, e.toString(), e);
        }
    }
}
