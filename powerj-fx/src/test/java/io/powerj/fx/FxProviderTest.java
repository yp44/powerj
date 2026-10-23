package io.powerj.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.powerj.api.CmdletInfo;

class FxProviderTest {

    private final FxProvider provider = new FxProvider();

    @Test
    void providesGrid() {
        assertThat(provider.cmdlets()).hasSize(1);
        assertThat(provider.cmdlets().get(0).getClass().getAnnotation(CmdletInfo.class).name()).isEqualTo("grid");
    }

    @Test
    void translatesTheSummaryAndTheCategory() {
        assertThat(provider.messages(Locale.ENGLISH).getString("grid.summary")).isEqualTo("Shows the objects in a window");
        assertThat(provider.messages(Locale.FRENCH).getString("grid.summary")).isEqualTo("Affiche les objets dans une fenêtre");
        assertThat(provider.messages(Locale.ENGLISH).getString("category.Graphical")).isEqualTo("Graphical");
        assertThat(provider.messages(Locale.FRENCH).getString("category.Graphical")).isEqualTo("Graphique");
    }

    @Test
    void bothLanguagesHaveTheSameKeys() {
        Set<String> english = new HashSet<>(Collections.list(provider.messages(Locale.ENGLISH).getKeys()));
        Set<String> french = new HashSet<>(Collections.list(provider.messages(Locale.FRENCH).getKeys()));
        assertThat(french).isEqualTo(english);
    }
}
