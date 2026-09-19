/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.backup;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.BitSet;
import java.util.Locale;

/**
 * Five-field cron, parsed and evaluated here rather than taken from a library.
 *
 * <p>The engine ships Quartz, and the Data Pruner uses its {@code CronExpression}. This
 * does not, for the reason the other extensions in this repository avoid reaching into
 * {@code server-lib}: what an extension's classloader can see from there is a property of
 * how the engine was launched, not of the extension, and a backup that stops being taken
 * because a jar moved is the worst class of silent failure this plugin can have. Cron is
 * about two hundred lines and has not changed since the 1970s, so owning it is the smaller
 * liability.
 *
 * <p>Supported: {@code minute hour day-of-month month day-of-week}, with a star, a range
 * {@code a-b}, a list {@code a,b,c}, a step {@code a-b/n} or {@code star/n}, three-letter
 * month names ({@code JAN}..{@code DEC}) and day names ({@code SUN}..{@code SAT}). Sunday
 * is both {@code 0} and {@code 7}.
 *
 * <p><b>Not</b> supported, deliberately: a seconds field, {@code ?}, {@code L}, {@code W},
 * {@code #} and the {@code @yearly} macros. A backup does not need sub-minute precision,
 * and accepting Quartz's six-field form here would mean silently reading a seconds field as
 * minutes -- an expression that looks accepted and fires at the wrong time, which is worse
 * than one that is refused with a reason. Six fields are refused with exactly that reason.
 */
