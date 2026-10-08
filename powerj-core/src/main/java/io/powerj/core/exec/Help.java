package io.powerj.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.powerj.api.CmdletInfo;

/** Help generated from cmdlet metadata (specification FR-29, FR-45). */
final class Help {

    /**
     * Documented built-in commands, in display order; usage and description are the keys
     * {@code help.builtin.<name>.usage} and {@code help.builtin.<name>.description}.
     */
    private static final List<String> BUILTINS = List.of("cd", "pwd", "which", "help", "import", "mod-load",
            "mod-list", "history", "exit");

    private Help() {
    }

    static boolean isDocumentedBuiltin(String name) {
        return BUILTINS.contains(name);
    }

    static List<Object> run(List<Object> args, CmdletRegistry registry, JavaClasses java) {
        if (args.isEmpty()) {
            return overview(registry);
        }
        if (args.getFirst() instanceof String word && word.equals("members")) {
            if (args.size() != 2) {
                throw new PjException(Messages.get("help.members.valueExpected"));
            }
            Object value = args.get(1);
            if (value == null) {
                throw new PjException(Messages.get("help.members.null"));
            }
            return List.copyOf(Members.of(value));
        }
        if (args.size() > 1) {
            throw new PjException(Messages.get("help.oneCommand"));
        }
        String name = Values.text(args.getFirst());
        var cmdlet = registry.find(name);
        if (cmdlet.isPresent()) {
            return List.copyOf(cmdlet(cmdlet.get()));
        }
        if (BUILTINS.contains(name)) {
            return List.of(name + " — " + builtinDescription(name), Messages.get("help.usage", builtinUsage(name)));
        }
        if (args.getFirst() instanceof Evaluator.ClassRef(var type)) {
            return javaClass(type);
        }
        var type = java.find(name);
        if (type.isPresent() && !type.get().isPrimitive()) {
            return javaClass(type.get());
        }
        throw new PjException(Messages.get("help.unknown", name));
    }

    /** Constructors, static and instance methods, public fields of a Java class (FR-54). */
    static List<Object> javaClass(Class<?> type) {
        List<Object> lines = new ArrayList<>();
        String kind = type.isAnnotation() ? "annotation" : type.isInterface() ? "interface" : type.isEnum() ? "enum"
                : type.isRecord() ? "record" : Messages.get("help.kind.class");
        lines.add(kind + " " + type.getName() + (type.getSuperclass() != null && type.getSuperclass() != Object.class
                && !type.isEnum() && !type.isRecord() ? " extends " + type.getSuperclass().getSimpleName() : ""));
        List<String> constructors = new ArrayList<>();
        if (!type.isInterface() && !java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
            for (var c : type.getConstructors()) {
                constructors.add("new " + JavaInvoker.signature(c));
            }
        }
        java.util.Set<String> statics = new java.util.TreeSet<>();
        java.util.Set<String> instance = new java.util.TreeSet<>();
        for (var m : type.getMethods()) {
            if (!JavaClasses.accessible(m.getDeclaringClass()) && !Members.isAccessible(m.getDeclaringClass())) {
                continue;
            }
            String line = JavaInvoker.signature(m) + " → " + m.getReturnType().getSimpleName();
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                if (m.getDeclaringClass() == type) {
                    statics.add(line);
                }
            } else if (m.getDeclaringClass() != Object.class) {
                instance.add(line);
            }
        }
        java.util.Set<String> fields = new java.util.TreeSet<>();
        for (var f : type.getFields()) {
            boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
            fields.add((isStatic ? "static " : "") + f.getName() + " : " + f.getType().getSimpleName());
        }
        section(lines, Messages.get("help.section.constructors"), constructors);
        section(lines, Messages.get("help.section.staticMethods"), statics);
        section(lines, Messages.get("help.section.methods"), instance);
        section(lines, Messages.get("help.section.fields"), fields);
        return lines;
    }

    private static void section(List<Object> lines, String title, java.util.Collection<String> entries) {
        if (!entries.isEmpty()) {
            lines.add("");
            lines.add(title);
            entries.forEach(e -> lines.add("  " + e));
        }
    }

    private static List<Object> overview(CmdletRegistry registry) {
        List<Object> lines = new ArrayList<>();
        lines.add(Messages.get("help.overview.builtins"));
        BUILTINS.forEach(name -> lines.add("  %-10s %s".formatted(name, builtinDescription(name))));
        Map<String, List<CmdletRegistry.Registered>> byCategory = new TreeMap<>();
        for (var registered : registry.all()) {
            byCategory.computeIfAbsent(registered.category(), _ -> new ArrayList<>()).add(registered);
        }
        byCategory.forEach((category, cmdlets) -> {
            lines.add("");
            lines.add(category);
            cmdlets.forEach(c -> lines.add("  %-10s %s".formatted(c.name(), c.summary())));
        });
        lines.add("");
        lines.add(Messages.get("help.overview.commands"));
        lines.add(Messages.get("help.overview.members"));
        lines.add(Messages.get("help.overview.programs"));
        return lines;
    }

    static List<String> cmdlet(CmdletRegistry.Registered registered) {
        CmdletInfo info = registered.info();
        List<OptionBinder.OptionSpec> specs = OptionBinder.specs(registered.parameters());
        List<String> lines = new ArrayList<>();
        lines.add(info.name() + " — " + registered.summary());
        var usage = new StringBuilder(info.name());
        specs.stream().filter(OptionBinder.OptionSpec::isPositional).forEach(s ->
                usage.append(" [").append(s.longName()).append(s.isList() ? "..." : "").append("]"));
        if (specs.stream().anyMatch(s -> !s.isPositional())) {
            usage.append(" [options]");
        }
        lines.add(Messages.get("help.usage", usage));
        lines.add("");
        lines.add(Messages.get("help.options"));
        int width = specs.stream().mapToInt(s -> optionLabel(s).length()).max().orElse(10);
        String format = "  %-" + Math.max(width, "--on-error <mode>".length()) + "s  %s";
        for (var spec : specs) {
            String description = registered.optionDescription(spec.longName(), spec.description());
            lines.add(format.formatted(optionLabel(spec),
                    spec.mandatory() ? Messages.get("help.option.mandatory", description) : description));
        }
        lines.add(format.formatted("--on-error <mode>", Messages.get("help.onError")));
        lines.add("");
        lines.add(Messages.get("help.output", registered.output().getSimpleName() + outputComponents(registered.output())));
        lines.add(Messages.get("help.module", registered.module()));
        if (info.examples().length > 0) {
            lines.add("");
            lines.add(Messages.get("help.examples"));
            for (String example : info.examples()) {
                lines.add("  " + example);
            }
        }
        return lines;
    }

    private static String builtinUsage(String name) {
        return Messages.get("help.builtin." + name + ".usage");
    }

    private static String builtinDescription(String name) {
        return Messages.get("help.builtin." + name + ".description");
    }

    private static String optionLabel(OptionBinder.OptionSpec spec) {
        String label = spec.isPositional() ? "<" + spec.longName() + ">" : spec.display();
        if (!spec.isFlag() && !spec.isPositional()) {
            label += " " + Messages.get("help.option.value");
        }
        return label;
    }

    private static String outputComponents(Class<?> output) {
        if (!output.isRecord()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (var c : output.getRecordComponents()) {
            names.add(c.getName());
        }
        return " (" + String.join(", ", names) + ")";
    }
}
