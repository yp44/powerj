/**
 * PowerJ core: parsing, interpretation, command execution, Java interoperability.
 */
module io.powerj.core {
    requires transitive io.powerj.api;
    requires java.logging;
    // The whole Java SE library can be called from the prompt (FR-52): the launcher starts with --module, so only
    // the modules required here are in the boot layer (java.net.http, java.sql…).
    requires java.se;

    exports io.powerj.core;
    exports io.powerj.core.exec;
    exports io.powerj.core.lang;

    uses io.powerj.api.CmdletProvider;
}
