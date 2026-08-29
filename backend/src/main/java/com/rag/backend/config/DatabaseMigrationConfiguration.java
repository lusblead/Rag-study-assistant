package com.rag.backend.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * 在任何启动期业务 SQL 之前执行仓库内的 Flyway 迁移。
 *
 * <p>Spring Boot 4 不会仅凭 flyway-core 自动创建迁移 Bean，因此这里显式建立一个可被
 * MyBatis 和 Jdbc 初始化组件依赖的门禁。禁用 Flyway 只用于自行提供完整 Schema 的隔离测试。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseMigrationConfiguration {
    public static final String MIGRATION_GATE_BEAN = "databaseMigrationGate";

    @Bean(name = MIGRATION_GATE_BEAN, initMethod = "migrate")
    DatabaseMigrationGate databaseMigrationGate(DataSource dataSource,
                                                Environment environment) {
        return new DatabaseMigrationGate(dataSource, environment);
    }

    static final class DatabaseMigrationGate {
        private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationGate.class);
        private static final String DEFAULT_LOCATIONS = "classpath:db/migration";

        private final DataSource dataSource;
        private final Environment environment;

        DatabaseMigrationGate(DataSource dataSource, Environment environment) {
            this.dataSource = dataSource;
            this.environment = environment;
        }

        void migrate() {
            boolean enabled = environment.getProperty(
                    "spring.flyway.enabled", Boolean.class, true);
            if (!enabled) {
                log.info("Flyway migration gate is disabled for this application context");
                return;
            }

            String rawLocations = environment.getProperty(
                    "spring.flyway.locations", DEFAULT_LOCATIONS);
            String[] locations = Arrays.stream(rawLocations.split(","))
                    .map(String::trim)
                    .filter(location -> !location.isEmpty())
                    .toArray(String[]::new);
            if (locations.length == 0) {
                throw new IllegalStateException("spring.flyway.locations must not be empty");
            }

            Flyway flyway = Flyway.configure(DatabaseMigrationConfiguration.class.getClassLoader())
                    .dataSource(dataSource)
                    .locations(locations)
                    .baselineOnMigrate(environment.getProperty(
                            "spring.flyway.baseline-on-migrate", Boolean.class, false))
                    .baselineVersion(environment.getProperty(
                            "spring.flyway.baseline-version", "1"))
                    .validateOnMigrate(environment.getProperty(
                            "spring.flyway.validate-on-migrate", Boolean.class, true))
                    .load();

            MigrateResult result = flyway.migrate();
            log.info("Flyway migration gate completed: migrationsExecuted={}, targetVersion={}",
                    result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
