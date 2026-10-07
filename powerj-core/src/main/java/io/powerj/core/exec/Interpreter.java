package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.ExpressionParser;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.SyntaxException;
import io.powerj.core.lang.Token;

/**
 * Exécute une ligne : instructions enchaînées par {@code ;}, {@code &&}, {@code ||} (FR-04c), pipelines
 * {@code |}, commandes internes, cmdlets, commandes natives, expressions, affectations et redirections. Ordre de résolution
 * d'une commande (FR-13) : commande interne, cmdlet, programme du {@code PATH} ; {@code ^nom} force le
 * programme.
 */
public final class Interpreter {

    /** Largeur des tableaux écrits dans un fichier : pas de troncature. */
    private static final int FILE_WIDTH = 10_000;

    /** Attente maximale de l'arrêt des étapes d'un pipeline annulé. */
    private static final int JOIN_SECONDS = 5;

    private final Session session;
    private final ShellIo io;
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final CmdletRegistry registry;
    private final CommandResolver resolver;
    private final NativeRunner nativeRunner;
    private final Evaluator evaluator;
    /** Lignes de l'entrée standard en mode non interactif, lues par la première étape ; sinon {@code null}. */
    private Source standardInput;
    private boolean inheritStandardInput;
    /** Une erreur bloquante a eu lieu pendant la dernière ligne (mode non interactif : arrêt du script). */
    private volatile boolean blockingError;

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
        this.evaluator = new Evaluator(session, this::capture);
        builtins.put("cd", Builtins::cd);
        builtins.put("pwd", Builtins::pwd);
        builtins.put("exit", Builtins::exit);
        builtins.put("which", this::which);
        builtins.put("help", (args, _) -> Help.run(args, registry, session.java()));
        builtins.put("import", (args, _) -> importClasses(args));
        builtins.putAll(extraBuiltins);
    }

    public Session session() {
        return session;
    }

    public CmdletRegistry registry() {
        return registry;
    }

    /**
     * Mode non interactif (FR-04d) : les lignes de l'entrée standard alimentent la première étape qui lit des
     * objets, et les commandes natives en tête de pipeline lisent directement l'entrée standard.
     */
    public void useStandardInput(java.util.Iterator<String> lines) {
        this.standardInput = Source.of(lines);
        this.inheritStandardInput = true;
    }

    /**
     * Exécute la ligne. Une erreur dans une instruction est affichée et compte comme un échec (pour
     * {@code &&} / {@code ||}) ; une erreur de syntaxe empêche toute exécution.
     *
     * @throws InterruptedException si la ligne a été annulée par Ctrl+C
     */
    public void execute(String line) throws InterruptedException {
        blockingError = false;
        Ast.Script script = Parser.parse(line, session.java()::isStaticReference);
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
            errTarget = redirect(statement.pipeline(), Token.Stream.ERR);
            Optional<NativeRunner.FileTarget> outTarget = redirect(statement.pipeline(), Token.Stream.OUT);
            if (statement.assignTo().isPresent()) {
                if (outTarget.isPresent()) {
                    throw new PjException("une affectation ne peut pas rediriger sa sortie avec >");
                }
                List<Object> values = new ArrayList<>();
                boolean succeeded = pipeline(statement.pipeline(), values::add, true, Optional.empty(), errTarget);
                session.setVariable(statement.assignTo().get(), Evaluator.single(values));
                return succeeded;
            }
            try (var output = new Output(outTarget)) {
                return pipeline(statement.pipeline(), output::accept, false, outTarget, errTarget);
            }
        } catch (PjException e) {
            blockingError = true;
            reportError(describe(e.error()), errTarget);
            return false;
        }
    }

    /** {@code true} si la dernière ligne exécutée a rencontré une erreur bloquante (pas seulement un échec). */
    public boolean hadBlockingError() {
        return blockingError;
    }

    /** Étape préparée : arguments évalués, commande résolue. */
    private sealed interface Prepared { }

    /** Étape qui produit des objets (valeur, commande interne, cmdlet). */
    private record ObjectStep(ObjectStage stage) implements Prepared { }

    /** Étape native. */
    private record NativeStep(NativeRunner.Command command) implements Prepared { }

    @FunctionalInterface
    private interface ObjectStage {
        /**
         * @param input  objets reçus, ou {@code null} en première étape
         * @param output reçoit les objets produits
         * @param errors reçoit les erreurs non bloquantes
         * @return succès
         */
        boolean run(Source input, Consumer<Object> output, Consumer<String> errors) throws Exception;
    }

    /** Suite d'étapes exécutée par un même fil : une étape objet, ou des natives consécutives. */
    private sealed interface Segment { }

    private record ObjectSegment(ObjectStage stage) implements Segment { }

    private record NativeSegment(List<NativeRunner.Command> commands) implements Segment { }

    /**
     * Exécute un pipeline en envoyant chaque valeur produite par la dernière étape à {@code sink}. Les étapes
     * s'exécutent en parallèle (fils virtuels), reliées par des files bornées ; la dernière s'exécute dans le
     * fil appelant, qui reçoit l'interruption de Ctrl+C.
     *
     * @param capture {@code true} si les valeurs sont capturées (affectation, sous-expression) : la sortie
     *                d'une commande native est alors lue en lignes au lieu d'être affichée
     * @return succès : celui de la dernière étape, et aucune erreur bloquante dans les autres
     */
    private boolean pipeline(Ast.Pipeline pipeline, Consumer<Object> sink, boolean capture,
                             Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget)
            throws InterruptedException {
        List<Segment> segments = segments(pipeline.stages());
        Consumer<String> errors = synchronizedErrors(errorSink(errTarget));
        if (segments.size() == 1 && segments.getFirst() instanceof NativeSegment(var commands) && commands.size() == 1) {
            NativeRunner.Command command = commands.getFirst();
            var result = nativeRunner.run(command.executable(), command.args(), session, io, capture, outTarget,
                    errTarget, command.errorsToOutput(), inheritsInput());
            session.recordNative(result.run());
            result.capturedLines().forEach(sink);
            return result.run().succeeded();
        }
        Optional<NativeRunner.FileTarget> sharedErrors = shared(errTarget);
        if (segments.size() == 1) {
            return segment(segments.getFirst(), standardInput, null, sink, capture, outTarget, sharedErrors, errors);
        }

        int n = segments.size();
        List<Pipe> pipes = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            pipes.add(new Pipe());
        }
        var blockingErrors = new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> threads = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            Segment segment = segments.get(i);
            Source input = i == 0 ? standardInput : pipes.get(i - 1);
            Pipe output = pipes.get(i);
            threads.add(Thread.ofVirtual().name("powerj-etape-" + (i + 1)).unstarted(() -> {
                output.onAbort(Thread.currentThread()::interrupt);
                try {
                    segment(segment, input, output, null, false, Optional.empty(), sharedErrors, errors);
                    output.close();
                } catch (InterruptedException | java.util.concurrent.CancellationException e) {
                    closeQuietly(output);
                } catch (PjException e) {
                    blockingErrors.set(true);
                    errors.accept(describe(e.error()));
                    closeQuietly(output);
                } catch (Throwable t) {
                    blockingErrors.set(true);
                    java.util.logging.Logger.getLogger(Interpreter.class.getName())
                            .log(java.util.logging.Level.SEVERE, "Erreur interne dans une étape de pipeline", t);
                    errors.accept("erreur interne : " + t + " (détails dans le journal)");
                    closeQuietly(output);
                } finally {
                    if (input instanceof Pipe pipe) {
                        pipe.abort();
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        Pipe lastInput = pipes.getLast();
        boolean succeeded;
        try {
            succeeded = segment(segments.getLast(), lastInput, null, sink, capture, outTarget, sharedErrors, errors);
        } finally {
            lastInput.abort();
            joinAll(threads);
        }
        if (blockingErrors.get()) {
            blockingError = true;
        }
        return succeeded && !blockingErrors.get();
    }

    /** Exécute un segment ; {@code output} est la file vers l'étape suivante, ou {@code null} en fin de pipeline. */
    private boolean segment(Segment segment, Source input, Pipe output, Consumer<Object> sink, boolean capture,
                            Optional<NativeRunner.FileTarget> outTarget, Optional<NativeRunner.FileTarget> errTarget,
                            Consumer<String> errors) throws InterruptedException {
        return switch (segment) {
            case ObjectSegment(var stage) -> {
                try {
                    yield stage.run(input, output != null ? output::putUnrolled : sink, errors);
                } catch (InterruptedException | RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PjException(PjError.of(e.toString(), e));
                }
            }
            case NativeSegment(var commands) -> {
                List<NativeRun> runs = nativeRunner.runGroup(commands, session, io, input, inheritsInput(), output,
                        sink, capture, outTarget, errTarget, errors);
                synchronized (session) {
                    session.recordNative(runs.getLast());
                }
                yield runs.getLast().succeeded();
            }
        };
    }

    /** Résout les étapes et regroupe les commandes natives consécutives. */
    private List<Segment> segments(List<Ast.Stage> stages) throws InterruptedException {
        List<Segment> segments = new ArrayList<>();
        List<NativeRunner.Command> natives = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Prepared prepared = prepare(stages.get(i), i);
            if (prepared instanceof NativeStep(var command)) {
                natives.add(command);
                continue;
            }
            if (!natives.isEmpty()) {
                segments.add(new NativeSegment(List.copyOf(natives)));
                natives.clear();
            }
            segments.add(new ObjectSegment(((ObjectStep) prepared).stage()));
        }
        if (!natives.isEmpty()) {
            segments.add(new NativeSegment(List.copyOf(natives)));
        }
        return segments;
    }

    /**
     * Prépare une étape : évalue ses arguments et résout la commande (FR-13 : commande interne, cmdlet,
     * programme du {@code PATH}).
     *
     * @param index position dans le pipeline : au-delà de la première, l'étape doit lire des objets
     */
    private Prepared prepare(Ast.Stage stage, int index) throws InterruptedException {
        if (stage.body() instanceof Ast.ExpressionBody(var expression)) {
            return new ObjectStep((_, output, _) -> {
                Object value = evaluator.evaluate(expression);
                output.accept(value);
                return !(value instanceof Boolean b) || b;
            });
        }
        var command = (Ast.Command) stage.body();
        List<Object> args = arguments(command.arguments());
        if (!command.forceNative()) {
            Builtin builtin = builtins.get(command.name());
            if (builtin != null) {
                if (index > 0) {
                    throw new PjException("« " + command.name() + " » ne lit pas les objets du pipeline");
                }
                return new ObjectStep((_, output, _) -> {
                    invokeBuiltin(command.name(), builtin, args).forEach(output);
                    return true;
                });
            }
            var found = registry.find(command.name());
            if (found.isPresent()) {
                var cmdlet = found.get();
                if (args.contains("--help")) {
                    return new ObjectStep((_, output, _) -> {
                        Help.cmdlet(cmdlet).forEach(output);
                        return true;
                    });
                }
                if (index > 0 && !cmdlet.readsInput()) {
                    throw new PjException("« " + command.name() + " » ne lit pas les objets du pipeline");
                }
                return new ObjectStep((input, output, errors) -> runCmdlet(cmdlet, args,
                        cmdlet.readsInput() ? input : null, output,
                        stage.errorsToOutput() ? message -> output.accept(message) : errors));
            }
        }
        Path executable = resolver.resolve(command.name(), session).orElseThrow(() -> unknownCommand(command));
        List<String> textArgs = args.stream().map(Values::text).toList();
        return new NativeStep(new NativeRunner.Command(executable, textArgs, stage.errorsToOutput()));
    }

    /** Commande introuvable ; {@code Math.NOPE} : le champ statique manque plutôt que la commande. */
    private PjException unknownCommand(Ast.Command command) {
        if (command.forceNative()) {
            return new PjException("commande native introuvable : " + command.name());
        }
        int dot = command.name().lastIndexOf('.');
        if (dot > 0 && command.arguments().isEmpty()) {
            var owner = session.java().find(command.name().substring(0, dot));
            if (owner.isPresent()) {
                return new PjException(owner.get().getSimpleName() + " n'a pas de champ statique "
                        + command.name().substring(dot + 1));
            }
        }
        return new PjException("commande inconnue : " + command.name());
    }

    private boolean runCmdlet(CmdletRegistry.Registered cmdlet, List<Object> args, Source input,
                              Consumer<Object> output, Consumer<String> errors) throws Exception {
        try {
            return CmdletRunner.run(cmdlet, args, session, errors, output, input, this::compile);
        } catch (PjException | InterruptedException | java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new PjException(PjError.of(cmdlet.name() + " : " + e, e));
        }
    }

    /** Compile le texte d'un bloc pour un cmdlet ({@code CmdletContext.compile}). */
    private io.powerj.api.ScriptBlock compile(String source) {
        try {
            return new CompiledBlock(source.strip(), ExpressionParser.parse(source, session.java()::isStaticReference), evaluator);
        } catch (SyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Le premier process lit-il directement l'entrée standard du shell ? */
    private boolean inheritsInput() {
        return io.interactive() || inheritStandardInput;
    }

    /** {@code 2>} partagé par plusieurs process : le fichier est vidé une fois, puis chacun y ajoute. */
    private static Optional<NativeRunner.FileTarget> shared(Optional<NativeRunner.FileTarget> target) {
        if (target.isEmpty() || target.get().append()) {
            return target;
        }
        try {
            Files.writeString(target.get().file(), "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PjException(PjError.of("écriture impossible dans " + target.get().file() + " : "
                    + e.getMessage(), e));
        }
        return Optional.of(new NativeRunner.FileTarget(target.get().file(), true));
    }

    private static Consumer<String> synchronizedErrors(Consumer<String> errors) {
        Object lock = new Object();
        return message -> {
            synchronized (lock) {
                errors.accept(message);
            }
        };
    }

    private static void closeQuietly(Pipe pipe) {
        try {
            pipe.close();
        } catch (java.util.concurrent.CancellationException _) {
            // pipeline déjà arrêté
        }
    }

    /** Attend la fin des étapes (arrêtées si besoin), sans se laisser interrompre. */
    private static void joinAll(List<Thread> threads) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(JOIN_SECONDS);
        for (Thread thread : threads) {
            while (thread.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break; // étape bloquée dans un appel non interruptible : abandonnée (FR-57)
                }
                try {
                    thread.join(java.time.Duration.ofNanos(remaining));
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
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

    /** {@code import java.security.*}, {@code import javax.crypto.Cipher} ; sans argument : imports actifs (FR-47). */
    private List<Object> importClasses(List<Object> args) {
        if (args.isEmpty()) {
            return List.copyOf(session.java().imports());
        }
        for (Object arg : args) {
            session.java().addImport(Values.text(arg));
        }
        return List.of();
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
            case Ast.ExpressionArgument(var expression) -> evaluator.evaluate(expression);
        };
    }

    /** Valeurs d'un pipeline entre parenthèses : {@code (ls).name}. */
    private List<Object> capture(Ast.Pipeline pipeline) throws InterruptedException {
        if (redirect(pipeline, Token.Stream.OUT).isPresent()) {
            throw new PjException("« > » impossible dans une sous-expression ( )");
        }
        List<Object> values = new ArrayList<>();
        Optional<NativeRunner.FileTarget> errTarget = redirect(pipeline, Token.Stream.ERR);
        pipeline(pipeline, values::add, true, Optional.empty(), errTarget);
        return values;
    }

    private Optional<NativeRunner.FileTarget> redirect(Ast.Pipeline pipeline, Token.Stream stream)
            throws InterruptedException {
        Ast.Redirect last = null;
        for (Ast.Redirect redirect : pipeline.redirects()) {
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

    /**
     * Message d'une erreur bloquante ; l'exception Java d'origine est conservée dans {@code $errors} et sa
     * pile ajoutée au message si {@code $debug} vaut {@code true} (FR-43, FR-53).
     */
    private String describe(PjError error) {
        if (error.cause().isEmpty()) {
            return error.message();
        }
        Throwable cause = error.cause().get();
        session.recordError(cause);
        if (!session.debug()) {
            return error.message();
        }
        var trace = new java.io.StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        return error.message() + System.lineSeparator() + trace.toString().stripTrailing();
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
