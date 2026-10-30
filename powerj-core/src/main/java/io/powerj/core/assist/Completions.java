package io.powerj.core.assist;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.powerj.core.java.JavaClasses;
import io.powerj.core.java.JavaIndex;
import io.powerj.core.java.PropertyAccess;
import io.powerj.core.lang.Lexer;
import io.powerj.core.lang.Scanning;
import io.powerj.core.nativeexec.NativeCommands;
import io.powerj.core.runtime.CommandCatalog;
import io.powerj.core.runtime.Session;

/**
 * Tab completion (specification FR-21 to FR-25, FR-24b): commands, cmdlet options and values,
 * paths, variables, object members and Java API. Runs no command and calls no method:
 * types are inferred from variables, cmdlet output types and Java signatures. The one thing read from a value
 * is what {@link PropertyAccess#peek} reads: map keys, record components, public fields and the getters that the
 * preview policy knows to be pure.
 */
public final class Completions {

    private static final Logger LOG = Logger.getLogger(Completions.class.getName());

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

    private static final Pattern ASSIGNMENT = Pattern.compile("\\$[\\p{L}_][\\p{L}\\p{N}_]*\\s*=(?!=)\\s*");

    private final CommandCatalog catalog;
    private final TypeInference types;
    /** Preview mode (FR-66): only what can run in a preview is offered. */
    private volatile boolean previewMode;
    private final JavaIndex index;
    private final PathCompleter paths;
    private final NameCompleter names;
    private final MemberCompleter members;
    private final CommandCompleter commands;
    private final ArgumentCompleter arguments;

    public Completions(CommandCatalog catalog) {
        this(catalog, new JavaIndex(), new NativeCommands());
    }

    Completions(CommandCatalog catalog, JavaIndex index, NativeCommands natives) {
        this.catalog = catalog;
        this.types = new TypeInference(catalog);
        this.index = index;
        var packages = new PackageCompleter(catalog, index);
        this.paths = new PathCompleter(catalog);
        this.names = new NameCompleter(catalog, types, packages);
        this.members = new MemberCompleter(packages);
        this.commands = new CommandCompleter(catalog, natives, packages);
        this.arguments = new ArgumentCompleter(catalog, paths, packages);
    }

    /**
     * Preview mode (FR-66): commands are limited to the cmdlets declared {@code preview = true} (no built-in command,
     * no native program) and Java methods to those of the preview set.
     */
    public void previewMode(boolean on) {
        this.previewMode = on;
    }

    /** Prepares, in the background, the index of the classes imported by default. */
    public void warmUp() {
        index.warmUp(JavaClasses.DEFAULT_IMPORTS);
    }

    private Session session() {
        return catalog.session();
    }

    /** Suggestions for the word that ends at the cursor. */
    public Result complete(String line, int cursor) {
        try {
            return doComplete(line, Math.min(cursor, line.length()));
        } catch (RuntimeException e) {
            // Completion must never break the input; but a failure that leaves no trace looks like "nothing to offer"
            // (a name that starts with a dot was once lost this way, #82).
            LOG.log(Level.FINE, "Completion failed on: " + line, e);
            return Result.none(cursor);
        }
    }

    private Result doComplete(String line, int cursor) {
        LineContext context = LineContext.of(line, cursor);
        Frame frame = context.frame();
        if (context.inString()) {
            if (frame.kind == Frame.Kind.COMMAND && !LineText.isHead(line, frame, context.stringStart())) {
                return paths.paths(line, context.stringStart(), cursor);
            }
            return Result.none(cursor);
        }
        int identStart = cursor;
        while (identStart > 0 && LineText.isIdentifierChar(line.charAt(identStart - 1))) {
            identStart--;
        }
        String fragment = line.substring(identStart, cursor);
        char before = identStart > 0 ? line.charAt(identStart - 1) : '\0';

        if (before == '$') {
            return names.variables(identStart - 1, "$" + fragment, frame.kind != Frame.Kind.COMMAND);
        }
        if (before == ':' && identStart >= 2 && line.charAt(identStart - 2) == ':') {
            String receiver = LineText.receiverText(line, identStart - 2);
            return new Result(identStart, fragment, MemberCompleter.methodReferences(types.typeOf(receiver, types.scope(line, frame))));
        }
        boolean head = frame.kind == Frame.Kind.COMMAND && LineText.isHead(line, frame, identStart);
        if (before == '.') {
            boolean spread = identStart >= 2 && line.charAt(identStart - 2) == '*';
            int dot = spread ? identStart - 2 : identStart - 1;
            String receiver = LineText.receiverText(line, dot);
            boolean receiverAtHead = frame.kind == Frame.Kind.COMMAND && LineText.isHead(line, frame, dot - receiver.length());
            if (receiverAtHead) {
                head = true;
            }
            if (frame.kind != Frame.Kind.COMMAND || head || isExpressionReceiver(receiver)
                    || isExpressionStage(line, frame, dot)) {
                TypeInfo type = types.typeOf(receiver, types.scope(line, frame));
                if (spread) {
                    type = TypeInference.elementOf(type);
                }
                List<Candidate> found = members.members(type, previewMode);
                if (!found.isEmpty() || frame.kind != Frame.Kind.COMMAND || head) {
                    return new Result(identStart, fragment, found);
                }
            }
        }
        if (frame.kind != Frame.Kind.COMMAND || isExpressionStage(line, frame, identStart)) {
            return names.identifiers(line, frame, identStart, fragment);
        }
        if (head) {
            return commands.commands(line, frame, identStart, fragment, previewMode);
        }
        return arguments.arguments(line, frame, cursor);
    }

    /** Is the current stage an expression ({@code new java.io.F}, {@code Math.max(…)}) rather than a command? */
    private boolean isExpressionStage(String line, Frame frame, int at) {
        int start = frame.stageStart();
        start = Math.min(Scanning.skipBlanks(line, start), at);
        Matcher assignment = ASSIGNMENT.matcher(line).region(start, at);
        if (assignment.lookingAt()) {
            start = assignment.end(); // $x = …: what follows the = is what counts
        }
        return start < at && Lexer.expressionAt(line, start, session().java()::isStaticReference);
    }

    /** As a command argument, {@code x.} is an expression only for a variable, a call or a class. */
    private boolean isExpressionReceiver(String receiver) {
        return receiver.startsWith("$") || receiver.endsWith(")") || receiver.endsWith("]")
                || session().java().find(receiver).isPresent();
    }


}
