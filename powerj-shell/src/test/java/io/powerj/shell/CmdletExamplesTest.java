package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.powerj.api.ScriptBlock;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.command.OptionBinder;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.StringPart;

/**
 * Every example of the help of a built-in cmdlet can be typed (audit v2, B-10; FR-45): it parses, and the options of each
 * stage that is a built-in cmdlet bind to its parameters. Nothing runs: a value computed at run time (a variable, a
 * sub-expression) is a placeholder text, a block is a block that returns nothing. {@code ls -r -p 16 C:\dev} was an
 * example of {@code ls}: it parses everywhere, but names a folder that only Windows has; the examples are portable now,
 * and {@link #noExampleNamesAWindowsDrive()} keeps them so.
 */
class CmdletExamplesTest {

    /** A block that the binder accepts and nobody calls. */
    private static final ScriptBlock BLOCK = new ScriptBlock() {
        @Override
        public Object invoke(Object current) {
            return null;
        }

        @Override
        public String source() {
            return "{ }";
        }
    };

    /** The built-in cmdlets, found by ServiceLoader as the shell finds them. */
    private static final List<CmdletRegistry.Registered> CMDLETS = CmdletRegistry.discover().all();
    private static final Map<String, Class<? extends Record>> PARAMS = new HashMap<>();

    static {
        for (CmdletRegistry.Registered cmdlet : CMDLETS) {
            PARAMS.put(cmdlet.name(), cmdlet.parameters());
        }
    }

    /** {@code cmdlet: example} for every example of every built-in cmdlet. */
    private static List<String[]> examples() {
        List<String[]> all = new ArrayList<>();
        for (CmdletRegistry.Registered cmdlet : CMDLETS) {
            for (String example : cmdlet.info().examples()) {
                all.add(new String[] {cmdlet.name(), example});
            }
        }
        return all;
    }

    /** What is wrong with {@code example}, or {@code null}: a syntax error, or a stage whose options do not bind. */
    static String problem(String example) {
        Ast.Script script;
        try {
            script = Parser.parse(example);
        } catch (RuntimeException e) {
            return "does not parse: " + e.getMessage();
        }
        List<Ast.Command> commands = new ArrayList<>();
        collect(script, commands);
        for (Ast.Command command : commands) {
            List<Object> args = new ArrayList<>();
            for (Ast.Argument argument : command.arguments()) {
                args.add(value(argument));
            }
            try {
                OptionBinder.bind(command.name(), PARAMS.get(command.name()), args);
            } catch (RuntimeException e) {
                return command.name() + ": " + e.getMessage();
            }
        }
        return commands.isEmpty() ? "names no built-in cmdlet" : null;
    }

    /** The stages that call a built-in cmdlet, in the line and in its groups {@code ( … )}, at any depth. */
    private static void collect(Object node, List<Ast.Command> commands) {
        switch (node) {
            case Ast.Command command when !command.forceNative() && PARAMS.containsKey(command.name()) -> {
                commands.add(command);
                collect(command.arguments(), commands);
            }
            case List<?> list -> list.forEach(element -> collect(element, commands));
            case java.util.Optional<?> optional -> optional.ifPresent(value -> collect(value, commands));
            case Record record -> {
                for (var component : record.getClass().getRecordComponents()) {
                    try {
                        collect(component.getAccessor().invoke(record), commands);
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                }
            }
            case null, default -> { }
        }
    }

    /** The value an argument would have, without evaluating anything. */
    private static Object value(Ast.Argument argument) {
        return switch (argument) {
            case Ast.WordArgument(var text) -> text;
            case Ast.ExpressionArgument(var expression) -> value(expression);
            case Ast.CompoundArgument _ -> "compound";
        };
    }

    private static Object value(Ast.Expression expression) {
        return switch (expression) {
            case Ast.Literal(var value) -> value;
            case Ast.BlockExpression _, Ast.Lambda _, Ast.MethodRef _ -> BLOCK;
            case Ast.StringExpression(var parts) -> parts.stream()
                    .map(part -> part instanceof StringPart.Text(var text) ? text : "x").reduce("", String::concat);
            default -> "value";
        };
    }

    @Test
    void everyExampleParsesAndBinds() {
        List<String> problems = new ArrayList<>();
        for (String[] example : examples()) {
            String problem = problem(example[1]);
            if (problem != null) {
                problems.add(example[0] + " | " + example[1] + " → " + problem);
            }
        }

        assertThat(examples()).hasSizeGreaterThan(100);
        assertThat(problems).isEmpty();
    }

    @Test
    void noExampleNamesAWindowsDrive() {
        assertThat(examples()).extracting(e -> e[0] + " | " + e[1]).noneMatch(e -> e.matches(".*\\b[A-Z]:\\\\.*"));
    }

    @Test
    void theCheckFindsWhatItIsMeantTo() {
        assertThat(problem("ls -r --filter *.java")).isNull();
        assertThat(problem("ls --recurce")).contains("--recurce");
        assertThat(problem("ls | sort size -d | first 3")).isNull();
        assertThat(problem("ls -p many")).contains("--parallel");
        assertThat(problem("ls (")).startsWith("does not parse");
        assertThat(problem("git status")).isEqualTo("names no built-in cmdlet");
    }
}
