/**
 * Example third-party module for PowerJ (specification §4.3): small sample cmdlets, each showing one aspect of the
 * extension API. The first one, {@code passthrough}, receives the objects of the pipeline.
 */
module com.example.samples {
    requires io.powerj.api;

    provides io.powerj.api.CmdletProvider with com.example.samples.SamplesProvider;
}
