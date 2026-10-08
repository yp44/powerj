package io.powerj.core.exec;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.ExpressionParser;

/**
 * Tab completion (specification FR-21 to FR-25, FR-24b): commands, cmdlet options and values,
 * paths, variables, object members and Java API. Runs no command and calls no method:
 * types are inferred from variables, cmdlet output types and Java signatures.
 */
public final class Completions {

    /**
     * Suggestion.
     *
     * @param value       inserted text (prefixed by the current word)
     * @param display     text shown in the menu
     * @param description detail shown next to it (synopsis, type)
     * @param complete    {@code true} if the word is finished (a space follows)
     */
    public record Candidate(String value, String display, String description, boolean complete) { }

    /**
     * Result.
     *
     * @param start start position of the replaced text
     * @param word  text that the suggestions extend (without quotes for a quoted path)
     */
    public record Result(int start, String word, List<Candidate> candidates) {
        static Result none(int cursor) {
            return new Result(cursor, "", List.of());
        }
    }

    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "now", "new");
    private static final Pattern LAMBDA_PARAM = Pattern.compile("\\s*([\\p{L}_][\\p{L}\\p{N}_]*)\\s*->");
    private static final int MAX_FILES = 500;
    private static final Pattern ASSIGNMENT = Pattern.compile("\\$[\\p{L}_][\\p{L}\\p{N}_]*\\s*=(?!=)\\s*");

    private final Interpreter interpreter;
    private final JavaIndex index;
    private final NativeCommands natives;

    public Completions(Interpreter interpreter) {
        this(interpreter, new JavaIndex(), new NativeCommands());
    }

    Completions(Interpreter interpreter, JavaIndex index, NativeCommands natives) {
        this.interpreter = interpreter;
        this.index = index;
        this.natives = natives;
    }

    /** Prepares, in the background, the index of the classes imported by default. */
    public void warmUp() {
        index.warmUp(JavaClasses.DEFAULT_IMPORTS);
    }

    private Session session() {
        return interpreter.session();
    }

    // --- Analyse du contexte ---

    private enum FrameKind { COMMAND, BLOCK, EXPRESSION }

    /** Nesting level at the cursor: command, block { }, or expression parentheses. */
    private static final class Frame {
        final FrameKind kind;
        final int start;
        final Frame parent;
        final List<Integer> stageStarts = new ArrayList<>();

        Frame(FrameKind kind, int start, Frame parent) {
            this.kind = kind;
            this.start = start;
            this.parent = parent;
            stageStarts.add(start);
        }

        int stageStart() {
            return stageStarts.getLast();
        }
    }

    /** Context at the cursor. */
    private record Context(Frame frame, boolean inString, int stringStart) { }

    private static Context analyze(String line, int cursor) {
        Frame frame = new Frame(FrameKind.COMMAND, 0, null);
        int i = 0;
        while (i < cursor) {
            char c = line.charAt(i);
            switch (c) {
                case '"' -> {
                    int end = stringEnd(line, i, cursor);
                    if (end < 0) {
                        return new Context(frame, true, i);
                    }
                    i = end;
                    continue;
                }
                case '{' -> frame = new Frame(FrameKind.BLOCK, i + 1, frame);
                case '(', '[' -> {
                    boolean command = frame.kind == FrameKind.COMMAND && c == '('
                            && (i == 0 || !Character.isJavaIdentifierPart(line.charAt(i - 1)));
                    frame = new Frame(command ? FrameKind.COMMAND : FrameKind.EXPRESSION, i + 1, frame);
                }
                case '}', ')', ']' -> {
                    if (frame.parent != null) {
                        frame = frame.parent;
                    }
                }
                case '|' -> {
                    if (frame.kind == FrameKind.COMMAND) {
                        if (i + 1 < cursor && line.charAt(i + 1) == '|') {
                            frame.stageStarts.clear(); // || : nouvelle instruction
                            i++;
                        }
                        frame.stageStarts.add(i + 1);
                    }
                }
                case ';' -> {
                    if (frame.kind == FrameKind.COMMAND) {
                        frame.stageStarts.clear();
                        frame.stageStarts.add(i + 1);
                    }
                }
                case '&' -> {
                    if (frame.kind == FrameKind.COMMAND && i + 1 < cursor && line.charAt(i + 1) == '&') {
                        frame.stageStarts.clear();
                        frame.stageStarts.add(i + 2);
                        i++;
                    }
                }
                default -> { }
            }
            i++;
        }
        return new Context(frame, false, -1);
    }

    /** End (exclusive) of the string opened at {@code open}, or -1 if it is not closed before {@code limit}. */
    private static int stringEnd(String line, int open, int limit) {
        int i = open + 1;
        while (i < limit) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    // --- Point d'entrée ---

    /** Suggestions for the word that ends at the cursor. */
    public Result complete(String line, int cursor) {
        try {
            return doComplete(line, Math.min(cursor, line.length()));
        } catch (RuntimeException e) {
            return Result.none(cursor); // la complétion ne doit jamais casser la saisie
        }
    }

    private Result doComplete(String line, int cursor) {
        Context context = analyze(line, cursor);
        Frame frame = context.frame();
        if (context.inString()) {
            if (frame.kind == FrameKind.COMMAND && !isHead(line, frame, context.stringStart())) {
                return paths(line, context.stringStart(), cursor);
            }
            return Result.none(cursor);
        }
        int identStart = cursor;
        while (identStart > 0 && isIdentifierChar(line.charAt(identStart - 1))) {
            identStart--;
        }
        String fragment = line.substring(identStart, cursor);
        char before = identStart > 0 ? line.charAt(identStart - 1) : '\0';

        if (before == '$') {
            return variables(identStart - 1, "$" + fragment, frame.kind != FrameKind.COMMAND);
        }
        if (before == ':' && identStart >= 2 && line.charAt(identStart - 2) == ':') {
            String receiver = receiverText(line, identStart - 2);
            return new Result(identStart, fragment, methodReferences(typeOf(receiver, scope(line, frame))));
        }
        boolean head = frame.kind == FrameKind.COMMAND && isHead(line, frame, identStart);
        if (before == '.') {
            boolean spread = identStart >= 2 && line.charAt(identStart - 2) == '*';
            int dot = spread ? identStart - 2 : identStart - 1;
            String receiver = receiverText(line, dot);
            boolean receiverAtHead = frame.kind == FrameKind.COMMAND && isHead(line, frame, dot - receiver.length());
            if (receiverAtHead) {
                head = true;
            }
            if (frame.kind != FrameKind.COMMAND || head || isExpressionReceiver(receiver)
                    || isExpressionStage(line, frame, dot)) {
                TypeInfo type = typeOf(receiver, scope(line, frame));
                if (spread) {
                    type = elementOf(type);
                }
                List<Candidate> members = members(type);
                if (!members.isEmpty() || frame.kind != FrameKind.COMMAND || head) {
                    return new Result(identStart, fragment, members);
                }
            }
        }
        if (frame.kind != FrameKind.COMMAND || isExpressionStage(line, frame, identStart)) {
            return identifiers(line, frame, identStart, fragment);
        }
        if (head) {
            return commands(line, frame, identStart, fragment);
        }
        return arguments(line, frame, cursor);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Is the current stage an expression ({@code new java.io.F}, {@code Math.max(…)}) rather than a command? */
    private boolean isExpressionStage(String line, Frame frame, int at) {
        int start = frame.stageStart();
        while (start < at && io.powerj.core.lang.Lexer.isBlank(line.charAt(start))) {
            start++;
        }
        Matcher assignment = ASSIGNMENT.matcher(line).region(start, at);
        if (assignment.lookingAt()) {
            start = assignment.end(); // $x = … : c'est ce qui suit le = qui compte
        }
        return start < at && io.powerj.core.lang.Lexer.expressionAt(line, start, session().java()::isStaticReference);
    }

    /** Is the word starting at {@code at} the first one of its stage (command name)? */
    private static boolean isHead(String line, Frame frame, int at) {
        String before = line.substring(frame.stageStart(), at).strip();
        return before.isEmpty() || before.equals("^") || before.matches("\\$[\\p{L}_][\\p{L}\\p{N}_]*\\s*=");
    }

    /** As a command argument, {@code x.} is an expression only for a variable, a call or a class. */
    private boolean isExpressionReceiver(String receiver) {
        return receiver.startsWith("$") || receiver.endsWith(")") || receiver.endsWith("]")
                || session().java().find(receiver).isPresent();
    }

    /** Text of the expression preceding the dot (or {@code ::}) at {@code dot}. */
    private static String receiverText(String line, int dot) {
        int i = dot;
        while (i > 0) {
            char c = line.charAt(i - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.' || c == '$') {
                i--;
            } else if (c == '*' && i < line.length() && line.charAt(i) == '.') {
                i--;
            } else if (c == ')' || c == ']') {
                int open = matchingBackward(line, i - 1);
                if (open < 0) {
                    break;
                }
                i = open;
            } else if (c == '"') {
                int open = line.lastIndexOf('"', i - 2);
                if (open < 0) {
                    break;
                }
                i = open;
            } else {
                break;
            }
        }
        return line.substring(i, dot);
    }

    private static int matchingBackward(String line, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // --- Commandes, options, arguments ---

    private Result commands(String line, Frame frame, int identStart, String fragment) {
        String before = line.substring(frame.stageStart(), identStart).strip();
        List<Candidate> candidates = new ArrayList<>();
        if (!before.equals("^")) {
            for (String builtin : new TreeSet<>(interpreter.builtinNames())) {
                candidates.add(new Candidate(builtin, builtin + " [interne]", "commande interne", true));
            }
            for (var cmdlet : interpreter.registry().all()) {
                candidates.add(new Candidate(cmdlet.name(), cmdlet.name() + " [pj]", cmdlet.info().summary(), true));
            }
            if (!fragment.isEmpty() && Character.isUpperCase(fragment.charAt(0))) {
                importedClasses().forEach(c -> candidates.add(new Candidate(c, c, "classe Java", false)));
            }
        }
        for (String name : natives.names(session().environment())) {
            candidates.add(new Candidate(name, name + " [natif]", "programme", true));
        }
        return new Result(identStart, fragment, candidates);
    }

    private Result arguments(String line, Frame frame, int cursor) {
        int wordStart = cursor;
        while (wordStart > frame.stageStart() && !isArgumentBoundary(line.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = line.substring(wordStart, cursor);
        List<String> words = splitWords(line.substring(frame.stageStart(), wordStart));
        String command = words.getFirst();
        boolean forceNative = command.startsWith("^");
        String name = forceNative ? command.substring(1) : command;
        var cmdlet = forceNative ? Optional.<CmdletRegistry.Registered>empty() : interpreter.registry().find(name);

        if (name.equals("import")) {
            return imports(wordStart, word);
        }
        if (cmdlet.isPresent()) {
            List<OptionBinder.OptionSpec> specs = OptionBinder.specs(cmdlet.get().parameters());
            if (word.startsWith("-")) {
                return new Result(wordStart, word, options(specs, words));
            }
            Optional<OptionBinder.OptionSpec> expecting = valueOf(specs, words.getLast());
            if (expecting.isPresent()) {
                return optionValues(expecting.get(), line, wordStart, cursor);
            }
            // Paramètre positionnel textuel (souvent un chemin : ls docs) ; pas pour un bloc (where, map).
            boolean pathPositional = specs.stream().anyMatch(s -> s.isPositional()
                    && (s.component().getType() == Path.class || s.component().getType() == String.class
                        || isPathList(s) || s.isList() && s.component().getGenericType().getTypeName().contains("String")));
            return pathPositional ? paths(line, wordStart, cursor) : Result.none(cursor);
        }
        if (interpreter.builtinNames().contains(name) && !name.equals("cd")) {
            return Result.none(cursor);
        }
        return paths(line, wordStart, cursor); // cd et commandes natives : chemins (FR-25)
    }

    private static boolean isArgumentBoundary(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '|' || c == ';' || c == '(' || c == '>';
    }

    private static List<String> splitWords(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.strip().split("[\\s\\u00a0\\u202f]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            words.add("");
        }
        return words;
    }

    private static boolean isPathList(OptionBinder.OptionSpec spec) {
        return spec.isList() && spec.component().getGenericType().getTypeName().contains("Path");
    }

    /** Options of the cmdlet, excluding those already typed (FR-22). */
    private static List<Candidate> options(List<OptionBinder.OptionSpec> specs, List<String> words) {
        List<Candidate> candidates = new ArrayList<>();
        for (var spec : specs) {
            if (spec.isPositional() && spec.shortName() == '\0' && !spec.isFlag()) {
                continue; // purement positionnel
            }
            boolean used = words.stream().anyMatch(w -> w.equals("--" + spec.longName())
                    || w.startsWith("--" + spec.longName() + "=")
                    || (spec.shortName() != '\0' && w.matches("-[^-]*" + spec.shortName() + "[^-]*")));
            if (!used || spec.isList()) {
                String label = "--" + spec.longName() + (spec.shortName() != '\0' ? ", -" + spec.shortName() : "");
                candidates.add(new Candidate("--" + spec.longName(), label, spec.description(), true));
            }
        }
        if (words.stream().noneMatch(w -> w.startsWith("--on-error"))) {
            candidates.add(new Candidate("--on-error", "--on-error", "erreurs non bloquantes : stop, continue, silent", true));
        }
        candidates.add(new Candidate("--help", "--help", "aide de la commande", true));
        return candidates;
    }

    /** Option expecting a value, designated by the last typed word ({@code --filter}, {@code -f}). */
    private static Optional<OptionBinder.OptionSpec> valueOf(List<OptionBinder.OptionSpec> specs, String word) {
        for (var spec : specs) {
            if (spec.isFlag()) {
                continue;
            }
            if (word.equals("--" + spec.longName())
                    || (spec.shortName() != '\0' && word.matches("-[^-]*" + spec.shortName()))) {
                return Optional.of(spec);
            }
        }
        return Optional.empty();
    }

    /** Values of an option according to its type (FR-23): enum constants, paths. */
    private Result optionValues(OptionBinder.OptionSpec spec, String line, int start, int cursor) {
        Class<?> type = spec.component().getType();
        if (type.isEnum()) {
            List<Candidate> candidates = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                String value = ((Enum<?>) constant).name().toLowerCase(Locale.ROOT);
                candidates.add(new Candidate(value, value, "", true));
            }
            return new Result(start, line.substring(start, cursor), candidates);
        }
        if (type == Path.class || isPathList(spec)) {
            return paths(line, start, cursor);
        }
        return Result.none(cursor);
    }

    private Result imports(int start, String word) {
        int dot = word.lastIndexOf('.');
        if (dot < 0) {
            List<Candidate> roots = new ArrayList<>();
            new TreeSet<>(index.packages().stream().map(p -> p.split("\\.")[0]).toList())
                    .forEach(r -> roots.add(new Candidate(r + ".", r, "package", false)));
            return new Result(start, word, roots);
        }
        String pkg = word.substring(0, dot);
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate c : packageMembers(pkg)) {
            String suffix = c.description().equals("package") ? "." : "";
            candidates.add(new Candidate(pkg + "." + c.value() + suffix, c.display(), c.description(), c.complete()));
        }
        if (index.packages().contains(pkg)) {
            candidates.add(new Candidate(pkg + ".*", "*", "toutes les classes du package", true));
        }
        return new Result(start, word, candidates);
    }

    // --- Chemins ---

    /** File paths for the word starting at {@code start} (possibly quoted). */
    private Result paths(String line, int start, int cursor) {
        String raw = line.substring(start, cursor);
        boolean quoted = raw.startsWith("\"");
        String text = quoted ? unescape(raw.substring(1)) : raw;
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        String dirPart = text.substring(0, slash + 1);
        String prefix = text.substring(slash + 1);
        Path base;
        try {
            base = dirPart.isEmpty() ? session().currentDirectory()
                    : dirPart.startsWith("~") ? session().home().resolve(dirPart.substring(1).replaceFirst("^[/\\\\]", ""))
                    : session().currentDirectory().resolve(dirPart);
        } catch (InvalidPathException e) {
            return Result.none(cursor);
        }
        if (!Files.isDirectory(base)) {
            return Result.none(cursor);
        }
        char separator = dirPart.contains("/") ? '/' : dirPart.contains("\\") ? '\\'
                : Platform.isWindows() ? '\\' : '/';
        boolean ignoreCase = Platform.isWindows();
        List<Candidate> candidates = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(base)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean matches = ignoreCase ? name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))
                        : name.startsWith(prefix);
                if (!matches || (name.startsWith(".") && !prefix.startsWith("."))) {
                    continue;
                }
                boolean dir = Files.isDirectory(entry);
                String value = dirPart + name + (dir ? String.valueOf(separator) : "");
                candidates.add(new Candidate(value, name + (dir ? separator : ""), dir ? "dossier" : "", !dir));
                if (candidates.size() >= MAX_FILES) {
                    break;
                }
            }
        } catch (IOException | SecurityException e) {
            return Result.none(cursor);
        }
        candidates.sort(java.util.Comparator.comparing(Candidate::value));
        return new Result(start, text, candidates);
    }

    private static String unescape(String text) {
        var out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                out.append(text.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // --- Variables et identifiants ---

    private Result variables(int start, String word, boolean inBlock) {
        List<Candidate> candidates = new ArrayList<>();
        if (inBlock) {
            candidates.add(new Candidate("$_", "$_", "objet courant", false));
        }
        for (String name : session().variableNames()) {
            Object value;
            try {
                value = session().variable(name);
            } catch (PjException e) {
                continue;
            }
            candidates.add(new Candidate("$" + name, "$" + name,
                    value == null ? "null" : value.getClass().getSimpleName(), false));
        }
        return new Result(start, word, candidates);
    }

    /** In an expression: lambda parameters, imported classes, keywords. */
    private Result identifiers(String line, Frame frame, int start, String fragment) {
        List<Candidate> candidates = new ArrayList<>();
        Scope scope = scope(line, frame);
        scope.parameters().keySet().forEach(p -> candidates.add(new Candidate(p, p, "paramètre", false)));
        String before = line.substring(Math.max(0, start - 4), start);
        if (before.endsWith("new ")) {
            importedClasses().forEach(c -> candidates.add(new Candidate(c, c, "classe Java", false)));
            return new Result(start, fragment, candidates);
        }
        KEYWORDS.stream().sorted().forEach(k -> candidates.add(new Candidate(k, k, "mot-clé", false)));
        if (!fragment.isEmpty() && Character.isUpperCase(fragment.charAt(0))) {
            importedClasses().forEach(c -> candidates.add(new Candidate(c, c, "classe Java", false)));
        }
        return new Result(start, fragment, candidates);
    }

    /** Simple names of the imported classes (default imports and {@code import}). */
    private List<String> importedClasses() {
        TreeSet<String> names = new TreeSet<>();
        for (String imported : session().java().imports()) {
            if (imported.endsWith(".*")) {
                names.addAll(index.classes(imported.substring(0, imported.length() - 2)));
            } else {
                names.add(imported.substring(imported.lastIndexOf('.') + 1));
            }
        }
        return List.copyOf(names);
    }

    // --- Types ---

    /** What an expression denotes, inferred without executing it. */
    private sealed interface TypeInfo {
        record Instance(Class<?> type, Object value) implements TypeInfo { }

        /** List whose element type is known ({@code (ls)}). */
        record ListOf(Class<?> element) implements TypeInfo { }

        record Static(Class<?> type) implements TypeInfo { }

        record Package(String name) implements TypeInfo { }

        record Unknown() implements TypeInfo { }
    }

    /** Types known in a block: {@code $_} and lambda parameters. */
    private record Scope(Class<?> current, Map<String, Class<?>> parameters) { }

    private Scope scope(String line, Frame frame) {
        Map<String, Class<?>> parameters = new LinkedHashMap<>();
        Class<?> current = null;
        for (Frame f = frame; f != null; f = f.parent) {
            if (f.kind != FrameKind.BLOCK) {
                continue;
            }
            Class<?> type = f.parent != null && f.parent.kind == FrameKind.COMMAND ? upstreamType(line, f.parent) : null;
            if (current == null) {
                current = type;
            }
            Matcher param = LAMBDA_PARAM.matcher(line).region(f.start, line.length());
            if (param.lookingAt()) {
                parameters.putIfAbsent(param.group(1), type == null ? Object.class : type);
            }
        }
        return new Scope(current, parameters);
    }

    /** Type of the objects received by the current stage: output of the previous cmdlet (FR-24). */
    private Class<?> upstreamType(String line, Frame command) {
        List<Integer> starts = command.stageStarts;
        for (int k = starts.size() - 2; k >= 0; k--) {
            String stage = line.substring(starts.get(k), starts.get(k + 1) - 1).strip();
            String name = splitWords(stage).getFirst();
            if (name.startsWith("^")) {
                return String.class;
            }
            var cmdlet = interpreter.registry().find(name);
            if (cmdlet.isPresent()) {
                Class<?> output = cmdlet.get().output();
                if (output != Object.class) {
                    return output;
                }
                if (name.equals("where")) {
                    continue; // where laisse passer les objets tels quels
                }
                return null;
            }
            if (interpreter.commandKind(name) == Interpreter.CommandKind.NATIVE) {
                return String.class;
            }
            return null;
        }
        return null;
    }

    private TypeInfo typeOf(String receiver, Scope scope) {
        if (receiver.isBlank()) {
            return new TypeInfo.Unknown();
        }
        Ast.Expression expression;
        try {
            expression = ExpressionParser.parse(receiver, session().java()::isStaticReference);
        } catch (RuntimeException e) {
            return new TypeInfo.Unknown();
        }
        return typeOf(expression, scope);
    }

    private TypeInfo typeOf(Ast.Expression expression, Scope scope) {
        return switch (expression) {
            case Ast.VariableExpression(var name, var accessors) when accessors.isEmpty() -> variableType(name, scope);
            case Ast.Name(var name) -> {
                if (scope.parameters().containsKey(name)) {
                    yield instance(scope.parameters().get(name));
                }
                yield session().java().simpleClass(name).<TypeInfo>map(TypeInfo.Static::new)
                        .orElse(new TypeInfo.Package(name));
            }
            case Ast.Get(var target, var name) -> member(typeOf(target, scope), name);
            case Ast.Invoke(var target, var method, var arguments) -> invocation(typeOf(target, scope), method, arguments.size());
            case Ast.At(var target, var index) -> elementOf(typeOf(target, scope), index);
            case Ast.SpreadGet(var target, var name) -> {
                TypeInfo element = member(elementOf(typeOf(target, scope)), name);
                yield element instanceof TypeInfo.Instance(var type, _) ? new TypeInfo.ListOf(type) : new TypeInfo.Unknown();
            }
            case Ast.New(var type, _) -> session().java().find(type).<TypeInfo>map(c -> instance(c))
                    .orElse(new TypeInfo.Unknown());
            case Ast.Cast(var type, _) -> session().java().find(type).<TypeInfo>map(c -> instance(c))
                    .orElse(new TypeInfo.Unknown());
            case Ast.StringExpression _ -> instance(String.class);
            case Ast.Literal(var value) -> value == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(value.getClass(), value);
            case Ast.SubExpression(var pipeline) -> pipelineType(pipeline);
            case Ast.ListLiteral _ -> instance(List.class);
            case Ast.Now() -> instance(java.time.Instant.class);
            default -> new TypeInfo.Unknown();
        };
    }

    private TypeInfo variableType(String name, Scope scope) {
        if (name.equals("_")) {
            return scope.current() == null ? new TypeInfo.Unknown() : instance(scope.current());
        }
        try {
            Object value = session().variable(name);
            return value == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(value.getClass(), value);
        } catch (PjException e) {
            return new TypeInfo.Unknown();
        }
    }

    /** {@code (ls)}: list of the outputs of the last cmdlet of the pipeline. */
    private TypeInfo pipelineType(Ast.Pipeline pipeline) {
        if (pipeline.stages().getLast().body() instanceof Ast.Command(var name, var forceNative, _)) {
            if (forceNative) {
                return new TypeInfo.ListOf(String.class);
            }
            var cmdlet = interpreter.registry().find(name);
            if (cmdlet.isPresent() && cmdlet.get().output() != Object.class) {
                return new TypeInfo.ListOf(cmdlet.get().output());
            }
        }
        return new TypeInfo.Unknown();
    }

    private static TypeInfo instance(Class<?> type) {
        return new TypeInfo.Instance(type, null);
    }

    private TypeInfo member(TypeInfo target, String name) {
        return switch (target) {
            case TypeInfo.Static(var type) -> {
                Optional<Field> field = JavaClasses.staticField(type, name);
                if (field.isPresent()) {
                    yield instance(field.get().getType());
                }
                yield JavaClasses.nested(type, name).<TypeInfo>map(TypeInfo.Static::new).orElse(new TypeInfo.Unknown());
            }
            case TypeInfo.Package(var prefix) -> {
                String qualified = prefix + "." + name;
                yield session().java().find(qualified).<TypeInfo>map(TypeInfo.Static::new)
                        .orElse(new TypeInfo.Package(qualified));
            }
            case TypeInfo.Instance(var type, var value) -> {
                if (value != null) {
                    try {
                        Object property = PropertyAccess.property(value, name); // getter : lecture sans effet attendu
                        yield property == null ? propertyType(type, name) : new TypeInfo.Instance(property.getClass(), property);
                    } catch (RuntimeException e) {
                        yield new TypeInfo.Unknown();
                    }
                }
                yield propertyType(type, name);
            }
            case TypeInfo.ListOf _, TypeInfo.Unknown _ -> new TypeInfo.Unknown();
        };
    }

    private static TypeInfo propertyType(Class<?> type, String name) {
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                if (c.getName().equalsIgnoreCase(name)) {
                    return instance(c.getType());
                }
            }
        }
        return Members.getter(type, name).<TypeInfo>map(m -> instance(m.getReturnType()))
                .or(() -> Members.field(type, name).map(f -> instance(f.getType())))
                .orElse(new TypeInfo.Unknown());
    }

    private static TypeInfo invocation(TypeInfo target, String method, int arity) {
        List<Method> candidates = switch (target) {
            case TypeInfo.Static(var type) -> java.util.Arrays.stream(type.getMethods())
                    .filter(m -> Modifier.isStatic(m.getModifiers()) && m.getName().equals(method)).toList();
            case TypeInfo.Instance(var type, _) -> JavaInvoker.instanceMethods(type).getOrDefault(method, List.of());
            case TypeInfo.ListOf _ -> JavaInvoker.instanceMethods(List.class).getOrDefault(method, List.of());
            default -> List.of();
        };
        return candidates.stream()
                .filter(m -> m.getParameterCount() == arity || m.isVarArgs())
                .findFirst()
                .or(() -> candidates.stream().findFirst())
                .<TypeInfo>map(m -> m.getReturnType() == void.class ? new TypeInfo.Unknown() : instance(m.getReturnType()))
                .orElse(new TypeInfo.Unknown());
    }

    private static TypeInfo elementOf(TypeInfo target) {
        return elementOf(target, null);
    }

    /** Element of a list, of an array ({@code $f[0]}) or target of a {@code *.}. */
    private static TypeInfo elementOf(TypeInfo target, Ast.Expression index) {
        return switch (target) {
            case TypeInfo.ListOf(var element) -> instance(element);
            case TypeInfo.Instance(var type, var value) when value instanceof List<?> list && !list.isEmpty() -> {
                Object element = list.getFirst();
                if (index instanceof Ast.Literal(var position) && position instanceof Integer i) {
                    int at = i < 0 ? list.size() + i : i;
                    if (at >= 0 && at < list.size()) {
                        element = list.get(at);
                    }
                }
                yield element == null ? new TypeInfo.Unknown() : new TypeInfo.Instance(element.getClass(), element);
            }
            case TypeInfo.Instance(var type, _) when type.isArray() -> instance(type.componentType());
            case TypeInfo.Instance(var type, var value) when !(value instanceof Collection<?>) && index == null ->
                    target; // *. sur une valeur seule : elle-même
            default -> new TypeInfo.Unknown();
        };
    }

    // --- Membres ---

    private List<Candidate> members(TypeInfo type) {
        return switch (type) {
            case TypeInfo.Instance(var t, _) -> instanceMembers(t);
            case TypeInfo.ListOf _ -> instanceMembers(List.class);
            case TypeInfo.Static(var t) -> staticMembers(t);
            case TypeInfo.Package(var name) -> packageMembers(name);
            case TypeInfo.Unknown _ -> List.of();
        };
    }

    /** Properties (components, getters, fields) and instance methods, with their signature (FR-24b). */
    private static List<Candidate> instanceMembers(Class<?> type) {
        List<Candidate> candidates = new ArrayList<>();
        for (Members.Member m : Members.ofType(type)) {
            if (!m.kind().equals("méthode")) {
                candidates.add(new Candidate(m.name(), m.name() + " : " + m.type(), m.kind(), false));
            }
        }
        Set<String> components = new java.util.HashSet<>();
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                components.add(c.getName());
            }
        }
        JavaInvoker.instanceMethods(type).forEach((name, methods) -> {
            if (name.equals("equals") || name.equals("hashCode")) {
                return; // bruit dans le menu
            }
            for (Method method : methods) {
                if (method.getParameterCount() == 0 && components.contains(name)) {
                    continue; // accesseur de record : déjà proposé comme propriété
                }
                candidates.add(methodCandidate(method));
            }
        });
        return candidates;
    }

    private static List<Candidate> staticMembers(Class<?> type) {
        List<Candidate> candidates = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == type) {
                candidates.add(methodCandidate(method));
            }
        }
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                candidates.add(new Candidate(field.getName(), field.getName() + " : " + field.getType().getSimpleName(),
                        "champ statique", false));
            }
        }
        for (Class<?> nested : type.getClasses()) {
            if (JavaClasses.accessible(nested)) {
                candidates.add(new Candidate(nested.getSimpleName(), nested.getSimpleName(), "classe", false));
            }
        }
        return candidates;
    }

    private static Candidate methodCandidate(Method method) {
        String value = method.getName() + (method.getParameterCount() == 0 ? "()" : "(");
        return new Candidate(value, JavaInvoker.signature(method) + " : " + method.getReturnType().getSimpleName(),
                "méthode", false);
    }

    /** Subpackages and classes of a package ({@code java.util.} → {@code List}, {@code concurrent}…). */
    private List<Candidate> packageMembers(String pkg) {
        List<Candidate> candidates = new ArrayList<>();
        TreeSet<String> subpackages = new TreeSet<>();
        for (String p : index.packages()) {
            if (p.startsWith(pkg + ".")) {
                subpackages.add(p.substring(pkg.length() + 1).split("\\.")[0]);
            }
        }
        subpackages.forEach(s -> candidates.add(new Candidate(s, s, "package", false)));
        index.classes(pkg).forEach(c -> candidates.add(new Candidate(c, c, "classe", false)));
        return candidates;
    }

    /** After {@code Classe::} or {@code $x::}: method names (and {@code new} for a class). */
    private static List<Candidate> methodReferences(TypeInfo type) {
        TreeSet<String> names = new TreeSet<>();
        switch (type) {
            case TypeInfo.Static(var t) -> {
                for (Method m : t.getMethods()) {
                    names.add(m.getName());
                }
                if (!t.isInterface() && t.getConstructors().length > 0) {
                    names.add("new");
                }
            }
            case TypeInfo.Instance(var t, _) -> names.addAll(JavaInvoker.instanceMethods(t).keySet());
            default -> { }
        }
        return names.stream().map(n -> new Candidate(n, n, "méthode", false)).toList();
    }
}
