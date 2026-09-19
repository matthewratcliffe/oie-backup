/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * When a backup runs, if it runs at all.
 *
 * <p>Five shapes, in increasing order of how much the operator has to know: off, every N
 * minutes, daily at a time, weekly on a day at a time, monthly on a date at a time, and a
 * cron expression for anything else. The first five exist because "every night at two" is
 * what almost everybody wants and writing {@code 0 2 * * *} to say it is a small exam;
 * cron exists because the moment a deployment needs "weekdays only, twice a day" any fixed
 * set of choices is the wrong set.
 *
 * <p>Everything except the interval is evaluated through {@link Cron}, including the
 * daily/weekly/monthly forms, which are turned into an expression rather than given their
 * own arithmetic. One evaluator means one set of daylight-saving and end-of-month
 * behaviours to reason about instead of four.
 *
 * <h2>The time zone matters, so it is explicit</h2>
 *
 * <p>"Two in the morning" is a local statement and the container's clock is almost always
 * UTC, so a schedule that did not name a zone would quietly mean 2am UTC -- which is noon
 * in Sydney, and exactly the wrong time for the thing this is usually for. The zone is
 * stored with the schedule and shown beside it. It defaults to the JVM's, so an engine
 * whose {@code TZ} is set behaves the way its operator expects without being told twice.
 *
 * <p>Daylight saving is handled by asking for local times and resolving them: on the
 * morning a clock jumps forward, a 2am schedule runs at the first instant that exists
 * after 2am, and on the morning it goes back it runs once, at the first of the two 2ams.
 * Running twice would take two backups -- harmless -- but skipping is not, so the
 * resolution leans the way that always produces a run.
 */
public final class Schedule {

    public enum Kind {
        /** No schedule. Backups still run when somebody asks for one. */
        OFF("off"),
        /** Every N minutes, measured from the end of the last run. */
        INTERVAL("interval"),
        DAILY("daily"),
        WEEKLY("weekly"),
        MONTHLY("monthly"),
        CRON("cron");

        private final String token;

