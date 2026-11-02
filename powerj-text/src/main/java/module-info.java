/**
 * What the modules that render output share about the output contract of {@code powerj-api}: how a value is read,
 * shown and parsed, the same way in the terminal, the grid and the exports (ADR 0010).
 * <p>
 * <b>Not API.</b> The package is exported to every module because {@code powerj-fx} is loaded from
 * {@code ~/.powerj/modules} in a layer of its own, where a qualified export would not reach it. A third-party module can
 * therefore read it, but nothing here is promised to stay: it changes without deprecation, and what a third-party author
 * needs is in {@code io.powerj.api}.
 */
module io.powerj.text {
    requires io.powerj.api;

    exports io.powerj.text;
}
