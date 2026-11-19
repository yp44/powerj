package io.powerj.core.command;

import java.lang.reflect.Array;
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
import io.powerj.api.ScriptBlock;
import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.format.Values;
import io.powerj.core.i18n.Messages;
import io.powerj.core.lang.Units;
import io.powerj.fs.GlobArgument;

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

        /** The type of one value of the option: the element of a list, the component itself otherwise. */
        public Class<?> valueType() {
            return isList() ? elementType(component.getGenericType()) : component.getType();
        }

        /**
         * What the help puts after the name of the option: {@code <n>}, {@code <size>}, {@code <duration>}, {@code <path>},
         * {@code {fast|safe}} for an enum, {@code <text>}, {@code <value>} for what is not one of these (audit v2, U-05).
         */
        public String valueLabel() {
            Class<?> type = valueType();
            if (type.isEnum()) {
                return Arrays.stream(type.getEnumConstants()).map(c -> ((Enum<?>) c).name().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.joining("|", "{", "}"));
            }
            return switch (type.getSimpleName()) {
                case "int", "Integer", "short", "Short", "byte", "Byte" -> "<n>";
                case "long", "Long" -> "<size>";
                case "double", "Double", "float", "Float" -> "<number>";
                case "Duration" -> "<duration>";
                case "Path" -> "<path>";
                case "char", "Character" -> "<char>";
                case "String" -> "<text>";
                default -> Messages.get("help.option.value");
            };
        }

        /** What an invalid value should have been, in words: "an integer", "a duration such as 30s"… */
        String expected() {
            Class<?> type = valueType();
            return Messages.get(switch (type.getSimpleName()) {
                case "int", "Integer" -> "option.expected.integer";
                case "short", "Short" -> "option.expected.short";
                case "byte", "Byte" -> "option.expected.byte";
                case "long", "Long" -> "option.expected.size";
                case "double", "Double", "float", "Float" -> "option.expected.number";
                case "char", "Character" -> "option.expected.char";
                case "Path" -> "option.expected.path";
                default -> "option.expected.value";
            });
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
                    throw new PjException(Messages.get(spec.isPositional() ? "option.missingMandatory.positional"
                            : "option.missingMandatory", command, spec.longName()));
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
        Optional<String> suggestion = Suggestions.forName(name, specs.stream().map(OptionSpec::longName).toList());
        throw new PjException(suggestion.isPresent()
                ? Messages.get("option.unknown.long.suggestion", command, name, suggestion.get())
                : Messages.get("option.unknown.long", command, name));
    }

    private static int byShortName(String command, List<OptionSpec> specs, char letter) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).shortName() == letter) {
                return i;
            }
        }
        // a hint: the long option that starts with the letter, if only one does, else the letters that exist
        List<String> startingWith = specs.stream().map(OptionSpec::longName).filter(n -> n.charAt(0) == letter).toList();
        if (startingWith.size() == 1) {
            throw new PjException(Messages.get("option.unknown.short.suggestion", command, letter, startingWith.getFirst()));
        }
        String letters = specs.stream().filter(sp -> sp.shortName() != '\0')
                .map(sp -> "-" + sp.shortName()).collect(java.util.stream.Collectors.joining(", "));
        throw new PjException(letters.isEmpty() ? Messages.get("option.unknown.short", command, letter)
                : Messages.get("option.unknown.short.letters", command, letter, letters));
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

    /**
     * Converts a value to the component's type (FR-20). A {@link GlobArgument} (a word whose wildcards the quotes made
     * literal) reaches only an option that says it takes paths and is typed {@code Object} or a {@code List} of
     * {@code Object}, as {@code rm}'s are: anywhere else it is the text of the word.
     */
    private static Object convert(String command, OptionSpec spec, Object value) {
        Class<?> type = spec.component().getType();
        if (value instanceof GlobArgument word && !(spec.isPath() && takesObjects(spec))) {
            value = word.text();
        }
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

    private static boolean takesObjects(OptionSpec spec) {
        Class<?> type = spec.component().getType();
        return type == Object.class
                || type == List.class && elementType(spec.component().getGenericType()) == Object.class;
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
            if (type == float.class || type == Float.class) {
                return Float.valueOf(text);
            }
            if (type == short.class || type == Short.class) {
                return Short.valueOf(text);
            }
            if (type == byte.class || type == Byte.class) {
                return Byte.valueOf(text);
            }
            if (type == char.class || type == Character.class) {
                if (text.length() != 1) {
                    throw new NumberFormatException(text); // one character, not zero or two
                }
                return text.charAt(0);
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
        } catch (NumberFormatException | ArithmeticException | InvalidPathException e) {
            throw new PjException(Messages.get("option.invalidValue", command, spec.isPositional() ? "<" + spec.longName() + ">"
                    : "--" + spec.longName(), text, spec.expected()));
        }
        throw new PjException(Messages.get("option.unsupportedType", command, type.getSimpleName()));
    }

    /** What a component holds when its option is not given: {@code 0}, {@code false} or {@code '\0'} for every primitive. */
    private static Object defaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            return Array.get(Array.newInstance(type, 1), 0);
        }
        return type == List.class ? List.of() : null;
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
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new PjException(PjError.of(Messages.get("option.unusableParameters", command, e), e));
        }
    }

    /** The scalar types an option can have; an enum is accepted too. A {@link ScriptBlock} comes from a {@code { }} block. */
    private static final java.util.Set<Class<?>> SUPPORTED = java.util.Set.of(String.class, Duration.class, Path.class,
            int.class, Integer.class, long.class, Long.class, double.class, Double.class, float.class, Float.class,
            short.class, Short.class, byte.class, Byte.class, char.class, Character.class, boolean.class,
            Boolean.class, Object.class, ScriptBlock.class);

    private static boolean supported(Class<?> type) {
        return SUPPORTED.contains(type) || type.isEnum();
    }

    /**
     * Checks that a parameter record can be bound, the first time it is used or when its cmdlet is registered: a short
     * or long name or a position given twice, a positional list that is not the last positional, a type that
     * {@link #bind} cannot convert. Each is a mistake of the cmdlet's author, which would otherwise cost one failed call
     * per user (audit v2, R-02).
     *
     * @throws IllegalArgumentException with a message that names the component
     */
    public static void validate(Class<? extends Record> type) {
        List<OptionSpec> specs = specs(type);
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (OptionSpec spec : specs) {
            String name = spec.component().getName();
            Class<?> component = spec.component().getType();
            Class<?> element = component == List.class ? elementType(spec.component().getGenericType()) : component;
            if (!supported(element)) {
                throw new IllegalArgumentException(Messages.get("option.declaration.unsupportedType", type.getSimpleName(),
                        name, element.getSimpleName()));
            }
            claim(seen, "--" + spec.longName(), name, type);
            if (spec.shortName() != '\0') {
                claim(seen, "-" + spec.shortName(), name, type);
            }
            if (spec.isPositional()) {
                claim(seen, "position " + spec.position(), name, type);
            }
        }
        int[] positionals = DECLARED.get(type).positionals();
        for (int i = 0; i < positionals.length - 1; i++) {
            OptionSpec spec = specs.get(positionals[i]);
            if (spec.isList()) {
                throw new IllegalArgumentException(Messages.get("option.declaration.listNotLast", type.getSimpleName(),
                        spec.component().getName()));
            }
        }
    }

    private static void claim(java.util.Map<String, String> seen, String key, String component, Class<?> type) {
        String owner = seen.putIfAbsent(key, component);
        if (owner != null) {
            throw new IllegalArgumentException(Messages.get("option.declaration.duplicate", type.getSimpleName(),
                    component, owner, key));
        }
    }

    /** {@code onError} → {@code on-error}. */
    private static String kebab(String name) {
        return CAMEL_BOUNDARY.matcher(name).replaceAll("$1-$2").toLowerCase(Locale.ROOT);
    }
}
