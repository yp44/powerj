/**
 * Built-in PowerJ cmdlets.
 */
@SuppressWarnings("module") // the shell is not on the module path when the cmdlets are compiled alone
module io.powerj.cmdlets {
    requires transitive io.powerj.api;
    requires io.powerj.fs;

    // The core binds the options of a cmdlet by reflection on its public classes (OptionBinder). Nobody else needs
    // these classes: a cmdlet is found through the provider below, which needs no export.
    exports io.powerj.cmdlets to io.powerj.core;

    provides io.powerj.api.CmdletProvider with io.powerj.cmdlets.BuiltinCmdlets;
}
