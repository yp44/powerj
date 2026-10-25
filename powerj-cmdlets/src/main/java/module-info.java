/**
 * Built-in PowerJ cmdlets.
 */
@SuppressWarnings("module") // the shell is not on the module path when the cmdlets are compiled alone
module io.powerj.cmdlets {
    requires transitive io.powerj.api;
    requires io.powerj.fs;

    // The core binds the options of a cmdlet by reflection on its public classes (OptionBinder); the shell uses
    // ProtectedPaths (review C-09, step 4.5 removes that). Nobody else needs these classes: a cmdlet is found through
    // the provider below, which needs no export.
    exports io.powerj.cmdlets to io.powerj.core, io.powerj.shell;

    provides io.powerj.api.CmdletProvider with io.powerj.cmdlets.BuiltinCmdlets;
}
