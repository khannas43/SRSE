package gov.rajasthan.smart.srse.config;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the operational plane's package scanning against silent drift.
 *
 * <p>Adding a JPA entity or repository in a package that neither list covers
 * has two failure modes, and the quiet one is the dangerous one:
 * <ul>
 *   <li>a missing REPOSITORY package fails loudly at startup
 *       ("required a bean of type ... that could not be found");</li>
 *   <li>a missing ENTITY package fails at NO point during startup — the table
 *       is simply never created by {@code hbm2ddl}, and every query against
 *       it blows up at runtime, in production, long after deploy.</li>
 * </ul>
 * Neither is reachable from a {@code @WebMvcTest} slice or a Mockito unit
 * test, which is why {@code lakehouse}'s omission survived a green suite and
 * only surfaced when the app was actually started.
 */
class OperationalDataSourceConfigTest {

    private static final String ROOT_PACKAGE = "gov.rajasthan.smart.srse";

    /** The literal list in the annotation must match the one the entity scan uses. */
    @Test
    void repositoryScanAndEntityScanCoverTheSamePackages() {
        EnableJpaRepositories annotation =
                OperationalDataSourceConfig.class.getAnnotation(EnableJpaRepositories.class);

        assertEquals(
                new TreeSet<>(List.of(OperationalDataSourceConfig.OPERATIONAL_PACKAGES)),
                new TreeSet<>(List.of(annotation.basePackages())),
                "@EnableJpaRepositories basePackages and OPERATIONAL_PACKAGES have drifted apart");
    }

    @Test
    void everyJpaEntityLivesInAScannedPackage() {
        assertAllCovered(findPackagesContaining(new AnnotationTypeFilter(Entity.class)), "@Entity");
    }

    @Test
    void everySpringDataRepositoryLivesInAScannedPackage() {
        assertAllCovered(findPackagesContaining(new AssignableTypeFilter(Repository.class)),
                "Spring Data repository");
    }

    private static void assertAllCovered(Set<String> packages, String what) {
        assertTrue(packages.size() > 0, "scan found no " + what + " classes at all — test is not testing anything");
        List<String> scanned = List.of(OperationalDataSourceConfig.OPERATIONAL_PACKAGES);
        List<String> uncovered = packages.stream()
                .filter(pkg -> scanned.stream().noneMatch(pkg::startsWith))
                .sorted()
                .toList();
        assertTrue(uncovered.isEmpty(),
                "These packages hold a " + what + " but are not in OPERATIONAL_PACKAGES, so their "
                        + "tables are never created / beans never registered: " + uncovered);
    }

    private static Set<String> findPackagesContaining(org.springframework.core.type.filter.TypeFilter filter) {
        // useDefaultFilters=false, and interfaces must be considered candidates:
        // Spring Data repositories are interfaces, which the default candidate
        // check rejects.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        scanner.addIncludeFilter(filter);
        return scanner.findCandidateComponents(ROOT_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(java.util.Objects::nonNull)
                .map(name -> name.substring(0, name.lastIndexOf('.')))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
