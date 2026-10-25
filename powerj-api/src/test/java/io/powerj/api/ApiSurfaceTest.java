package io.powerj.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Snapshot of the public surface of {@code io.powerj.api}, the only package a third-party module compiles against
 * (review C-32, Phase 0.4). It stands in for japicmp until a release exists to compare with.
 * <p>
 * Any change of the public or protected API makes this test fail: that is the point. Look at the difference, decide
 * whether it is a compatible change (new method with a {@code default}, new type) or a break (removed or changed
 * member: it must first be {@code @Deprecated(forRemoval = true)} for one minor version, see CHANGELOG.md), then
 * regenerate the snapshot with {@code ./mvnw test -pl powerj-api -Dpowerj.api.update=true} and commit it with the change.
 */
class ApiSurfaceTest {

    private static final Path CLASSES = Path.of("target/classes/io/powerj/api");
    private static final Path SNAPSHOT = Path.of("src/test/resources/io/powerj/api/api-surface.txt");

    @Test
    void thePublicSurfaceIsTheCommittedOne() throws Exception {
        String actual = describe();
        if (Boolean.getBoolean("powerj.api.update")) {
            Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
            return;
        }
        String expected = Files.readString(SNAPSHOT, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            List<String> before = expected.lines().toList();
            List<String> after = actual.lines().toList();
            List<String> removed = new ArrayList<>(before);
            removed.removeAll(after);
            List<String> added = new ArrayList<>(after);
            added.removeAll(before);
            fail("""
                    The public API changed. Removed or changed (a break unless deprecated first):
                    %s
                    Added or changed:
                    %s
                    If the change is intended, regenerate the snapshot: ./mvnw test -pl powerj-api -Dpowerj.api.update=true""",
                    String.join("\n", removed), String.join("\n", added));
        }
    }

    @Test
    void theSnapshotCoversEveryClassOfThePackage() throws Exception {
        assertThat(classNames()).isNotEmpty().contains("io.powerj.api.Cmdlet", "io.powerj.api.CmdletContext",
                "io.powerj.api.Option", "io.powerj.api.Row");
    }

    private static List<String> classNames() throws IOException {
        try (Stream<Path> files = Files.list(CLASSES)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".class") && !n.equals("module-info.class")
                            && !n.equals("package-info.class"))
                    .map(n -> "io.powerj.api." + n.substring(0, n.length() - ".class".length()))
                    .sorted()
                    .toList();
        }
    }

    private static String describe() throws Exception {
        List<String> lines = new ArrayList<>();
        for (String name : classNames()) {
            Class<?> type = Class.forName(name);
            if (!isExposed(type.getModifiers())) {
                continue;
            }
            lines.add("");
            lines.add(header(type));
            List<String> members = new ArrayList<>();
            for (RecordComponent component : recordComponents(type)) {
                members.add("  component " + component.getGenericType().getTypeName() + " " + component.getName());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (isExposed(constructor.getModifiers()) && !constructor.isSynthetic()) {
                    members.add("  " + constructor.toGenericString());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (isExposed(method.getModifiers()) && !method.isSynthetic()) {
                    Object defaultValue = method.getDefaultValue();
                    members.add("  " + method.toGenericString()
                            + (defaultValue == null ? "" : " default " + render(defaultValue)));
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (isExposed(field.getModifiers()) && !field.isSynthetic()) {
                    members.add("  " + field.toGenericString());
                }
            }
            members.sort(null);
            lines.addAll(members);
        }
        return String.join("\n", lines).stripLeading() + "\n";
    }

    private static boolean isExposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static RecordComponent[] recordComponents(Class<?> type) {
        return type.isRecord() ? type.getRecordComponents() : new RecordComponent[0];
    }

    private static String header(Class<?> type) {
        StringBuilder text = new StringBuilder(type.toGenericString());
        if (type.getGenericSuperclass() != null && type.getSuperclass() != Object.class && !type.isRecord()
                && !type.isEnum()) {
            text.append(" extends ").append(type.getGenericSuperclass().getTypeName());
        }
        if (type.getGenericInterfaces().length > 0) {
            text.append(type.isInterface() ? " extends " : " implements ").append(String.join(", ",
                    Arrays.stream(type.getGenericInterfaces()).map(java.lang.reflect.Type::getTypeName).toList()));
        }
        if (type.isAnnotation()) {
            text.append(" retention=").append(type.isAnnotationPresent(java.lang.annotation.Retention.class)
                    ? type.getAnnotation(java.lang.annotation.Retention.class).value() : "CLASS");
        }
        return text.toString();
    }

    private static String render(Object value) {
        return value instanceof Object[] array ? Arrays.deepToString(array)
                : value instanceof Character c ? "'\\u%04x'".formatted((int) c)
                : value instanceof String text ? '"' + text + '"' : String.valueOf(value);
    }
}
