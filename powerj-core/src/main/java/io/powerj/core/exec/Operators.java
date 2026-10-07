package io.powerj.core.exec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.temporal.Temporal;
import java.util.Objects;

import io.powerj.core.lang.Ast.Operator;

/**
 * Sémantique des opérateurs des blocs (spécification FR-33) : celle de Java, avec l'égalité par valeur,
 * la comparaison des {@link Comparable}, et l'arithmétique des dates et durées.
 */
final class Operators {

    private Operators() {
    }

    static Object binary(Operator op, Object left, Object right) {
        return switch (op) {
            case EQ -> equal(left, right);
            case NE -> !equal(left, right);
            case LT -> compare(op, left, right) < 0;
            case LE -> compare(op, left, right) <= 0;
            case GT -> compare(op, left, right) > 0;
            case GE -> compare(op, left, right) >= 0;
            case ADD -> add(left, right);
            case SUB -> subtract(left, right);
            case MUL, DIV, REM -> arithmetic(op, left, right);
            case AND, OR, NOT, NEG -> throw new IllegalArgumentException(op.name());
        };
    }

    static Object unary(Operator op, Object operand) {
        return switch (op) {
            case NOT -> !bool(op, operand);
            case NEG -> switch (operand) {
                case Duration d -> d.negated();
                case Object n when isNumeric(n) -> arithmetic(Operator.SUB, 0, n);
                case null, default -> throw unsupported(op, operand);
            };
            default -> throw new IllegalArgumentException(op.name());
        };
    }

    /** Opérande de {@code &&}, {@code ||}, {@code !} ou d'un ternaire : un booléen, comme en Java. */
    static boolean bool(Operator op, Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new PjException("« " + op.symbol() + " » attend un booléen, reçu " + describe(value));
    }

    /** Égalité de valeur ({@code Objects.equals}) ; nombres comparés par valeur ({@code 1 == 1L}). */
    static boolean equal(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return numericCompare(left, right) == 0;
        }
        if (left instanceof Character c && right instanceof Number) {
            return numericCompare((int) c, right) == 0;
        }
        if (left instanceof Number && right instanceof Character c) {
            return numericCompare(left, (int) c) == 0;
        }
        return Objects.equals(left, right);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Operator op, Object left, Object right) {
        if (isNumeric(left) && isNumeric(right)) {
            return numericCompare(numeric(left), numeric(right));
        }
        if (left instanceof Comparable comparable && right != null
                && (left.getClass().isInstance(right) || right.getClass().isInstance(left))) {
            return comparable.compareTo(right);
        }
        throw new PjException("« " + op.symbol() + " » impossible entre " + describe(left) + " et " + describe(right));
    }

    private static Object add(Object left, Object right) {
        if (left instanceof String || right instanceof String) {
            return String.valueOf(left) + right;
        }
        if (left instanceof Temporal t && right instanceof Duration d) {
            return plus(t, d);
        }
        if (left instanceof Duration d && right instanceof Temporal t) {
            return plus(t, d);
        }
        if (left instanceof Duration a && right instanceof Duration b) {
            return a.plus(b);
        }
        return arithmetic(Operator.ADD, left, right);
    }

    private static Object subtract(Object left, Object right) {
        if (left instanceof Temporal t && right instanceof Duration d) {
            return plus(t, d.negated());
        }
        if (left instanceof Duration a && right instanceof Duration b) {
            return a.minus(b);
        }
        return arithmetic(Operator.SUB, left, right);
    }

    private static Temporal plus(Temporal temporal, Duration duration) {
        try {
            return temporal.plus(duration);
        } catch (DateTimeException e) {
            throw new PjException(PjError.of("impossible d'ajouter une durée à " + describe(temporal)
                    + " : " + e.getMessage(), e));
        }
    }

    /** Arithmétique Java avec promotion numérique : int, long, double, ou BigInteger / BigDecimal. */
    private static Object arithmetic(Operator op, Object left, Object right) {
        if (!isNumeric(left) || !isNumeric(right)) {
            throw unsupported(op, left, right);
        }
        Number a = numeric(left);
        Number b = numeric(right);
        try {
            if (a instanceof BigDecimal || b instanceof BigDecimal
                    || (isBig(a) || isBig(b)) && (isFloating(a) || isFloating(b))) {
                BigDecimal x = decimal(a);
                BigDecimal y = decimal(b);
                return switch (op) {
                    case ADD -> x.add(y);
                    case SUB -> x.subtract(y);
                    case MUL -> x.multiply(y);
                    case DIV -> x.divide(y, java.math.MathContext.DECIMAL128);
                    default -> x.remainder(y);
                };
            }
            if (a instanceof BigInteger || b instanceof BigInteger) {
                BigInteger x = new BigInteger(a.toString());
                BigInteger y = new BigInteger(b.toString());
                return switch (op) {
                    case ADD -> x.add(y);
                    case SUB -> x.subtract(y);
                    case MUL -> x.multiply(y);
                    case DIV -> x.divide(y);
                    default -> x.remainder(y);
                };
            }
            if (isFloating(a) || isFloating(b)) {
                double x = a.doubleValue();
                double y = b.doubleValue();
                return switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case DIV -> x / y;
                    default -> x % y;
                };
            }
            if (a instanceof Long || b instanceof Long) {
                long x = a.longValue();
                long y = b.longValue();
                return switch (op) {
                    case ADD -> x + y;
                    case SUB -> x - y;
                    case MUL -> x * y;
                    case DIV -> x / y;
                    default -> x % y;
                };
            }
            int x = a.intValue();
            int y = b.intValue();
            return switch (op) {
                case ADD -> x + y;
                case SUB -> x - y;
                case MUL -> x * y;
                case DIV -> x / y;
                default -> x % y;
            };
        } catch (ArithmeticException e) {
            throw new PjException(PjError.of("calcul impossible : " + e.getMessage(), e));
        }
    }

    /** Comparaison numérique exacte (entiers) ou en double (décimaux). */
    private static int numericCompare(Object left, Object right) {
        Number a = numeric(left);
        Number b = numeric(right);
        if (isBig(a) || isBig(b)) {
            return decimal(a).compareTo(decimal(b));
        }
        if (isFloating(a) || isFloating(b)) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return Long.compare(a.longValue(), b.longValue());
    }

    /** {@code char} se comporte comme un nombre en arithmétique et en comparaison, comme en Java. */
    private static boolean isNumeric(Object value) {
        return value instanceof Number || value instanceof Character;
    }

    private static Number numeric(Object value) {
        return value instanceof Character c ? (int) c : (Number) value;
    }

    private static boolean isFloating(Number n) {
        return n instanceof Double || n instanceof Float;
    }

    private static boolean isBig(Number n) {
        return n instanceof BigDecimal || n instanceof BigInteger;
    }

    private static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f);
            default -> BigDecimal.valueOf(n.longValue());
        };
    }

    private static PjException unsupported(Operator op, Object operand) {
        return new PjException("« " + op.symbol() + " » impossible sur " + describe(operand));
    }

    private static PjException unsupported(Operator op, Object left, Object right) {
        return new PjException("« " + op.symbol() + " » impossible entre " + describe(left) + " et " + describe(right));
    }

    /** {@code null}, ou type et valeur abrégée : {@code String "abc"}. */
    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = Values.text(value);
        if (text.length() > 30) {
            text = text.substring(0, 27) + "…";
        }
        return value.getClass().getSimpleName() + (value instanceof CharSequence ? " \"" + text + "\"" : " " + text);
    }
}
