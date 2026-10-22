/**
 * PowerJ core: parsing, interpretation, command execution, Java interoperability.
 */
module io.powerj.core {
    requires transitive io.powerj.api;
    requires java.logging;
    requires java.management;
    requires static jdk.jfr;

    exports io.powerj.core;
    exports io.powerj.core.exec;
    exports io.powerj.core.lang;

    uses io.powerj.api.CmdletProvider;
}
