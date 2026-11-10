package io.powerj.core.command;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import io.powerj.api.Option;
import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Units;

/**
 * Builds a cmdlet's parameter record from the arguments entered, Unix style
 * (specification FR-18 to FR-20): {@code -r}, {@code -ra}, {@code --recurse}, {@code --filter *.java},
 * {@code --filter=*.java}, positionals, {@code --} end of options. A long option is written whole, in the case of the
 * help: {@code --rec} is not {@code --recurse}. A {@code boolean} value is {@code true} or {@code false}.
 */
public final class OptionBinder {

    /** Description of an option, derived from a record component. */
    public record OptionSpec(RecordComponent component, char shortName, String longName, boolean mandatory,
                             int position, String description) {

        public boolean isFlag() {
            return component.getType() == boolean.class || component.getType() == Boolean.class;
        }

        public boolean isList() {
            return component.getType() == List.class;
        }

        public boolean isPositional() {
            return position >= 0;
        }

        /** Does the cmdlet say that Tab completion offers file paths here? ({@code @Option(path = true)}) */
        public boolean isPath() {
            Option option = component.getAnnotation(Option.class);
            return option != null && option.path();
        }

        /** Displayed form: {@code -r, --recurse}. */
        public String display() {
            return (shortName != '\0' ? "-" + shortName + ", " : "    ") + "--" + longName;
        }
    }

    private OptionBinder() {
    }

    /** A negative number is an argument, not an option: {@code -5}, {@code -1.5}. */
    private static final Pattern NEGATIVE_NUMBER = Pattern.compile("-\\d+(\\.\\d+)?");
    /** Where a camel-case name is cut into words: {@code onError} gives {@code on-Error}. */
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    /**
     * What a parameter record declares: its options in component order, and the indexes (in that list) of those that can
     * be given without a name, in the order of their position.
     */
    private record Declared(List<OptionSpec> specs, int[] positionals) {
    }

    /** The options of each parameter record, read once: the same list serves every command, the help and the completion. */
    private static final ClassValue<Declared> DECLARED = new ClassValue<>() {
        @Override
        protected Declared computeValue(Class<?> type) {
            List<OptionSpec> specs = new ArrayList<>();
            for (RecordComponent c : type.getRecordComponents()) {
                Option option = c.getAnnotation(Option.class);
                if (option == null) {
                    specs.add(new OptionSpec(c, '\0', kebab(c.getName()), false, -1, ""));
                } else {
                    String longName = option.longName().isEmpty() ? kebab(c.getName()) : option.longName();
                    specs.add(new OptionSpec(c, option.shortName(), longName, option.mandatory(), option.position(),
                            option.description()));
                }
            }
            int[] positionals = IntStream.range(0, specs.size()).filter(i -> specs.get(i).isPositional()).boxed()
                    .sorted(Comparator.comparingInt(i -> specs.get(i).position())).mapToInt(Integer::intValue).toArray();
            return new Declared(List.copyOf(specs), positionals);
        }
    };

    /** Options declared by a parameter record, in component order. The list is shared and cannot be changed. */
    public static List<OptionSpec> specs(Class<? extends Record> type) {
        return DECLARED.get(type).specs();
    }

    /**
     * @param command name of the command, for messages
     * @param args    evaluated arguments: words ({@code String}) or expression values
     */
    public static <P extends Record> P bind(String command, Class<P> type, List<Object> args) {
        Declared declared = DECLARED.get(type);
        Binding binding = new Binding(command, declared, args);
        binding.readArguments();
        binding.checkMandatoryAndFillDefaults();
        return construct(command, type, declared.specs(), binding.values);
    }

    /** The reading of one command line: the values given so far, and where the next argument is. */
    private static final class Binding {
        final String command;
        final List<OptionSpec> specs;
        final List<Object> args;
        final Object[] values;
        final boolean[] given;
        final int[] positionals;
        int cursor; // the next argument to read
        int nextPositional;
        boolean optionsEnded;

        Binding(String command, Declared declared, List<Object> args) {
            this.command = command;
            this.specs = declared.specs();
            this.args = args;
            this.values = new Object[specs.size()];
            this.given = new boolean[specs.size()];
            this.positionals = declared.positionals();
        }