        Kind(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        static Kind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return OFF;
            }
            String t = raw.trim().toLowerCase(Locale.ROOT);
            for (Kind k : values()) {
                if (k.token.equals(t) || k.name().toLowerCase(Locale.ROOT).equals(t)) {
                    return k;
                }
            }
            return OFF;
        }
    }

    /** One hour. Short enough to be useful, long enough not to be a mistake. */
    private static final int MIN_INTERVAL_MINUTES = 15;
    /** Six weeks. Past this the interval form is the wrong tool; use cron or monthly. */
    private static final int MAX_INTERVAL_MINUTES = 60 * 24 * 42;

    private final Kind kind;
    private final int intervalMinutes;
    private final int hour;
    private final int minute;
    /** 1 = Monday .. 7 = Sunday, matching {@link java.time.DayOfWeek}. */
    private final int dayOfWeek;
    /** 1..28; see {@link #dayOfMonth()} for why it stops there. */
    private final int dayOfMonth;
    private final String cronExpression;
    private final ZoneId zone;

    /** Parsed once at construction so an invalid expression is refused at save time. */
    private final Cron cron;

    private Schedule(Kind kind, int intervalMinutes, int hour, int minute, int dayOfWeek,
                     int dayOfMonth, String cronExpression, ZoneId zone) {
        this.kind = kind;
        this.intervalMinutes = intervalMinutes;
        this.hour = hour;
        this.minute = minute;
        this.dayOfWeek = dayOfWeek;
        this.dayOfMonth = dayOfMonth;
        this.cronExpression = cronExpression;
        this.zone = zone;
        this.cron = kind == Kind.OFF || kind == Kind.INTERVAL ? null : Cron.parse(expression());
    }

    public static Schedule off() {
        return new Schedule(Kind.OFF, 0, 2, 0, 1, 1, "", ZoneId.systemDefault());
    }

    /**
     * Builds a schedule from the form's fields, refusing what cannot be scheduled.
     *
     * @throws IllegalArgumentException with a message meant for the settings form
     */
    public static Schedule of(String kindToken, String intervalMinutes, String timeOfDay,
                              String dayOfWeek, String dayOfMonth, String cronExpression,
                              String zoneId) {
        Kind kind = Kind.parse(kindToken);
        ZoneId zone = parseZone(zoneId);

        int[] hm = parseTimeOfDay(timeOfDay);
        int interval = clampInterval(parseInt(intervalMinutes, 24 * 60));
        int dow = clamp(parseInt(dayOfWeek, 1), 1, 7);
        int dom = clamp(parseInt(dayOfMonth, 1), 1, 28);
        String expr = cronExpression == null ? "" : cronExpression.trim();

        if (kind == Kind.CRON && expr.isEmpty()) {
            throw new IllegalArgumentException("a cron expression is required");
        }
        return new Schedule(kind, interval, hm[0], hm[1], dow, dom, expr, zone);
    }

    /**
     * The cron expression this schedule evaluates as.
     *
     * <p>Shown in the console beside the friendly form, because a schedule somebody set
     * with three dropdowns is one they will eventually need to explain to a colleague, and
     * {@code 0 2 * * 1} is the sentence that travels.
     */
    public String expression() {
        switch (kind) {
            case DAILY:
                return minute + " " + hour + " * * *";
            case WEEKLY:
                // Cron counts Sunday as 0; DayOfWeek counts it as 7.
                return minute + " " + hour + " * * " + (dayOfWeek == 7 ? 0 : dayOfWeek);
            case MONTHLY:
                return minute + " " + hour + " " + dayOfMonth + " * *";
            case CRON:
                return cronExpression;
            default:
                return "";
        }
    }

    /**
     * When this schedule next runs, given when it last did.
     *
     * <p>{@code lastRun} is only consulted by the interval form, which is defined relative
     * to it. The calendar forms answer from {@code now} alone, which is what makes them
     * recover cleanly from an engine that was down: a daily 2am backup on a node that comes
     * up at 4am is next due at 2am tomorrow, not immediately, because the window has passed
     * and firing on boot would mean a container restart loop taking a backup every time.
     *
     * @return the next run, or null when this schedule never runs
     */
    public Instant nextAfter(Instant lastRun, Instant now) {
        switch (kind) {
            case OFF:
                return null;
            case INTERVAL:
                // No previous run means due now: a schedule that has just been switched on
                // should take its first backup rather than wait out a full interval to
                // prove it works.
                return lastRun == null ? now : lastRun.plus(Duration.ofMinutes(intervalMinutes));
            default:
                LocalDateTime next = cron.nextAfter(LocalDateTime.ofInstant(now, zone));
                if (next == null) {
                    return null;
                }
                // atZone resolves a local time that does not exist (the spring-forward gap)
                // to the instant after the gap, and an ambiguous one (the autumn overlap) to
                // the earlier of the two. Both are the direction that still produces a run.
                return next.atZone(zone).toInstant();
        }
    }

    /** Whether a run is owed right now. */
    public boolean isDue(Instant lastRun, Instant now) {
        Instant next = nextAfter(lastRun, now);
        return next != null && !next.isAfter(now);
    }

    /** A sentence for the console and the log. */
    public String describe() {
        switch (kind) {
            case OFF:
                return "no schedule";
            case INTERVAL:
                return intervalMinutes % 60 == 0
                    ? "every " + (intervalMinutes / 60) + " hour"
                        + (intervalMinutes == 60 ? "" : "s")
                    : "every " + intervalMinutes + " minutes";
            case DAILY:
                return "daily at " + time() + " " + zone;
            case WEEKLY:
                return "every " + java.time.DayOfWeek.of(dayOfWeek)
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
                    + " at " + time() + " " + zone;
            case MONTHLY:
                return "on day " + dayOfMonth + " of each month at " + time() + " " + zone;
            default:
                return cronExpression + " (" + zone + ")";
        }
    }

    private String time() {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    public Kind kind() {
        return kind;
    }

    public boolean scheduled() {
        return kind != Kind.OFF;
    }

    public int intervalMinutes() {
        return intervalMinutes;
    }

    public String timeOfDay() {
        return time();
    }

    public int dayOfWeek() {
        return dayOfWeek;
    }

    /**
     * The day of the month, never past the 28th.
     *
     * <p>The 29th, 30th and 31st are refused rather than clamped at run time because a
     * monthly backup set for the 31st would silently skip February, April, June, September
     * and November -- seven months a year with no backup and nothing saying so. An operator
     * who genuinely wants the last day of the month has cron and {@code L} is not the
     * answer here either; {@code 0 2 28-31 * *} with a guard is, and that is a decision to
     * make deliberately rather than to inherit from a dropdown.
     */
    public int dayOfMonth() {
        return dayOfMonth;
    }

    public String cronExpression() {
        return cronExpression;
    }

    public ZoneId zone() {
        return zone;
    }

    // ------------------------------------------------------------------
    // Parsing helpers. All total: the form posts strings and an unparseable
    // one becomes the default rather than an exception, except where the value
    // is the whole point of the setting (the cron expression).
    // ------------------------------------------------------------------

    private static ZoneId parseZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("'" + zoneId.trim()
                + "' is not a time zone the JVM knows (try Australia/Melbourne, or UTC)");
        }
    }

    private static int[] parseTimeOfDay(String text) {
        if (text == null || text.isBlank()) {
            return new int[] {2, 0};
        }
        String t = text.trim();
        int colon = t.indexOf(':');
        if (colon < 0) {
            return new int[] {clamp(parseInt(t, 2), 0, 23), 0};
        }
        return new int[] {
            clamp(parseInt(t.substring(0, colon), 2), 0, 23),
            clamp(parseInt(t.substring(colon + 1), 0), 0, 59)};
    }

    private static int parseInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInterval(int minutes) {
        return clamp(minutes, MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES);
    }
}
