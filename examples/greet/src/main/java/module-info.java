/**
 * Module tiers d'exemple pour PowerJ (spécification §4.3) : le cmdlet {@code greet}.
 */
module com.example.greet {
    requires io.powerj.api;

    provides io.powerj.api.CmdletProvider with com.example.greet.GreetProvider;
}
