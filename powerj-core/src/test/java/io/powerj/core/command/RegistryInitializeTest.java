package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.CmdletProvider;

/** A provider is told once where the configuration folder is, so that it can create its default files there. */
class RegistryInitializeTest {

    record Params() { }

    @CmdletInfo(name = "one", summary = "One")
    static final class One implements Cmdlet<Params, Void, String> { }

    @CmdletInfo(name = "two", summary = "Two")
    static final class Two implements Cmdlet<Params, Void, String> { }

    @CmdletInfo(name = "three", summary = "Three")
    static final class Three implements Cmdlet<Params, Void, String> { }

    private static final class Provider implements CmdletProvider {
        final List<Cmdlet<?, ?, ?>> cmdlets;
        final List<Path> told = new ArrayList<>();
        RuntimeException toThrow;
        IOException toFail;

        Provider(Cmdlet<?, ?, ?>... cmdlets) {
            this.cmdlets = List.of(cmdlets);
        }

        @Override
        public List<Cmdlet<?, ?, ?>> cmdlets() {
            return cmdlets;
        }

        @Override
        public void initialize(Path configDirectory) throws IOException {
            told.add(configDirectory);
            if (toFail != null) {
                throw toFail;
            }
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    @TempDir
    Path tmp;

    private final CmdletRegistry registry = CmdletRegistry.of(List.of());

    private void add(Provider provider) {
        registry.addModule(getClass().getModule(), Optional.empty(), provider, provider.cmdlets);
    }

    @Test
    void aProviderIsToldWhereTheConfigurationFolderIsOnceWhateverTheNumberOfItsCmdlets() {
        var provider = new Provider(new One(), new Two());
        add(provider);

        assertThat(registry.initialize(Optional.of(tmp))).isEmpty();
        assertThat(registry.initialize(Optional.of(tmp))).isEmpty();

        assertThat(provider.told).containsExactly(tmp);
    }

    @Test
    void aProviderAddedLaterIsToldWhenAskedAgainAndTheOthersAreNot() {
        var first = new Provider(new One());
        add(first);
        registry.initialize(Optional.of(tmp));
        var second = new Provider(new Two());
        add(second);

        registry.initialize(Optional.of(tmp));

        assertThat(first.told).hasSize(1);
        assertThat(second.told).containsExactly(tmp);
    }

    @Test
    void withoutAConfigurationFolderNobodyIsTold() {
        var provider = new Provider(new One());
        add(provider);

        assertThat(registry.initialize(Optional.empty())).isEmpty();
        registry.initialize(Optional.of(tmp));

        assertThat(provider.told).containsExactly(tmp); // not consumed by the first call
    }

    @Test
    void aProviderThatCannotWriteIsReportedAndNotAskedAgainAndTheOthersAreTold() {
        var failing = new Provider(new One());
        failing.toFail = new IOException("disk full");
        var fine = new Provider(new Two());
        add(failing);
        add(fine);

        var warnings = registry.initialize(Optional.of(tmp));
        registry.initialize(Optional.of(tmp));

        assertThat(warnings).singleElement().asString().contains("disk full");
        assertThat(failing.told).hasSize(1);
        assertThat(fine.told).hasSize(1);
    }

    @Test
    void aProviderThatThrowsIsReportedLikeOneThatCannotWrite() {
        var provider = new Provider(new One());
        provider.toThrow = new IllegalStateException("bug of the module");
        add(provider);

        assertThat(registry.initialize(Optional.of(tmp))).singleElement().asString().contains("bug of the module");
    }

    @Test
    void aCmdletRegisteredWithoutAProviderIsLeftAlone() {
        registry.register(new Three());

        assertThat(registry.initialize(Optional.of(tmp))).isEmpty();
    }
}
