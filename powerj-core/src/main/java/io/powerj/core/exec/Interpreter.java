package io.powerj.core.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Connector;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.StringPart;
import io.powerj.core.lang.Token;

/**
 * Exécute une ligne : instructions enchaînées par {@code ;}, {@code &&}, {@code ||} (FR-04c), commandes
 * internes, commandes natives, expressions, affectations et redirections.
 */
public final class Interpreter {

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CommandResolver resolver;
    private final NativeRunner nativeRunner;

    /**
     * @param extraBuiltins commandes internes fournies par le shell (ex. {@code history})
     */
    public Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins) {
        this(session, io, extraBuiltins, new CommandResolver(), new NativeRunner());
    }

    Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins,
                CommandResolver resolver, NativeRunner nativeRunner) {
        this.session = session;
        this.io = io;
        this.resolver = resolver;
        this.nativeRunner = nativeRunner;
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("exit", Builtins::exit);
        builtins.put("which", this::which);
        builtins.putAll(extraBuiltins);
    }

    public Session session() {
        return session;
    }

    /**
     * Exécute la ligne. Une erreur dans une instruction est affichée et compte comme un échec (pour
     * {@code &&} / {@code ||}) ; une erreur de syntaxe empêche toute exécution.
     *
     * @throws InterruptedException si la ligne a été annulée par Ctrl+C
     */
    public void execute(String line) throws InterruptedException {
        Ast.Script script = Parser.parse(line);
        boolean succeeded = true;
        for (Ast.Step step : script.steps()) {
            if (session.exitRequest().isPresent()) {
                return;
            }
            boolean run = switch (step.connector()) {
                case ALWAYS -> true;
                case IF_SUCCESS -> succeeded;
                case IF_FAILURE -> !succeeded;
            };
            if (run) {
                succeeded = statement(step.statement());
                session.recordSuccess(succeeded);
            }
        }
    }

    private boolean statement(Statement statement) throws InterruptedException {
        try {
            if (statement.assignTo().isPresent() && redirect(statement, Token.Stream.OUT).isPresent()) {
                throw new PjException("une affectation ne peut pas rediriger sa sortie avec >");
            }
            Evaluation result = evaluate(statement);
            if (statement.assignTo().isPresent()) {
                session.setVariable(statement.assignTo().get(), single(result.values()));
            } else if (!result.alreadyWritten()) {
                output(result.values(), redirect(statement, Token.Stream.OUT));
            }
            return result.succeeded();
        } catch (PjException e) {
            reportError(e.error().message(), redirect(statement, Token.Stream.ERR));
            return false;
        }
    }

    /**
     * @param alreadyWritten {@code true} si la sortie a déjà été écrite (commande native : terminal ou fichier)
     */
    private record Evaluation(List<Object> values, boolean succeeded, boolean alreadyWritten) {
        Evaluation(List<Object> values, boolean succeeded) {
            this(values, succeeded, false);
        }
    }

    private Evaluation evaluate(Statement statement) throws InterruptedException {
        return switch (statement.body()) {
            case Ast.ExpressionBody(var expression) -> {
                Object value = evaluate(expression);
                yield new Evaluation(Collections.singletonList(value), !(value instanceof Boolean b) || b);
            }
            case Ast.Command command -> command(command, statement);
        };
    }

    private Evaluation command(Ast.Command command, Statement statement) throws InterruptedException {
        List<String> args = arguments(command.arguments());
        Builtin builtin = command.forceNative() ? null : builtins.get(command.name());
        if (builtin != null) {
            try {
                return new Evaluation(builtin.run(args, session), true);
            } catch (PjException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new PjException(PjError.of(command.name() + " : " + e.getMessage(), e));
            }
        }
        Path executable = resolver.resolve(command.name(), session).orElseThrow(() -> new PjException(
                (command.forceNative() ? "commande native introuvable : " : "commande inconnue : ") + command.name()));
        var result = nativeRunner.run(executable, args, session, io, statement.assignTo().isPresent(),
                redirect(statement, Token.Stream.OUT), redirect(statement, Token.Stream.ERR));
        session.recordNative(result.run());
        return new Evaluation(new ArrayList<>(result.capturedLines()), result.run().succeeded(), true);
    }

    private List<Object> which(List<String> names, Session session) {
        if (names.isEmpty()) {
            throw new PjException("which : nom de commande attendu");
        }
        List<Object> lines = new ArrayList<>();
        for (String name : names) {
            if (builtins.containsKey(name)) {
                lines.add(name + " → commande interne");
            } else {
                Path path = resolver.resolve(name, session)
                        .orElseThrow(() -> new PjException("which : introuvable : " + name));
                lines.add(name + " → natif " + path);
            }
        }
        return lines;
    }

    private List<String> arguments(List<Argument> arguments) {
        List<String> values = new ArrayList<>(arguments.size());
        for (Argument argument : arguments) {
            values.add(argumentText(argument));
        }
        return values;
    }

    private String argumentText(Argument argument) {
        return switch (argument) {
            case Ast.WordArgument(var text) -> text;
            case Ast.ExpressionArgument(var expression) -> Values.text(evaluate(expression));
        };
    }

    private Object evaluate(Expression expression) {
        return switch (expression) {
            case Ast.Literal(var value) -> value;
            case Ast.VariableExpression(var name, var accessors) ->
                    PropertyAccess.apply(session.variable(name), accessors);
            case Ast.StringExpression(var parts) -> {
                var text = new StringBuilder();
                for (StringPart part : parts) {
                    switch (part) {
                        case StringPart.Text(var t) -> text.append(t);
                        case StringPart.Interpolation(var name, var accessors) ->
                                text.append(Values.text(PropertyAccess.apply(session.variable(name), accessors)));
                    }
                }
                yield text.toString();
            }
        };
    }

    /** Valeur d'une affectation (FR-31) : aucune → null, une → elle-même, plusieurs → liste. */
    private static Object single(List<Object> values) {
        return switch (values.size()) {
            case 0 -> null;
            case 1 -> values.getFirst();
            default -> Collections.unmodifiableList(new ArrayList<>(values));
        };
    }

    private Optional<NativeRunner.FileTarget> redirect(Statement statement, Token.Stream stream) {
        Ast.Redirect last = null;
        for (Ast.Redirect redirect : statement.redirects()) {
            if (redirect.stream() == stream) {
                last = redirect;
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        Path file = session.currentDirectory().resolve(argumentText(last.target()));
        return Optional.of(new NativeRunner.FileTarget(file, last.append()));
    }

    private void output(List<Object> values, Optional<NativeRunner.FileTarget> target) {
        List<String> lines = new ArrayList<>();
        for (Object value : values) {
            lines.addAll(Values.lines(value));
        }
        if (target.isPresent()) {
            write(target.get(), lines);
        } else if (!lines.isEmpty()) {
            lines.forEach(io.out()::println);
            io.out().flush();
        }
    }

    private void reportError(String message, Optional<NativeRunner.FileTarget> target) {
        if (target.isPresent()) {
            write(target.get(), List.of(message));
        } else {
            io.errors().accept(message);
        }
    }

    private static void write(NativeRunner.FileTarget target, List<String> lines) {
        try {
            if (target.append()) {
                Files.write(target.file(), lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.write(target.file(), lines, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new PjException(PjError.of("écriture impossible dans " + target.file() + " : " + e.getMessage(), e));
        }
    }
}
