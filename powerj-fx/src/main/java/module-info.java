/**
 * Optional PowerJ module with the JavaFX cmdlets: {@code grid}. Installed by unpacking its zip into
 * {@code ~/.powerj/modules/} (specification §4.4); it is not part of the distribution.
 */
module io.powerj.fx {
    requires io.powerj.api;
    requires javafx.controls;

    provides io.powerj.api.CmdletProvider with io.powerj.fx.FxProvider;
}
