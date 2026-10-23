package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowSettingsTest {

    @TempDir
    Path tmp;

    private Path file() {
        return tmp.resolve("fx-grid.properties");
    }

    @Test
    void theStateComesBackAsItWasSaved() {
        var state = new WindowState(120.5, 40, 1000, 640.25);
        WindowSettings.save(file(), state);
        assertThat(WindowSettings.load(file())).contains(state);
    }

    @Test
    void theFolderIsCreated() {
        Path nested = tmp.resolve("home").resolve("fx-grid.properties");
        WindowSettings.save(nested, new WindowState(0, 0, 800, 600));
        assertThat(nested).exists();
    }

    @Test
    void aNewSaveReplacesTheOldOne() {
        WindowSettings.save(file(), new WindowState(0, 0, 800, 600));
        WindowSettings.save(file(), new WindowState(10, 20, 900, 700));
        assertThat(WindowSettings.load(file())).contains(new WindowState(10, 20, 900, 700));
    }

    @Test
    void withoutAFileThereIsNothing() {
        assertThat(WindowSettings.load(file())).isEmpty();
    }

    @Test
    void aFileThatIsNotUsableGivesNothing() throws IOException {
        Files.writeString(file(), "x=0\ny=0\nwidth=10\nheight=10\n");           // too small
        assertThat(WindowSettings.load(file())).isEmpty();
        Files.writeString(file(), "x=0\ny=0\nwidth=800\n");                     // incomplete
        assertThat(WindowSettings.load(file())).isEmpty();
        Files.writeString(file(), "x=zero\ny=0\nwidth=800\nheight=600\n");      // not a number
        assertThat(WindowSettings.load(file())).isEmpty();
        Files.writeString(file(), "");
        assertThat(WindowSettings.load(file())).isEmpty();
    }

    @Test
    void aFolderInPlaceOfTheFileGivesNothingAndSavingDoesNotFail() throws IOException {
        Files.createDirectories(file());
        assertThat(WindowSettings.load(file())).isEmpty();
        WindowSettings.save(file(), new WindowState(0, 0, 800, 600)); // cannot write: no exception
    }

    @Test
    void aFileWhereTheFolderShouldBeDoesNotFailEither() throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "a file");
        WindowSettings.save(blocker.resolve("fx-grid.properties"), new WindowState(0, 0, 800, 600));
        assertThat(WindowSettings.load(blocker.resolve("fx-grid.properties"))).isEmpty();
    }

    @Test
    void theFileNameIsTheOneOfThePlan() {
        assertThat(WindowSettings.FILE_NAME).isEqualTo("fx-grid.properties");
    }
}
