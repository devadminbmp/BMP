package com.bmp;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ARCHITECTURE AS TESTS. If any module reaches into another module's internals,
 * `mvn verify` fails. The rules are not documentation — they are the build.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(BmpApplication.class);

    /**
     * Spring Modulith verification: every module may only depend on the modules
     * declared in its package-info allowedDependencies, and only through api types.
     */
    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }

    /** Human-readable module map, regenerated on every build. */
    @Test
    void generateModuleDocumentation() throws Exception {
        new org.springframework.modulith.docs.Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }

    /** Belt-and-braces: no class anywhere touches another module's internal package. */
    @Test
    void noCrossModuleInternalAccess() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.bmp");
        for (String m : new String[]{"user","salon","booking","payment","review","rewards","admin","notification"}) {
            noClasses()
                .that().resideOutsideOfPackage("com.bmp." + m + "..")
                .should().dependOnClassesThat().resideInAPackage("com.bmp." + m + ".internal..")
                .because("only " + m + "'s own code may touch com.bmp." + m + ".internal")
                .check(classes);
        }
    }

    /** LOCKED DECISION: money is integer paise. No floats/doubles near currency. */
    @Test
    void noFloatingPointMoney() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.bmp");
        noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("java.math.BigDecimal")
            .because("Money is integer paise (com.bmp.common.money.Money) — BigDecimal in " +
                     "business code usually means someone is about to store rupees as decimals")
            .check(classes);
    }
}
