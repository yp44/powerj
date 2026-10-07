/**
 * Cmdlets intégrés de PowerJ.
 */
module io.powerj.cmdlets {
    requires io.powerj.api;

    exports io.powerj.cmdlets;

    provides io.powerj.api.CmdletProvider with io.powerj.cmdlets.BuiltinCmdlets;
}
