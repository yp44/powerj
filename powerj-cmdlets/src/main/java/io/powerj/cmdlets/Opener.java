package io.powerj.cmdlets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** Opens a file to read it: {@code Files::newInputStream}, or a test's own stream. */
@FunctionalInterface
interface Opener {

    InputStream open(Path path) throws IOException;
}
