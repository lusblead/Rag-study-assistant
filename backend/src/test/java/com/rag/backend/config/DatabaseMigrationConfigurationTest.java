package com.rag.backend.config;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseMigrationConfigurationTest {

    @Test
    void disabledGateDoesNotTouchDataSource() {
        DataSource dataSource = mock(DataSource.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.enabled", "false");

        new DatabaseMigrationConfiguration.DatabaseMigrationGate(
                dataSource, environment).migrate();

        verifyNoInteractions(dataSource);
    }

    @Test
    void migrationFailureIsPropagatedAndStopsGate() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLException("isolated database is unavailable"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.locations", "classpath:db/migration");

        assertThrows(FlywayException.class,
                () -> new DatabaseMigrationConfiguration.DatabaseMigrationGate(
                        dataSource, environment).migrate());
    }
}
