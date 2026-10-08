/**
 * Example third-party module for PowerJ (specification §4.3): the {@code greet} cmdlet.
 */
module com.example.greet {
    requires io.powerj.api;

    provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider;
}
