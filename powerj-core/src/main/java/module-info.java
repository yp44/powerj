/**
 * Cœur de PowerJ : analyse, interprétation, exécution des commandes, interopérabilité Java.
 */
module io.powerj.core {
    requires transitive io.powerj.api;
    requires java.logging;

    exports io.powerj.core;
    exports io.powerj.core.exec;
    exports io.powerj.core.lang;
}
