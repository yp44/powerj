package io.powerj.cmdlets;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/**
 * {@code where} : ne laisse passer que les objets pour lesquels la condition est vraie (spécification FR-36).
 * <pre>
 * where { f -> f.size > 1mb && !f.dir }
 * where { $_.dir }
 * where size > 1mb                      # forme courte, équivaut à where { $_.size > 1mb }
 * </pre>
 */
@CmdletInfo(name = "where", category = "Filtres", summary = "Filtre les objets du pipeline selon une condition",
        examples = {"ls -r | where { $_.size > 1mb }", "ls | where { $_.name.endsWith(\".java\") && !$_.dir }",
                "ls | where { f -> f.size > 1mb && !f.dir }", "ls | where size > 10kb", "git status --porcelain | where { $_.startsWith(\" M \") }",
                "env | where { $_.name.startsWith(\"JAVA\") }"})
public final class Where implements Cmdlet<Where.Params, Object, Object> {

    /** Paramètres de {@code where}. */
    public record Params(
            @Option(position = 0, mandatory = true,
                    description = "Condition booléenne : lambda { f -> … }, bloc { $_… }, ou forme courte : propriété opérateur valeur")
            List<Object> condition) {
    }

    private static final Set<String> OPERATORS = Set.of("==", "!=", "<", "<=", ">", ">=");
    private static final Pattern PROPERTY = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private static final Pattern LITERAL = Pattern.compile(
            "-?\\d+(\\.\\d+)?(L|b|kb|mb|gb|tb|ms|s|m|h|d)?|true|false|null", Pattern.CASE_INSENSITIVE);

    /** Condition de chaque exécution en cours : un même cmdlet peut servir dans plusieurs pipelines à la fois. */
    private final Map<Params, ScriptBlock> conditions = new ConcurrentHashMap<>();

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        conditions.put(p, condition(p.condition(), context));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        ScriptBlock condition = conditions.computeIfAbsent(p, key -> condition(key.condition(), context));
        boolean keep;
        try {
            keep = condition.test(input);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            // FR-36 : une erreur d'évaluation est non bloquante, l'objet est ignoré.
            context.error(e.getMessage() + " (objet ignoré : " + abbreviate(input) + ")");
            return;
        }
        if (keep) {
            context.emit(input);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        conditions.remove(p);
    }

    /** Bloc donné tel quel, ou bloc compilé depuis la forme courte. */
    static ScriptBlock condition(List<Object> words, CmdletContext<?> context) {
        if (words.size() == 1 && words.getFirst() instanceof ScriptBlock block) {
            return block;
        }
        if (words.size() == 3 && words.get(0) instanceof String property && words.get(1) instanceof String operator
                && OPERATORS.contains(operator)) {
            if (!PROPERTY.matcher(property).matches()) {
                throw new IllegalArgumentException("nom de propriété invalide : " + property);
            }
            return context.compile("$_." + property + " " + operator + " " + value(words.get(2)));
        }
        throw new IllegalArgumentException("condition attendue : where { expression } ou where propriété opérateur valeur"
                + " (opérateurs : == != < <= > >=)");
    }

    /** Valeur de la forme courte en texte d'expression : littéral tel quel, sinon chaîne entre guillemets. */
    private static String value(Object value) {
        return switch (value) {
            case null -> "null";
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case String s when LITERAL.matcher(s).matches() -> s;
            case String s -> quote(s);
            default -> throw new IllegalArgumentException("valeur non prise en charge par la forme courte : "
                    + value.getClass().getSimpleName() + " ; utiliser where { … }");
        };
    }

    private static String quote(String text) {
        var quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '$' -> quoted.append("\\$");
                case '\n' -> quoted.append("\\n");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() > 40 ? text.substring(0, 39) + "…" : text;
    }
}
