package com.salkcoding.oswl.local;

import jakarta.persistence.Entity;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import javax.sql.DataSource;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Generates the PostgreSQL DDL for the current JPA entity model, used to produce the
 * {@code V1__baseline.sql} Flyway migration.
 *
 * <p>Why this exists: production runs {@code ddl-auto: validate}, so Hibernate never creates
 * tables there — Flyway does. But the migration set started at V2 (incremental ALTERs against
 * an already-populated schema), which left a brand-new PostgreSQL database with no way to get
 * a schema at all. V1 closes that hole, and generating it from the entities (rather than
 * hand-writing it) is what keeps it consistent with what {@code validate} will demand.
 *
 * <p>Not part of the build or the test suite — run on demand via
 * {@code ./gradlew generateSchemaBaseline} when the entity model changes enough to warrant a
 * refreshed baseline. The output is source material, not a drop-in: review it, then fold it
 * into a versioned migration.
 *
 * <p>Uses the JPA-standard script-only schema generation, so no database connection is made;
 * the dialect is set explicitly and the script is PostgreSQL flavored regardless of what the
 * local profile happens to run on.
 */
public final class SchemaBaselineGenerator {

    private static final String ENTITY_PACKAGE = "com.salkcoding.oswl";

    private SchemaBaselineGenerator() {}

    static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/schema-baseline.sql");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.deleteIfExists(out);

        List<String> found = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var candidate : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            found.add(candidate.getBeanClassName());
        }
        found.sort(String::compareTo);
        PersistenceUnitInfo unit = new ScriptOnlyPersistenceUnit(found);

        System.out.println("Entities discovered: " + found.size());
        found.forEach(c -> System.out.println("  " + c));

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        // Matches Spring Boot's defaults so generated names line up with what ddl-auto=validate
        // expects for any field that does not name its column explicitly.
        props.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
        props.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        // Script-only generation: no connection is opened, nothing is executed.
        props.put("jakarta.persistence.schema-generation.scripts.action", "create");
        props.put("jakarta.persistence.schema-generation.scripts.create-target", out.toString());
        props.put("jakarta.persistence.schema-generation.create-source", "metadata");
        props.put("hibernate.hbm2ddl.delimiter", ";");
        props.put("hibernate.format_sql", "true");

        new HibernatePersistenceProvider().generateSchema(unit, props);

        System.out.println("Wrote " + out.toAbsolutePath());
    }

    /**
     * Minimal {@link PersistenceUnitInfo} listing the scanned entities. Spring's
     * {@code MutablePersistenceUnitInfo} no longer implements this interface as of Spring 7,
     * and everything a script-only generation actually reads is the managed class names —
     * there is no data source, no persistence.xml, and no class transformation.
     */
    private record ScriptOnlyPersistenceUnit(List<String> managedClassNames) implements PersistenceUnitInfo {

        @Override public String getPersistenceUnitName() { return "oswl-schema-baseline"; }
        @Override public String getPersistenceProviderClassName() { return HibernatePersistenceProvider.class.getName(); }
        @Override public String getScopeAnnotationName() { return null; }
        @Override public List<String> getQualifierAnnotationNames() { return List.of(); }
        @Override public PersistenceUnitTransactionType getTransactionType() { return PersistenceUnitTransactionType.RESOURCE_LOCAL; }
        @Override public DataSource getJtaDataSource() { return null; }
        @Override public DataSource getNonJtaDataSource() { return null; }
        @Override public List<String> getMappingFileNames() { return List.of(); }
        @Override public List<URL> getJarFileUrls() { return List.of(); }
        @Override public URL getPersistenceUnitRootUrl() { return null; }
        @Override public List<String> getManagedClassNames() { return managedClassNames; }
        @Override public boolean excludeUnlistedClasses() { return true; }
        @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.UNSPECIFIED; }
        @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
        @Override public Properties getProperties() { return new Properties(); }
        @Override public String getPersistenceXMLSchemaVersion() { return "3.2"; }
        @Override public ClassLoader getClassLoader() { return SchemaBaselineGenerator.class.getClassLoader(); }
        @Override public void addTransformer(ClassTransformer transformer) { /* no weaving needed */ }
        @Override public ClassLoader getNewTempClassLoader() { return null; }
    }
}
