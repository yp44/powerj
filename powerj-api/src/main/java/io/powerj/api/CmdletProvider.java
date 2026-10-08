package io.powerj.api;

import java.util.List;

/**
 * Supplies cmdlets to the shell. Declared by a module via {@code provides io.powerj.api.CmdletProvider
 * with …} and discovered by {@link java.util.ServiceLoader}.
 */
public interface CmdletProvider {

    List<Cmdlet<?, ?, ?>> cmdlets();
}
