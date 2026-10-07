package io.powerj.api;

import java.util.List;

/**
 * Fournit des cmdlets au shell. Déclaré par un module via {@code provides io.powerj.api.CmdletProvider
 * with …} et découvert par {@link java.util.ServiceLoader}.
 */
public interface CmdletProvider {

    List<Cmdlet<?, ?, ?>> cmdlets();
}
