package io.powerj.architecture;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;

/**
 * Architecture rules of PowerJ (review §5.1, finding C-32).
 * <p>
 * Plain rules describe what is true today and must stay true. {@code freeze(…)} rules describe the target: the
 * violations that exist are recorded in {@code src/test/resources/archunit_store} and tolerated, a new one fails the
 * build. When a violation is fixed, the stored line disappears by itself; when a rule has no violation left, replace
 * {@code freeze(rule)} by {@code rule} and delete its store file.
 * <p>
 * The rules are plain JUnit tests ({@code rule.check(classes)}) and not {@code @ArchTest} fields: the ArchUnit engine
 * is not reported by Surefire, and a violated rule did not fail the build.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.powerj");
    }

    private static void check(ArchRule rule) {
        rule.check(classes);
    }

    // --- Dependencies between the modules (what the module-info.java files say, checked on the classes) ---

    @Test
    void apiDependsOnlyOnTheJdk() {
        check(classes().that().resideInAPackage("io.powerj.api..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("io.powerj.api..", "java..", "javax..")
                .because("powerj-api is the only dependency of a third-party module: it must not drag anything in"));
    }

    @Test
    void fsDependsOnlyOnTheJdk() {
        check(classes().that().resideInAPackage("io.powerj.fs..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("io.powerj.fs..", "java..", "javax..")
                .because("powerj-fs has no dependency: a third-party module can use it too"));
    }

    @Test
    void cmdletsAreIndependentOfTheEngine() {
        check(noClasses().that().resideInAPackage("io.powerj.cmdlets..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.powerj.core..", "io.powerj.shell..", "io.powerj.fx..")
                .because("the built-in cmdlets must work exactly like a third-party module: through powerj-api only"));
    }

    @Test
    void fxDependsOnlyOnTheApi() {
        check(classes().that().resideInAPackage("io.powerj.fx..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage("io.powerj.fx..", "io.powerj.api..", "java..", "javax..", "javafx..")
                .because("powerj-fx is a model of a real third-party module, shipped apart from the distribution"));
    }

    @Test
    void coreDoesNotKnowItsClients() {
        check(noClasses().that().resideInAPackage("io.powerj.core..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.powerj.cmdlets..", "io.powerj.shell..", "io.powerj.fx.."));
    }

    @Test
    void shellDoesNotKnowTheOptionalModule() {
        check(noClasses().that().resideInAPackage("io.powerj.shell..")
                .should().dependOnClassesThat().resideInAPackage("io.powerj.fx.."));
    }

    @Test
    void modulesHaveNoCycle() {
        check(slices().matching("io.powerj.(*)..").should().beFreeOfCycles());
    }

    // --- Target architecture: violations frozen (finding in brackets) ---

    /**
     * [C-05] {@code lang} is the language: it needs nothing of the rest of the product, only the JDK and the
     * localization of the API ({@code io.powerj.api.Language}, for its error messages) (review §5.1).
     */
    @Test
    void langDependsOnlyOnTheJdkAndTheApi() {
        check(classes().that().resideInAPackage("io.powerj.core.lang..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("io.powerj.core.lang..", "io.powerj.api..",
                        "java..", "javax.."));
    }

    /**
     * [C-04, C-05] The packages of the core are layered, and a lower layer never reaches into a higher one: this keeps
     * them free of cycles across the layers. {@code runtime} must not know {@code perf} or {@code assist}, which plug
     * into it. Dependencies inside a layer are checked by {@link #corePackagesHaveNoCycle()}.
     */
    @Test
    void corePackagesRespectTheLayers() {
        check(layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Base").definedBy("io.powerj.core.error..", "io.powerj.core.i18n..", "io.powerj.core.lang..")
                .layer("Services").definedBy("io.powerj.core.format..", "io.powerj.core.java..",
                        "io.powerj.core.nativeexec..")
                .layer("Command").definedBy("io.powerj.core.command..")
                .layer("Runtime").definedBy("io.powerj.core.runtime..")
                .layer("Plugged in").definedBy("io.powerj.core.perf..", "io.powerj.core.assist..")
                .whereLayer("Plugged in").mayNotBeAccessedByAnyLayer()
                .whereLayer("Runtime").mayOnlyBeAccessedByLayers("Plugged in")
                .whereLayer("Command").mayOnlyBeAccessedByLayers("Runtime", "Plugged in")
                .whereLayer("Services").mayOnlyBeAccessedByLayers("Command", "Runtime", "Plugged in"));
    }

    /** [C-05] No cycle between the packages of the core. */
    @Test
    void corePackagesHaveNoCycle() {
        check(slices().matching("io.powerj.core.(*)..").should().beFreeOfCycles());
    }

    /** [C-06] The completion reads the commands through {@code CommandCatalog}, never the interpreter itself. */
    @Test
    void assistDoesNotUseTheConcreteInterpreter() {
        check(noClasses().that().resideInAPackage("io.powerj.core.assist..")
                .should().dependOnClassesThat().haveFullyQualifiedName("io.powerj.core.runtime.Interpreter"));
    }

    /**
     * [C-09] Only {@code Environment}, {@code SystemTerminals} and {@code Main} read the process around the shell
     * (variables, properties, standard streams, console): everything else is given what it needs, so a shell can be
     * started in a test with a folder and a few streams.
     */
    @Test
    void shellReadsTheProcessInThreePlacesOnly() {
        check(noClasses().that().resideInAPackage("io.powerj.shell..")
                .and().doNotHaveSimpleName("Environment").and().doNotHaveSimpleName("SystemTerminals")
                .and().doNotHaveSimpleName("Main")
                .should().callMethodWhere(target(owner(assignableTo(System.class)))
                        .and(target(nameMatching("getenv|getProperty|setProperty|console|exit"))))
                .orShould().accessFieldWhere(targetOwner(assignableTo(System.class)))
                .because("the process is read once, in Environment, and handed to whoever needs it"));
    }

    /** [C-09] The read loop does not know which line editor feeds it, and does not guess it from its type. */
    @Test
    void theReadLoopDoesNotKnowTheConcreteLineEditor() {
        check(noClasses().that().haveSimpleName("Repl")
                .should().dependOnClassesThat().resideInAPackage("org.jline.reader.impl..")
                .orShould().dependOnClassesThat().haveSimpleName("PreviewLineReader")
                .orShould().dependOnClassesThat().haveSimpleName("Previewer")
                .orShould().dependOnClassesThat().haveSimpleName("Perfer"));
    }

    /** [C-09] The shell has no business with the cmdlets' classes: it only finds them through the service loader. */
    @Test
    void shellDoesNotUseTheBuiltInCmdlets() {
        check(noClasses().that().resideInAPackage("io.powerj.shell..")
                .should().dependOnClassesThat().resideInAPackage("io.powerj.cmdlets.."));
    }

    /** [C-10, C-13] No global mutable state: a static field is final. */
    @Test
    void staticFieldsAreFinal() {
        check(freeze(fields().that().areStatic().should().beFinal()));
    }

    // --- Rules that already hold ---

    @Test
    void onlyMainEndsTheJvm() {
        check(noClasses().that().doNotHaveFullyQualifiedName("io.powerj.shell.Main")
                .should().callMethod(System.class, "exit", int.class)
                .orShould().callMethod(Runtime.class, "exit", int.class)
                .orShould().callMethod(Runtime.class, "halt", int.class)
                .because("the shell never leaves on its own: the exit request goes back to the read loop"));
    }

    @Test
    void fxModelDoesNotUseTheToolkitUi() {
        check(noClasses().that().resideInAPackage("io.powerj.fx.model..")
                .should().dependOnClassesThat().resideInAnyPackage("javafx.scene..", "javafx.stage..",
                        "javafx.application..", "io.powerj.fx.view..")
                .because("the model is tested without a display (docs/plans/fx-grid.md)"));
    }

    @Test
    void cmdletsAreDeclared() {
        check(classes().that().resideInAPackage("io.powerj.cmdlets..")
                .and().areTopLevelClasses().and().implement(Cmdlet.class)
                .should().beAnnotatedWith(CmdletInfo.class)
                .because("the name, category and summary of a cmdlet come from @CmdletInfo"));
    }
}
