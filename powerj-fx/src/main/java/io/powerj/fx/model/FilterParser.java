package io.powerj.fx.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.powerj.fx.Messages;

/**
 * The expression typed in the filter of a column (specification FR-77):
 * <ul>
 *   <li>{@code text}: the cell contains it, without regard to case;</li>
 *   <li>{@code =text}: the cell is exactly that ({@code =} alone: an empty cell); {@code !text}: it does not contain it;</li>
 *   <li>{@code /regex/}: the cell matches the regular expression, without regard to case;</li>
 *   <li>on a number or a size: {@code >10}, {@code >=3}, {@code <1.5mb}, {@code =7}, {@code 1kb..1mb}, {@code 5..}, {@code ..9}
 *       (sizes take the suffixes {@code b kb mb gb tb}, multiples of 1024);</li>
 *   <li>on a date: the same operators with {@code 2026}, {@code 2026-10}, {@code 2026-10-05},
 *       {@code 2026-10-05 18:12} or {@code 2026-10-05 18:12:30}; the date stands for the whole period it names, so
 *       {@code >2026-10} means after October and {@code 2026-10-01..2026-10-05} includes October 5.</li>
 * </ul>
 * Text conditions look at the text of the cell as shown <b>and</b> at its value ({@code KB} or {@code 14520} both find
 * {@code 14.2 KB}). An operator alone filters nothing, so that typing it does not empty the table.
 */