final class Cron {

    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);
    private final BitSet months = new BitSet(13);
    private final BitSet daysOfWeek = new BitSet(8);

    /**
     * Whether the day is selected by day-of-month, by day-of-week, or by both.
     *
     * <p>Vixie cron's rule, kept because every operator who writes cron has it in their
     * fingers: when both day fields are restricted the day matches if <i>either</i> does,
     * not both. {@code 0 3 1 * MON} is "the first of the month, and every Monday", not
     * "Mondays that fall on the first".
     */
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private final String source;

    private Cron(String source, boolean domRestricted, boolean dowRestricted) {
        this.source = source;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    String source() {
        return source;
    }

    private static final String[] MONTHS = {
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /**
     * Parses an expression, or throws with a message meant to be shown in the settings form.
     *
     * @throws IllegalArgumentException with a sentence naming the field that is wrong
     */
    static Cron parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("a cron expression is required");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length == 6 || fields.length == 7) {
            throw new IllegalArgumentException("this is a five-field cron (minute hour "
                + "day-of-month month day-of-week); the six- and seven-field Quartz form "
                + "with a seconds field is not accepted, because reading its seconds as "
                + "minutes would schedule the backup at the wrong time without saying so");
        }
        if (fields.length != 5) {
            throw new IllegalArgumentException("expected 5 fields (minute hour day-of-month "
                + "month day-of-week), found " + fields.length);
        }

        Cron cron = new Cron(expression.trim(),
            !"*".equals(fields[2]), !"*".equals(fields[4]));
        fill(cron.minutes, fields[0], 0, 59, "minute", null);
        fill(cron.hours, fields[1], 0, 23, "hour", null);
        fill(cron.daysOfMonth, fields[2], 1, 31, "day-of-month", null);
        fill(cron.months, fields[3], 1, 12, "month", MONTHS);
        fill(cron.daysOfWeek, fields[4], 0, 7, "day-of-week", DAYS);
        // 7 and 0 are both Sunday; everything downstream reads 0..6 only.
        if (cron.daysOfWeek.get(7)) {
            cron.daysOfWeek.clear(7);
            cron.daysOfWeek.set(0);
        }
        return cron;
    }

    private static void fill(BitSet target, String field, int min, int max, String name,
                             String[] names) {
        for (String term : field.split(",")) {
            String t = term.trim();
            if (t.isEmpty()) {
                throw new IllegalArgumentException(name + ": empty term in '" + field + "'");
            }

            int step = 1;
            int slash = t.indexOf('/');
            boolean stepped = slash >= 0;
            if (stepped) {
                String stepText = t.substring(slash + 1).trim();
                t = t.substring(0, slash).trim();
                try {
                    step = Integer.parseInt(stepText);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(name + ": '" + stepText
                        + "' is not a step");
                }
                if (step < 1) {
                    throw new IllegalArgumentException(name + ": step must be 1 or more");
                }
            }

            int from;
            int to;
            if ("*".equals(t)) {
                from = min;
                to = max;
            } else {
                int dash = t.indexOf('-', 1);
                if (dash > 0) {
                    from = value(t.substring(0, dash), min, max, name, names);
                    to = value(t.substring(dash + 1), min, max, name, names);
                } else {
                    from = value(t, min, max, name, names);
                    // A bare value with a step means "from here to the end of the range",
                    // which is what every cron implementation does with 5/20.
                    to = stepped ? max : from;
                }
            }
            if (from > to) {
                throw new IllegalArgumentException(name + ": " + from + "-" + to
                    + " counts backwards");
            }
            for (int i = from; i <= to; i += step) {
                target.set(i);
            }
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException(name + ": selects nothing");
        }
    }

    private static int value(String text, int min, int max, String name, String[] names) {
        String t = text.trim();
        if (names != null) {
            String upper = t.toUpperCase(Locale.ROOT);
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(upper)) {
                    // Months run 1..12 and days 0..6; both name arrays start at their own
                    // minimum, so the offset is the same expression for each.
                    return i + min;
                }
            }
        }
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + ": '" + t + "' is not a number");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + ": " + v + " is outside " + min
                + "-" + max);
        }
        return v;
    }

    /**
     * The first firing strictly after {@code after}, in that local time.
     *
     * <p>Scans a day at a time and only looks at hours and minutes on a day that matches,
     * so the worst case -- a leap day, {@code 0 0 29 2 *} -- is a few thousand cheap
     * comparisons rather than the two million a minute-by-minute scan would take.
     *
     * <p>Returns {@code null} past four years out. That is not really an error case: it is
     * the answer for an expression that can never fire, such as the 31st of February, and
     * the caller reports it as "this schedule never runs" rather than looping.
     */
    LocalDateTime nextAfter(LocalDateTime after) {
        LocalDateTime cursor = after.withSecond(0).withNano(0).plusMinutes(1);
        LocalDate lastDay = after.toLocalDate().plusYears(4);

        LocalDate day = cursor.toLocalDate();
        LocalTime earliest = cursor.toLocalTime();
        while (!day.isAfter(lastDay)) {
            if (matchesDay(day)) {
                for (int hour = earliest.getHour(); hour < 24; hour++) {
                    if (!hours.get(hour)) {
                        continue;
                    }
                    int startMinute = hour == earliest.getHour() ? earliest.getMinute() : 0;
                    for (int minute = startMinute; minute < 60; minute++) {
                        if (minutes.get(minute)) {
                            return LocalDateTime.of(day, LocalTime.of(hour, minute));
                        }
                    }
                }
            }
            day = day.plusDays(1);
            earliest = LocalTime.MIDNIGHT;
        }
        return null;
    }

    private boolean matchesDay(LocalDate day) {
        if (!months.get(day.getMonthValue())) {
            return false;
        }
        boolean dom = daysOfMonth.get(day.getDayOfMonth());
        DayOfWeek dw = day.getDayOfWeek();
        // DayOfWeek is MONDAY=1..SUNDAY=7; cron is SUNDAY=0..SATURDAY=6.
        boolean dow = daysOfWeek.get(dw == DayOfWeek.SUNDAY ? 0 : dw.getValue());

        if (domRestricted && dowRestricted) {
            return dom || dow;
        }
        return dom && dow;
    }

    /**
     * Fixed vectors, run by {@code build.sh} before the extension is packaged.
     *
     * <p>A scheduler is the one part of this plugin whose failure mode is silence: an
     * expression that is off by an hour, or that never fires at all, produces no error
     * anywhere -- just backups that are not there on the day somebody looks for them. So
     * the rules are asserted at build time rather than trusted.
     *
     * @return null when every vector holds, otherwise the first failure
     */
    static String selfTest() {
        try {
            String r;
            if ((r = expect("0 2 * * *", "2026-09-19T03:00", "2026-09-20T02:00")) != null) {
                return r;
            }
            if ((r = expect("0 2 * * *", "2026-09-19T01:30", "2026-09-19T02:00")) != null) {
                return r;
            }
            if ((r = expect("*/15 * * * *", "2026-09-19T10:07", "2026-09-19T10:15")) != null) {
                return r;
            }
            // Strictly after: a job that has just fired must not fire again on the spot.
            if ((r = expect("0 2 * * *", "2026-09-19T02:00", "2026-09-20T02:00")) != null) {
                return r;
            }
            if ((r = expect("30 1 * * SUN", "2026-09-19T00:00", "2026-09-20T01:30")) != null) {
                return r;
            }
            if ((r = expect("0 0 1 * *", "2026-09-19T00:00", "2026-10-01T00:00")) != null) {
                return r;
            }
            // Vixie's or-rule: the 1st, and every Monday, not their intersection.
            if ((r = expect("0 3 1 * MON", "2026-09-19T00:00", "2026-09-21T03:00")) != null) {
                return r;
            }
            if ((r = expect("0 3 1 * MON", "2026-09-28T04:00", "2026-10-01T03:00")) != null) {
                return r;
            }
            // A leap day is reachable, and is not in 2026 or 2027.
            if ((r = expect("0 0 29 2 *", "2026-09-19T00:00", "2028-02-29T00:00")) != null) {
                return r;
            }
            // A bare value with a step runs to the end of the range.
            if ((r = expect("5/20 * * * *", "2026-09-19T10:06", "2026-09-19T10:25")) != null) {
                return r;
            }
            if (parse("0 0 31 2 *").nextAfter(LocalDateTime.parse("2026-09-19T00:00")) != null) {
                return "31 February should never fire";
            }
            if ((r = refuses("0 0 2 * * *", "six-field")) != null) {
                return r;
            }
            if ((r = refuses("0 24 * * *", "hour 24")) != null) {
                return r;
            }
            if ((r = refuses("0 2 * *", "four fields")) != null) {
                return r;
            }
            if ((r = refuses("0 2 * * XYZ", "unknown day name")) != null) {
                return r;
            }
            return null;
        } catch (RuntimeException e) {
            return "unexpected failure: " + e;
        }
    }

    private static String expect(String expression, String from, String want) {
        LocalDateTime got = parse(expression).nextAfter(LocalDateTime.parse(from));
        String actual = got == null ? "never" : got.toString();
        return actual.equals(want) ? null
            : "'" + expression + "' after " + from + ": expected " + want + ", got " + actual;
    }

    private static String refuses(String expression, String why) {
        try {
            parse(expression);
            return "'" + expression + "' (" + why + ") should have been refused";
        } catch (IllegalArgumentException expected) {
            return null;
        }
    }
}
