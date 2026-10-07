package io.powerj.core.exec;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.powerj.api.Option;
import io.powerj.core.lang.Units;

/**
 * Construit le record de paramètres d'un cmdlet à partir des arguments saisis, en style Unix
 * (spécification FR-18 à FR-20) : {@code -r}, {@code -ra}, {@code --recurse}, {@code --rec} (abrégé non
 * ambigu), {@code --filter *.java}, {@code --filter=*.java}, positionnels, {@code --} fin des options.
 */
public final class OptionBinder {

    /** Description d'une option, issue d'un composant du record. */
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

        /** Forme affichée : {@code -r, --recurse}. */
        public String display() {
            return (shortName != '\0' ? "-" + shortName + ", " : "    ") + "--" + longName;
        }
    }

    private OptionBinder() {
    }

    /** Options déclarées par un record de paramètres, dans l'ordre des composants. */
    public static List<OptionSpec> specs(Class<? extends Record> type) {
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
        return specs;
    }

    /**
     * @param command nom de la commande, pour les messages
     * @param args    arguments évalués : mots ({@code String}) ou valeurs d'expressions
     */
    public static <P extends Record> P bind(String command, Class<P> type, List<Object> args) {
        List<OptionSpec> specs = specs(type);
        Object[] values = new Object[specs.size()];
        boolean[] given = new boolean[specs.size()];
        List<OptionSpec> positionals = specs.stream().filter(OptionSpec::isPositional)
                .sorted(Comparator.comparingInt(OptionSpec::position)).toList();
        int nextPositional = 0;
        boolean optionsEnded = false;

        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (!optionsEnded && arg instanceof String word && isOption(word)) {
                if (word.equals("--")) {
                    optionsEnded = true;
                    continue;
                }
                if (word.startsWith("--")) {
                    String name = word.substring(2);
                    String inline = null;
                    int equals = name.indexOf('=');
                    if (equals >= 0) {
                        inline = name.substring(equals + 1);
                        name = name.substring(0, equals);
                    }
                    OptionSpec spec = byLongName(command, specs, name);
                    int index = specs.indexOf(spec);
                    if (spec.isFlag()) {
                        values[index] = inline == null || parseBoolean(command, spec, inline);
                    } else if (inline != null) {
                        values[index] = add(values[index], convert(command, spec, inline));
                    } else {
                        if (i + 1 >= args.size()) {
                            throw new PjException(command + " : valeur attendue après --" + spec.longName());
                        }
                        values[index] = add(values[index], convert(command, spec, args.get(++i)));
                    }
                    given[index] = true;
                } else {
                    // Options courtes, éventuellement groupées : -ra, -f *.java, -fvaleur
                    String letters = word.substring(1);
                    for (int k = 0; k < letters.length(); k++) {
                        OptionSpec spec = byShortName(command, specs, letters.charAt(k));
                        int index = specs.indexOf(spec);
                        given[index] = true;
                        if (spec.isFlag()) {
                            values[index] = Boolean.TRUE;
                            continue;
                        }
                        String rest = letters.substring(k + 1);
                        if (!rest.isEmpty()) {
                            values[index] = add(values[index], convert(command, spec, rest));
                        } else if (i + 1 < args.size()) {
                            values[index] = add(values[index], convert(command, spec, args.get(++i)));
                        } else {
                            throw new PjException(command + " : valeur attendue après -" + spec.shortName());
                        }
                        break;
                    }
                }
            } else {
                if (nextPositional >= positionals.size()) {
                    throw new PjException(command + " : argument inattendu : " + Values.text(arg));
                }
                OptionSpec spec = positionals.get(nextPositional);
                int index = specs.indexOf(spec);
                if (given[index] && !spec.isList()) {
                    // l'option a déjà reçu sa valeur par son nom : on passe au positionnel suivant
                    nextPositional++;
                    i--;
                    continue;
                }
                values[index] = add(values[index], convert(command, spec, arg));
                given[index] = true;
                if (!spec.isList()) {
                    nextPositional++;
                }
            }
        }

        for (int i = 0; i < specs.size(); i++) {
            OptionSpec spec = specs.get(i);
            if (spec.mandatory() && !given[i]) {
                throw new PjException(command + " : option obligatoire manquante : --" + spec.longName());
            }
            if (values[i] == null) {
                values[i] = defaultValue(spec.component().getType());
            }
        }
        return construct(command, type, specs, values);
    }

    /** {@code -x}, {@code --xx}, {@code --} ; mais pas un nombre négatif ni {@code -} seul. */
    private static boolean isOption(String word) {
        return word.length() > 1 && word.charAt(0) == '-' && !word.matches("-\\d+(\\.\\d+)?");
    }

    private static OptionSpec byLongName(String command, List<OptionSpec> specs, String name) {
        for (OptionSpec spec : specs) {
            if (spec.longName().equalsIgnoreCase(name)) {
                return spec;
            }
        }
        List<OptionSpec> prefixed = specs.stream()
                .filter(s -> s.longName().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))).toList();
        if (prefixed.size() == 1 && !name.isEmpty()) {
            return prefixed.getFirst();
        }
        if (prefixed.size() > 1 && !name.isEmpty()) {
            throw new PjException(command + " : option ambiguë --" + name + " : "
                    + String.join(", ", prefixed.stream().map(s -> "--" + s.longName()).toList()));
        }
        String suggestion = closest(name, specs.stream().map(OptionSpec::longName).toList())
                .map(s -> ", vouliez-vous dire --" + s + " ?").orElse("");
        throw new PjException(command + " : option inconnue --" + name + suggestion);
    }

    private static OptionSpec byShortName(String command, List<OptionSpec> specs, char letter) {
        for (OptionSpec spec : specs) {
            if (spec.shortName() == letter) {
                return spec;
            }
        }
        throw new PjException(command + " : option inconnue -" + letter);
    }

    /** Nom le plus proche (distance d'édition ≤ 2). */
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
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "oui", "yes", "1" -> true;
            case "false", "non", "no", "0" -> false;
            default -> throw new PjException(command + " : --" + spec.longName() + " attend true ou false, pas '" + text + "'");
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

    /** Convertit une valeur vers le type du composant (FR-20). */
    private static Object convert(String command, OptionSpec spec, Object value) {
        Class<?> type = spec.component().getType();
        if (type == List.class) {
            Class<?> element = elementType(spec.component().getGenericType());
            List<Object> values = new ArrayList<>();
            if (value instanceof Iterable<?> items) {
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
            return value; // dont Object : valeur transmise telle quelle (bloc { }, objet)
        }
        String text = Values.text(value);
        try {
            if (type == String.class) {
                return text;
            }
            if (type == Duration.class) {
                return Units.parse(text).filter(Duration.class::isInstance).orElseThrow(
                        () -> new PjException(command + " : durée attendue pour --" + spec.longName()
                                + " (ex. 30s, 5m, 2h, 7d), pas '" + text + "'"));
            }
            if (type == Path.class) {
                return value instanceof Path p ? p : Path.of(text);
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(text);
            }
            if (type == long.class || type == Long.class) {
                // taille avec unité : 10kb, 1.5mb (FR-19)
                return Units.parse(text).filter(Long.class::isInstance).orElseGet(() -> Long.valueOf(text));
            }
            if (type == double.class || type == Double.class) {
                return Double.valueOf(text);
            }
            if (type == boolean.class || type == Boolean.class) {
                return parseBoolean(command, spec, text);
            }
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                        return constant;
                    }
                }
                throw new PjException(command + " : --" + spec.longName() + " accepte "
                        + Arrays.toString(type.getEnumConstants()).toLowerCase(Locale.ROOT) + ", pas '" + text + "'");
            }
        } catch (NumberFormatException | InvalidPathException e) {
            throw new PjException(command + " : valeur invalide pour --" + spec.longName() + " : '" + text + "'");
        }
        throw new PjException(command + " : type d'option non géré : " + type.getSimpleName());
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
            throw new PjException(PjError.of(command + " : " + cause.getMessage(), cause));
        } catch (ReflectiveOperationException e) {
            throw new PjException(PjError.of(command + " : paramètres inutilisables : " + e, e));
        }
    }

    /** {@code onError} → {@code on-error}. */
    private static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