public final class FilterParser {

    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})(?:-(\\d{2})(?:-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?)?)?)?");
    private static final Pattern SIZE = Pattern.compile("([+-]?\\d+(?:[.,]\\d+)?)\\s*(b|kb|mb|gb|tb)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:[.,]\\d+)?(?:[eE][+-]?\\d+)?");

    private final ZoneId zone;

    /** @param zone zone of the dates that are typed, and of the dates of {@code LocalDate}/{@code LocalDateTime} cells */
    public FilterParser(ZoneId zone) {
        this.zone = zone;
    }

    /** Dates in the zone of the machine, as the table shows them. */
    public static FilterParser system() {
        return new FilterParser(ZoneId.systemDefault());
    }

    /** A date period: from {@code start} (included) to {@code end} (excluded). */
    private record Period(Instant start, Instant end) {
    }

    /** What is typed makes no sense for the column. */
    private static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    /**
     * @param semantics the kind of the column that is filtered: it decides which operators exist
     * @param display   the kind of the cells whose text is read
     */
    public Parsed parse(String expression, ColumnKind semantics, ColumnKind display) {
        String text = expression == null ? "" : expression.strip();
        if (text.isEmpty()) {
            return Parsed.NONE;
        }
        try {
            if (text.length() >= 2 && text.startsWith("/") && text.endsWith("/")) {
                return regex(text.substring(1, text.length() - 1), display);
            }
            if (text.startsWith("!")) {
                String rest = text.substring(1).strip();
                return rest.isEmpty() ? Parsed.NONE : Parsed.not(contains(rest, display));
            }
            if (text.startsWith("=")) {
                return equal(text.substring(1).strip(), semantics, display);
            }
            return switch (semantics) {
                case NUMBER, SIZE, DATE -> ordered(text, semantics, display);
                default -> Parsed.of(contains(text, display));
            };
        } catch (Invalid e) {
            return Parsed.invalid(e.getMessage());
        }
    }

    private static Parsed regex(String body, ColumnKind display) throws Invalid {
        Pattern pattern;
        try {
            pattern = Pattern.compile(body, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new Invalid(Messages.get("grid.filter.regex.invalid", e.getDescription()));
        }
        return Parsed.of(value -> pattern.matcher(CellFormatter.fullText(value, display)).find()
                || pattern.matcher(raw(value)).find());
    }

    private static ColumnFilter contains(String text, ColumnKind display) {
        String needle = lower(text);
        return value -> lower(CellFormatter.fullText(value, display)).contains(needle)
                || lower(raw(value)).contains(needle);
    }

    private static ColumnFilter equals(String text, ColumnKind display) {
        String wanted = lower(text);
        return value -> lower(CellFormatter.fullText(value, display)).equals(wanted) || lower(raw(value)).equals(wanted);
    }

    /** {@code =x}: the number or the period if there is one, otherwise the same text. */
    private Parsed equal(String rest, ColumnKind semantics, ColumnKind display) {
        if (!rest.isEmpty()) {
            try {
                switch (semantics) {
                    case NUMBER, SIZE -> {
                        BigDecimal number = number(rest, semantics);
                        return Parsed.of(numbers(new Interval<>(number, true, number, true)));
                    }
                    case DATE -> {
                        Period period = period(rest);
                        return Parsed.of(dates(new Interval<>(period.start(), true, period.end(), false)));
                    }
                    default -> {
                    }
                }
            } catch (Invalid _) {
                // not a number or a date: the text is compared
            }
        }
        return Parsed.of(equals(rest, display));
    }

    /** Operators and ranges of the numbers, the sizes and the dates. */
    private Parsed ordered(String text, ColumnKind semantics, ColumnKind display) throws Invalid {
        int dots = text.indexOf("..");
        if (dots >= 0) {
            return range(text.substring(0, dots).strip(), text.substring(dots + 2).strip(), semantics);
        }
        String operator = text.startsWith(">=") ? ">=" : text.startsWith("<=") ? "<=" : text.startsWith(">") ? ">"
                : text.startsWith("<") ? "<" : null;
        if (operator == null) {
            return Parsed.of(contains(text, display));
        }
        String rest = text.substring(operator.length()).strip();
        if (rest.isEmpty()) {
            return Parsed.NONE;
        }
        if (semantics == ColumnKind.DATE) {
            Period p = period(rest);
            return Parsed.of(dates(switch (operator) {
                case ">" -> new Interval<>(p.end(), true, null, false);
                case ">=" -> new Interval<>(p.start(), true, null, false);
                case "<" -> new Interval<>(null, false, p.start(), false);
                default -> new Interval<>(null, false, p.end(), false);
            }));
        }
        BigDecimal n = number(rest, semantics);
        return Parsed.of(numbers(switch (operator) {
            case ">" -> new Interval<>(n, false, null, false);
            case ">=" -> new Interval<>(n, true, null, false);
            case "<" -> new Interval<>(null, false, n, false);
            default -> new Interval<>(null, false, n, true);
        }));
    }

    private Parsed range(String from, String to, ColumnKind semantics) throws Invalid {
        if (from.isEmpty() && to.isEmpty()) {
            return Parsed.NONE;
        }
        if (semantics == ColumnKind.DATE) {
            Instant low = from.isEmpty() ? null : period(from).start();
            Instant high = to.isEmpty() ? null : period(to).end();
            return Parsed.of(dates(new Interval<>(low, true, high, false)));
        }
        BigDecimal low = from.isEmpty() ? null : number(from, semantics);
        BigDecimal high = to.isEmpty() ? null : number(to, semantics);
        return Parsed.of(numbers(new Interval<>(low, true, high, true)));
    }

    private static BigDecimal number(String text, ColumnKind semantics) throws Invalid {
        if (semantics == ColumnKind.SIZE) {
            Matcher m = SIZE.matcher(text);
            if (!m.matches()) {
                throw new Invalid(Messages.get("grid.filter.size.invalid", text));
            }
            BigDecimal value = new BigDecimal(m.group(1).replace(',', '.'));
            String unit = m.group(2) == null ? "b" : lower(m.group(2));
            int power = switch (unit) {
                case "kb" -> 1;
                case "mb" -> 2;
                case "gb" -> 3;
                case "tb" -> 4;
                default -> 0;
            };
            return value.multiply(new BigDecimal(BigInteger.valueOf(1024).pow(power)));
        }
        if (!NUMBER.matcher(text).matches()) {
            throw new Invalid(Messages.get("grid.filter.number.invalid", text));
        }
        return new BigDecimal(text.replace(',', '.'));
    }

    private Period period(String text) throws Invalid {
        Matcher m = DATE.matcher(text);
        if (!m.matches()) {
            throw new Invalid(Messages.get("grid.filter.date.invalid", text));
        }
        try {
            int year = Integer.parseInt(m.group(1));
            if (m.group(2) == null) {
                return period(ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone), ChronoUnit.YEARS);
            }
            int month = Integer.parseInt(m.group(2));
            if (m.group(3) == null) {
                return period(ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, zone), ChronoUnit.MONTHS);
            }
            int day = Integer.parseInt(m.group(3));
            if (m.group(4) == null) {
                return period(ZonedDateTime.of(year, month, day, 0, 0, 0, 0, zone), ChronoUnit.DAYS);
            }
            int hour = Integer.parseInt(m.group(4));
            int minute = Integer.parseInt(m.group(5));
            if (m.group(6) == null) {
                return period(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone), ChronoUnit.MINUTES);
            }
            int second = Integer.parseInt(m.group(6));
            return period(ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone), ChronoUnit.SECONDS);
        } catch (DateTimeException e) {
            throw new Invalid(Messages.get("grid.filter.date.invalid", text));
        }
    }

    private static Period period(ZonedDateTime start, ChronoUnit unit) {
        return new Period(start.toInstant(), start.plus(1, unit).toInstant());
    }

    private static ColumnFilter numbers(Interval<BigDecimal> interval) {
        return value -> {
            BigDecimal number = decimal(value);
            return number != null && interval.contains(number);
        };
    }

    private ColumnFilter dates(Interval<Instant> interval) {
        return value -> {
            Instant instant = instant(value);
            return instant != null && interval.contains(instant);
        };
    }

    private static BigDecimal decimal(Object value) {
        return switch (value) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d when Double.isFinite(d) -> new BigDecimal(d);
            case Float f when Float.isFinite(f) -> new BigDecimal(f.doubleValue());
            case Double _, Float _ -> null;
            case Number n -> BigDecimal.valueOf(n.longValue());
            case null, default -> null;
        };
    }

    private Instant instant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case Date d -> d.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            case LocalDateTime l -> l.atZone(zone).toInstant();
            case LocalDate d -> d.atStartOfDay(zone).toInstant();
            case null, default -> null;
        };
    }

    private static String raw(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
