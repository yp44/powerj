package io.powerj.core.assist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.i18n.Messages;
import io.powerj.core.java.JavaClasses;
import io.powerj.core.java.JavaInvoker;
import io.powerj.core.java.Members;
import io.powerj.core.java.PreviewPolicy;

/** Suggestions after a dot or {@code ::}: properties, methods, static members, subpackages and classes (FR-24b). */
final class MemberCompleter {

    private final PackageCompleter packages;

    MemberCompleter(PackageCompleter packages) {
        this.packages = packages;
    }

    List<Candidate> members(TypeInfo type, boolean preview) {
        return switch (type) {
            case TypeInfo.Instance(var t, var value) when value instanceof io.powerj.api.Row row -> {
                List<Candidate> candidates = new ArrayList<>();
                row.forEach((name, cell) -> candidates.add(new Candidate(name, name, Members.Kind.PROPERTY.toString(),
                        false)));
                candidates.addAll(instanceMembers(t, preview));
                yield candidates;
            }
            case TypeInfo.Instance(var t, _) -> instanceMembers(t, preview);
            case TypeInfo.ListOf _ -> instanceMembers(List.class, preview);
            case TypeInfo.Static(var t) -> staticMembers(t, preview);
            case TypeInfo.Package(var name) -> packages.packageMembers(name);
            case TypeInfo.Unknown _ -> List.of();
        };
    }

    /** Properties (components, getters, fields) and instance methods, with their signature (FR-24b). */
    private List<Candidate> instanceMembers(Class<?> type, boolean preview) {
        List<Candidate> candidates = new ArrayList<>();
        for (Members.Member m : Members.ofType(type)) {
            if (m.kind() != Members.Kind.METHOD) {
                candidates.add(new Candidate(m.name(), m.name() + " : " + m.type(), m.kind().toString(), false));
            }
        }
        Set<String> components = new HashSet<>();
        if (type.isRecord()) {
            for (RecordComponent c : type.getRecordComponents()) {
                components.add(c.getName());
            }
        }
        JavaInvoker.instanceMethods(type).forEach((name, methods) -> {
            if (name.equals("equals") || name.equals("hashCode")) {
                return; // noise in the menu
            }
            for (Method method : methods) {
                if (method.getParameterCount() == 0 && components.contains(name)) {
                    continue; // record accessor: already offered as a property
                }
                if (preview && !PreviewPolicy.allows(method)) {
                    continue;
                }
                candidates.add(methodCandidate(method));
            }
        });
        return candidates;
    }

    private List<Candidate> staticMembers(Class<?> type, boolean preview) {
        List<Candidate> candidates = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == type
                    && (!preview || PreviewPolicy.allows(method))) {
                candidates.add(methodCandidate(method));
            }
        }
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                candidates.add(new Candidate(field.getName(), field.getName() + " : " + field.getType().getSimpleName(),
                        Messages.get("completion.staticField"), false));
            }
        }
        for (Class<?> nested : type.getClasses()) {
            if (JavaClasses.accessible(nested)) {
                candidates.add(new Candidate(nested.getSimpleName(), nested.getSimpleName(),
                        Messages.get("completion.class"), false));
            }
        }
        return candidates;
    }

    private static Candidate methodCandidate(Method method) {
        String value = method.getName() + (method.getParameterCount() == 0 ? "()" : "(");
        return new Candidate(value, JavaInvoker.signature(method) + " : " + method.getReturnType().getSimpleName(),
                Messages.get("completion.method"), false);
    }

    /** After {@code Class::} or {@code $x::}: method names (and {@code new} for a class). */
    static List<Candidate> methodReferences(TypeInfo type) {
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
        return names.stream().map(n -> new Candidate(n, n, Messages.get("completion.method"), false)).toList();
    }
}
