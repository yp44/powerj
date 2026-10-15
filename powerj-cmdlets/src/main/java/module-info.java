/**
 * Built-in PowerJ cmdlets.
 */
module io.powerj.cmdlets {
    requires io.powerj.api;
    requires io.powerj.fs;

    exports io.powerj.cmdlets;

    provides io.powerj.api.CmdletProvider with io.powerj.cmdlets.BuiltinCmdlets;
}
