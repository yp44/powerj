package io.powerj.core.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What a user agrees to when they let a module use native code: the bytes that will run, not the name they go by. The
 * fingerprint is the SHA-256 of the modules of a layer, each given by its name and the SHA-256 of its content: a jar
 * is hashed whole, a folder (an exploded module) by the names and the content of its files, in a fixed order. The order
 * in which the modules are given does not matter.
 */
final class ContentFingerprint {

    private ContentFingerprint() {
    }

    /**
     * @param modules the location (a jar, or a folder) of every module of the layer, by module name
     * @return the fingerprint, 64 lower-case hexadecimal digits
     * @throws IOException if a location cannot be read
     */
    static String of(Map<String, Path> modules) throws IOException {
        MessageDigest layer = sha256();
        for (var module : new TreeMap<>(modules).entrySet()) {
            layer.update(module.getKey().getBytes(StandardCharsets.UTF_8));
            layer.update((byte) 0);
            layer.update(digestOf(module.getValue()));
        }
        return HexFormat.of().formatHex(layer.digest());
    }

    private static byte[] digestOf(Path location) throws IOException {
        MessageDigest digest = sha256();
        if (Files.isDirectory(location)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(location)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                // The name is separated from the content by 0, and the content is a fixed-size digest, so that no
                // file can be made to look like another one by moving bytes from its name to its content.
                digest.update(location.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(sha256().digest(Files.readAllBytes(file)));
            }
        } else {
            digest.update(Files.readAllBytes(location));
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }
}