        void readArguments() {
            while (cursor < args.size()) {
                Object arg = args.get(cursor++);
                if (!optionsEnded && arg instanceof String word && isOption(word)) {
                    if (word.equals("--")) {
                        optionsEnded = true;
                    } else if (word.startsWith("--")) {
                        longOption(word);
                    } else {
                        shortOptions(word);
                    }
                } else {
                    positional(arg);
                }
            }
        }

        /** {@code --name}, {@code --name=value}, {@code --name value}: the name is exact. */
        private void longOption(String word) {
            String name = word.substring(2);
            String inline = null;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                inline = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            int index = byLongName(command, specs, name);
            OptionSpec spec = specs.get(index);
            if (spec.isFlag()) {
                values[index] = inline == null || parseBoolean(command, spec, inline);
            } else if (inline != null) {
                values[index] = add(values[index], convert(command, spec, inline));
            } else {
                if (cursor >= args.size()) {
                    throw new PjException(Messages.get("option.valueExpected.long", command, spec.longName()));
                }
                values[index] = add(values[index], convert(command, spec, args.get(cursor++)));
            }
            given[index] = true;
        }

        /** Short options, possibly grouped: {@code -ra}, {@code -f *.java}, {@code -fvalue}. */
        private void shortOptions(String word) {
            String letters = word.substring(1);
            for (int k = 0; k < letters.length(); k++) {
                int index = byShortName(command, specs, letters.charAt(k));
                OptionSpec spec = specs.get(index);
                given[index] = true;
                if (spec.isFlag()) {
                    values[index] = Boolean.TRUE;
                    continue;
                }
                String rest = letters.substring(k + 1);
                if (!rest.isEmpty()) {
                    values[index] = add(values[index], convert(command, spec, rest));
                } else if (cursor < args.size()) {
                    values[index] = add(values[index], convert(command, spec, args.get(cursor++)));
                } else {
                    throw new PjException(Messages.get("option.valueExpected.short", command, spec.shortName()));
                }
                break;
            }
        }

        private void positional(Object arg) {
            if (nextPositional >= positionals.length) {
                throw new PjException(Messages.get("option.unexpectedArgument", command, Values.text(arg)));
            }
            int index = positionals[nextPositional];
            OptionSpec spec = specs.get(index);
            if (given[index] && !spec.isList()) {
                // the option already received its value by name: move on to the next positional, with the same argument
                nextPositional++;
                cursor--;
                return;
            }
            values[index] = add(values[index], convert(command, spec, arg));
            given[index] = true;
            if (!spec.isList()) {
                nextPositional++;
            }
        }

        void checkMandatoryAndFillDefaults() {
            for (int i = 0; i < specs.size(); i++) {
                OptionSpec spec = specs.get(i);
                if (spec.mandatory() && !given[i]) {
                    throw new PjException(Messages.get("option.missingMandatory", command, spec.longName()));
                }
                if (values[i] == null) {
                    values[i] = defaultValue(spec.component().getType());
                }
            }
        }
    }

    /** {@code -x}, {@code --xx}, {@code --}; but not a negative number nor {@code -} alone. */
    private static boolean isOption(String word) {
        return word.length() > 1 && word.charAt(0) == '-' && !NEGATIVE_NUMBER.matcher(word).matches();
    }

