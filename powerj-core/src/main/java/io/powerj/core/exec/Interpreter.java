package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
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
import java.util.function.Consumer;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Body;
import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.StringPart;
import io.powerj.core.lang.Token;

/**
 * Exécute une ligne : instructions enchaînées par {@code ;}, {@code &&}, {@code ||} (FR-04c), commandes
 * internes, cmdlets, commandes natives, expressions, affectations et redirections. Ordre de résolution
 * d'une commande (FR-13) : commande interne, cmdlet, programme du {@code PATH} ; {@code ^nom} force le
 * programme.
 */
public final class Interpreter {

    /** Largeur des tableaux écrits dans un fichier : pas de troncature. */
    private static final int FILE_WIDTH = 10_000;

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CmdletRegistry registry;
    private final CommandResolver resolver;
    private final NativeRunner nativeRunner;

    /**
     * @param extraBuiltins commandes internes fournies par le shell (ex. {@code history})
     */
    public Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry) {
        this(session, io, extraBuiltins, registry, new CommandResolver(), new NativeRunner());
    }

    Interpreter(Session session, ShellIo io, Map<String, Builtin> extraBuiltins, CmdletRegistry registry,
                CommandResolver resolver, NativeRunner nativeRunner) {
        this.session = session;
        this.io = io;
        this.registry = registry;
        this.resolver = resolver;
        this.nativeRunner = nativeRunner;
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("exit", Builtins::exit);
        builtins.put("which", this::which);
        builtins.put("help", (args, _) -> Help.run(args, registry));
        builtins.putAll(extraBuiltins);
    }

    public Session session() {
        return session;
    }

    public CmdletRegistry registry() {
        return registry;
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
        Optional<NativeRunner.FileTarget> errTarget = Optional.empty();
        try {
            errTarget = redirect(statement, Token.Stream.ERR);
            Optional<NativeRunner.FileTarget> outTarget = redirect(statement, Token.Stream.OUT);
            if (statement.assignTo().isPresent()) {
                if (outTarget.isPresent()) {
                    throw new PjException("une affectation ne peut pas rediriger sa sortie avec >");
                }
                List<Object> values = new ArrayList<>();
                boolean succeeded = run(statement.body(), values::add, true, Optional.empty(), errTarget);
                session.setVariable(statement.assignTo().get(), single(values));
                return succeeded;
            }
            try (var output = new Output(outTarget)) {
                return run(statement.body(), output::accept, false, outTarget, errTarget);
            }
        } catch (PjException e) {
            reportError(e.error().message(), errTarget);
            return false;
        }
    }

    /**
     * Exécute un corps d'instruction en envoyant chaque valeur produite à {@code sink}.
     *
     * @param capture {@code true} si les valeurs sont capturées (affectation, sous-expression) : la sortie
     *                d'une commande native est alors lue en lignes au lieu d'être affichée
     * @return succès de l'instruction
     */
    private boolean run(Body body, Consumer<Object> sink, boolean capture,
                        Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget)
            throws InterruptedException {
        return switch (body) {
            case Ast.ExpressionBody(var expression) -> {
                Object value = evaluate(expression);
                sink.accept(value);
                yield !(value instanceof Boolean b) || b;
            }
            case Ast.Command command -> command(command, sink, capture, outTarget, errTarget);
        };
    }

    private boolean command(Ast.Command command, Consumer<Object> sink, boolean capture,
                            Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget)
            throws InterruptedException {
        List<Object> args = arguments(command.arguments());
        if (!command.forceNative()) {
            Builtin builtin = builtins.get(command.name());
            if (builtin != null) {
                for (Object value : invokeBuiltin(command.name(), builtin, args)) {
                    sink.accept(value);
                }
                return true;
            }
            var cmdlet = registry.find(command.name());
            if (cmdlet.isPresent()) {
                if (args.contains("--help")) {
                    Help.cmdlet(cmdlet.get()).forEach(sink);
                    return true;
                }
                try {
                    return CmdletRunner.run(cmdlet.get(), args, session, errorSink(errTarget), sink);
                } catch (PjException | InterruptedException | java.util.concurrent.CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PjException(PjError.of(command.name() + " : " + e, e));
                }
            }
        }
        Path executable = resolver.resolve(command.name(), session).orElseThrow(() -> new PjException(
                (command.forceNative() ? "commande native introuvable : " : "commande inconnue : ") + command.name()));
        List<String> textArgs = args.stream().map(Values::text).toList();
        var result = nativeRunner.run(executable, textArgs, session, io, capture, outTarget, errTarget);
        session.recordNative(result.run());
        result.capturedLines().forEach(sink);
        return result.run().succeeded();
    }

    private List<Object> invokeBuiltin(String name, Builtin builtin, List<Object> args) throws InterruptedException {
        try {
            return builtin.run(args, session);
        } catch (PjException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(name + " : " + e.getMessage(), e));
        }
    }

    private List<Object> which(List<Object> names, Session session) {
        if (names.isEmpty()) {
            throw new PjException("which : nom de commande attendu");
        }
        List<Object> lines = new ArrayList<>();
        for (Object value : names) {
            String name = Values.text(value);
            if (name.startsWith("^") && name.length() > 1) {
                String program = name.substring(1);
                Path path = resolver.resolve(program, session)
                        .orElseThrow(() -> new PjException("which : programme introuvable : " + program));
                lines.add(name + " → natif " + path);
            } else if (builtins.containsKey(name)) {
                lines.add(name + " → commande interne");
            } else if (registry.find(name).isPresent()) {
                lines.add(name + " → cmdlet (" + registry.find(name).get().module() + ")");
            } else {
                Path path = resolver.resolve(name, session)
                        .orElseThrow(() -> new PjException("which : introuvable : " + name));
                lines.add(name + " → natif " + path);
            }
        }
        return lines;
    }

    private List<Object> arguments(List<Argument> arguments) throws InterruptedException {
        List<Object> values = new ArrayList<>(arguments.size());
        for (Argument argument : arguments) {
            values.add(argumentValue(argument));
        }
        return values;
    }

    private Object argumentValue(Argument argument) throws InterruptedException {
        return switch (argument) {
            case Ast.WordArgument(var text) -> text;
            case Ast.ExpressionArgument(var expression) -> evaluate(expression);
        };
    }

    private Object evaluate(Expression expression) throws InterruptedException {
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
            case Ast.SubExpression(var body, var accessors) -> {
                List<Object> values = new ArrayList<>();
                run(body, values::add, true, Optional.empty(), Optional.empty());
                yield PropertyAccess.apply(single(values), accessors);
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

    private Optional<NativeRunner.FileTarget> redirect(Statement statement, Token.Stream stream)
            throws InterruptedException {
        Ast.Redirect last = null;
        for (Ast.Redirect redirect : statement.redirects()) {
            if (redirect.stream() == stream) {
                last = redirect;
            }
        }
        if (last == null) {
            return Optional.empty();
        }
        Path file = session.currentDirectory().resolve(Values.text(argumentValue(last.target())));
        return Optional.of(new NativeRunner.FileTarget(file, last.append()));
    }

    private Consumer<String> errorSink(Optional<NativeRunner.FileTarget> errTarget) {
        return errTarget.<Consumer<String>>map(target -> message -> append(target.file(), message))
                .orElse(io.errors());
    }

    private void reportError(String message, Optional<NativeRunner.FileTarget> target) {
        if (target.isPresent()) {
            write(target.get(), message);
        } else {
            io.errors().accept(message);
        }
    }

    private static void write(NativeRunner.FileTarget target, String message) {
        if (target.append()) {
            append(target.file(), message);
        } else {
            try {
                Files.writeString(target.file(), message + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PjException(PjError.of("écriture impossible dans " + target.file() + " : " + e.getMessage(), e));
            }
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PjException(PjError.of("écriture impossible dans " + file + " : " + e.getMessage(), e));
        }
    }

    /**
     * Destination des valeurs d'une instruction : le terminal, ou le fichier d'une redirection {@code >}.
     * Le fichier n'est ouvert qu'à la première valeur : une commande native redirigée l'écrit elle-même.
     */
    private final class Output implements AutoCloseable {

        private final Optional<NativeRunner.FileTarget> target;
        private OutputFormatter formatter;
        private PrintWriter file;

        Output(Optional<NativeRunner.FileTarget> target) {
            this.target = target;
        }

        void accept(Object value) {
            formatter().accept(value);
        }

        private OutputFormatter formatter() {
            if (formatter == null) {
                if (target.isPresent()) {
                    try {
                        var options = target.get().append()
                                ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                                : new StandardOpenOption[0];
                        file = new PrintWriter(Files.newBufferedWriter(target.get().file(), StandardCharsets.UTF_8, options));
                    } catch (IOException e) {
                        throw new PjException(PjError.of("écriture impossible dans " + target.get().file()
                                + " : " + e.getMessage(), e));
                    }
                    formatter = new OutputFormatter(file, FILE_WIDTH);
                } else {
                    formatter = new OutputFormatter(io.out(), io.width().getAsInt());
                }
            }
            return formatter;
        }

        @Override
        public void close() {
            if (formatter != null) {
                formatter.close();
            }
            if (file != null) {
                file.close();
                if (file.checkError()) {
                    throw new PjException("écriture impossible dans " + target.orElseThrow().file());
                }
            }
        }
    }
}
