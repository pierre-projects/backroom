package com.backroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.backroom.support.ApiTestSupport;
import com.backroom.support.IntegrationTest;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Flyway migrations upgrade an existing schema in place without resetting its history. */
@IntegrationTest
class DatabaseMigrationIntegrationTests extends ApiTestSupport {
    @Test
    void upgradesAnEmptyPhaseZeroSchemaWithoutResettingItsHistory() {
        var schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = Objects.requireNonNull(jdbc.getDataSource());
        var phaseZero = Flyway.configure()
                .dataSource(source)
                .defaultSchema(schema)
                .locations("classpath:phase0-empty")
                .load();
        phaseZero.migrate();
        assertThat(jdbc.queryForObject("select to_regclass(?)::text", String.class, schema + ".flyway_schema_history"))
                .isNotNull();
        var upgraded = Flyway.configure()
                .dataSource(source)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(11);
        assertThat(upgraded.info().applied()).hasSize(12); // Schema creation plus eleven versioned migrations.
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
    }
}