    /** Index of the option written as the help shows it: the name is exact (no other case, no beginning of a name). */
    private static int byLongName(String command, List<OptionSpec> specs, String name) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).longName().equals(name)) {
                return i;
            }
        }
        Optional<String> suggestion = suggestion(name, specs.stream().map(OptionSpec::longName).toList());
        throw new PjException(suggestion.isPresent()
                ? Messages.get("option.unknown.long.suggestion", command, name, suggestion.get())
                : Messages.get("option.unknown.long", command, name));
    }

    /**
     * The name to suggest to someone who wrote {@code text}, which is none: a hint, never a match.
     * <ul>
     * <li>exactly one name begins with {@code text}: it is the beginning of that name;</li>
     * <li>several do: none is suggested, the error does not pick one (Tab shows them);</li>
     * <li>none does: a typo, so the nearest name within two edits, if there is one.</li>
     * </ul>
     */
    private static Optional<String> suggestion(String text, List<String> names) {
        List<String> beginning = text.isEmpty() ? List.of() : names.stream().filter(n -> n.startsWith(text)).toList();
        if (beginning.isEmpty()) {
            return closest(text, names);
        }
        return beginning.size() == 1 ? Optional.of(beginning.getFirst()) : Optional.empty();
    }

    private static int byShortName(String command, List<OptionSpec> specs, char letter) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).shortName() == letter) {
                return i;
            }
        }
        throw new PjException(Messages.get("option.unknown.short", command, letter));
    }

    /** Closest name (edit distance ≤ 2), the case apart: it is a hint for a name written in the wrong case, not a match. */
    static Optional<String> closest(String name, List<String> candidates) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : candidates) {
            int d = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static boolean parseBoolean(String command, OptionSpec spec, String text) {
        return switch (text) { // exactly the two words of the language: no yes, no, oui, non, 1, 0, and no other case
            case "true" -> true;
            case "false" -> false;
            default -> throw new PjException(Messages.get("option.booleanExpected", command, spec.longName(), text));
        };
    }

    @SuppressWarnings("unchecked")
    private static Object add(Object current, Object value) {
        if (current instanceof List<?> list && value instanceof List<?> more) {
            var merged = new ArrayList<>((List<Object>) list);
            merged.addAll(more);
            return merged;
        }
        return value;
    }

    /** Converts a value to the component's type (FR-20). */
    private static Object convert(String command, OptionSpec spec, Object value) {
        Class<?> type = spec.component().getType();
        if (type == List.class) {
            Class<?> element = elementType(spec.component().getGenericType());
            List<Object> values = new ArrayList<>();
            if (value instanceof Collection<?> items) { // not any Iterable: a Path is one value
                for (Object item : items) {
                    values.add(convertScalar(command, spec, element, item));
                }
            } else {
                values.add(convertScalar(command, spec, element, value));
            }
            return values;
        }
        return convertScalar(command, spec, type, value);
    }

    private static Class<?> elementType(Type type) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertScalar(String command, OptionSpec spec, Class<?> type, Object value) {
        if (value == null || type.isInstance(value)) {
            return value; // including Object: value passed as is ({ } block, object)
        }
        String text = Values.text(value);
        try {
            if (type == String.class) {
                return text;
            }
            if (type == Duration.class) {
                return Units.parse(text).filter(Duration.class::isInstance).orElseThrow(
                        () -> new PjException(Messages.get("option.durationExpected", command, spec.longName(), text)));
            }
            if (type == Path.class) {
                return value instanceof Path p ? p : Path.of(text);
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(text);
            }
            if (type == long.class || type == Long.class) {
                // size with unit: 10kb, 1.5mb (FR-19)
                return Units.parse(text).filter(Long.class::isInstance).orElseGet(() -> Long.valueOf(text));
            }
            if (type == double.class || type == Double.class) {
                return Double.valueOf(text);
            }
            if (type == boolean.class || type == Boolean.class) {
                return parseBoolean(command, spec, text);
            }
            if (type.isEnum()) {
                // The value of an enumerated option is spelled in lower case (as completion and help show it), exactly.
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().toLowerCase(Locale.ROOT).equals(text)) {
                        return constant;
                    }
                }
                throw new PjException(Messages.get("option.enumExpected", command, spec.longName(),
                        Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT), text));
            }
        } catch (NumberFormatException | InvalidPathException e) {
            throw new PjException(Messages.get("option.invalidValue", command, spec.longName(), text));
        }
        throw new PjException(Messages.get("option.unsupportedType", command, type.getSimpleName()));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == List.class) {
            return List.of();
        }
        return null;
    }

    private static <P extends Record> P construct(String command, Class<P> type, List<OptionSpec> specs, Object[] values) {
        Class<?>[] types = specs.stream().map(s -> s.component().getType()).toArray(Class<?>[]::new);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof List<?> list) {
                values[i] = List.copyOf(list);
            }
        }
        try {
            Constructor<P> constructor = type.getDeclaredConstructor(types);
            constructor.trySetAccessible();
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new PjException(PjError.of(Messages.get("command.error", command, cause.getMessage()), cause));
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of(Messages.get("option.unusableParameters", command, e), e));
        }
    }

    /** {@code onError} → {@code on-error}. */
    private static String kebab(String name) {
        return CAMEL_BOUNDARY.matcher(name).replaceAll("$1-$2").toLowerCase(Locale.ROOT);
    }
}
