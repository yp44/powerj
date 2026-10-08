package io.powerj.core.exec;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * État d'une session de shell : répertoire courant, environnement, variables, dernière commande native.
 * Utilisée depuis le fil d'exécution des commandes ; les étapes d'un pipeline la lisent en parallèle.
 */
public final class Session {

    private final Path home;
    private final Map<String, String> environment;
    private final Map<String, Object> variables = new HashMap<>();
    private Path currentDirectory;
    private Path previousDirectory;
    private NativeRun lastNative;
    private boolean lastSucceeded = true;
    private Integer exitRequest;
    private final JavaClasses java = new JavaClasses();
    /** Exceptions Java récentes, la plus récente en premier ({@code $errors}, FR-53). */
    private final Deque<Throwable> errors = new ArrayDeque<>();
    private static final int MAX_ERRORS = 20;

    /**
     * @param home             dossier utilisateur ({@code ~})
     * @param currentDirectory répertoire courant initial
     * @param environment      environnement initial (copié)
     */
    public Session(Path home, Path currentDirectory, Map<String, String> environment) {
        this.home = home.toAbsolutePath().normalize();
        this.currentDirectory = currentDirectory.toAbsolutePath().normalize();
        // Les noms de variables d'environnement ne tiennent pas compte de la casse sous Windows.
        this.environment = Platform.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
        this.environment.putAll(environment);
    }

    public Path home() {
        return home;
    }

    public Path currentDirectory() {
        return currentDirectory;
    }

    public Optional<Path> previousDirectory() {
        return Optional.ofNullable(previousDirectory);
    }

    /** Change de répertoire courant ; le dossier doit exister. */
    public void changeDirectory(Path target) {
        Path resolved = currentDirectory.resolve(target).toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            throw new PjException("cd : dossier introuvable : " + target);
        }
        if (!Files.isDirectory(resolved)) {
            throw new PjException("cd : ce n'est pas un dossier : " + target);
        }
        previousDirectory = currentDirectory;
        currentDirectory = resolved;
    }

    /** Environnement de la session, appliqué aux commandes natives (modifiable). */
    public Map<String, String> environment() {
        return environment;
    }

    public Optional<NativeRun> lastNative() {
        return Optional.ofNullable(lastNative);
    }

    void recordNative(NativeRun run) {
        lastNative = Objects.requireNonNull(run);
    }

    public boolean lastSucceeded() {
        return lastSucceeded;
    }

    void recordSuccess(boolean succeeded) {
        lastSucceeded = succeeded;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    /** Valeur d'une variable, automatique ({@code $exit}, {@code $last}, {@code $?}…) ou définie. */
    public Object variable(String name) {
        return switch (name) {
            case "?" -> lastSucceeded;
            case "exit" -> lastNative == null ? null : lastNative.exitCode();
            case "last" -> lastNative;
            case "pwd" -> currentDirectory;
            case "home" -> home;
            case "errors" -> recentErrors();
            default -> {
                if (!variables.containsKey(name)) {
                    throw new PjException("variable inconnue : $" + name);
                }
                yield variables.get(name);
            }
        };
    }

    /** Classes Java accessibles et imports de la session (FR-47). */
    public JavaClasses java() {
        return java;
    }

    /** Conserve l'exception d'origine d'une erreur pour {@code $errors} (FR-53). */
    public synchronized void recordError(Throwable error) {
        errors.addFirst(error);
        while (errors.size() > MAX_ERRORS) {
            errors.removeLast();
        }
    }

    public synchronized List<Throwable> recentErrors() {
        return List.copyOf(errors);
    }

    /** {@code $debug} : afficher la pile Java des erreurs (FR-43). */
    public boolean debug() {
        return Boolean.TRUE.equals(variables.get("debug"));
    }

    /** Demande la fermeture du shell avec ce code ({@code exit}). */
    public void requestExit(int code) {
        exitRequest = code;
    }

    public OptionalInt exitRequest() {
        return exitRequest == null ? OptionalInt.empty() : OptionalInt.of(exitRequest);
    }
}
