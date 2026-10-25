package io.powerj.core.command;

import java.util.List;

import io.powerj.core.runtime.Session;

/** Built-in shell command ({@code cd}, {@code pwd}, {@code which}, {@code exit}, {@code history}…). */
@FunctionalInterface
public interface Builtin {

    /**
     * @param args already evaluated arguments: words ({@code String}) or expression values
     * @return produced values (displayed, or assigned to a variable)
     */
    List<Object> run(List<Object> args, Session session) throws Exception;
}
