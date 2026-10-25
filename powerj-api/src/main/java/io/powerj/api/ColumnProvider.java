package io.powerj.api;

import java.util.List;

/**
 * Capability of a cmdlet that emits {@link Row}s whose columns depend on its arguments ({@code select name kb={ … }}):
 * it names them while the user types, so that Tab completion offers them in the next stage
 * ({@code ls | select name kb={ … } | where { $_.kb > 1 }}).
 */
public interface ColumnProvider {

    /**
     * Names of the columns of the rows the cmdlet emits, read from the text of its arguments. Called on text that may
     * be incomplete or invalid: it must not fail. An empty list says that none is known.
     *
     * @param arguments text following the command name in the stage, as typed
     */
    List<String> columnsOf(String arguments);
}
