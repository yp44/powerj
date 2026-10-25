/**
 * Built-in PowerJ cmdlets.
 */
@SuppressWarnings("module") // the shell is not on the module path when the cmdlets are compiled alone
module io.powerj.cmdlets {
    requires transitive io.powerj.api;
    requires io.powerj.fs;

    // Only the shell, for ProtectedPaths (review C-09, step 4.5 removes this). The cmdlets themselves are found through
    // the provider below, which needs no export.
    exports io.powerj.cmdlets to io.powerj.shell;

    provides io.powerj.api.CmdletProvider with io.powerj.cmdlets.BuiltinCmdlets;
}
