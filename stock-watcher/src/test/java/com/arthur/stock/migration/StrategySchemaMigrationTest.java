package com.arthur.stock.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategySchemaMigrationTest {

    private Connection connection;
    private JdbcTemplate jdbcTemplate;
    private StrategySchemaMigration migration;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        migration = new StrategySchemaMigration(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void shouldMigrateLegacyStrategyAndBacktestReferencesIdempotently() {
        createLegacyTables();
        jdbcTemplate.update("""
                INSERT INTO quant_strategy (strategy_id, name)
                VALUES ('strategy-uuid-1', '双均线策略')
                """);
        jdbcTemplate.update("""
                INSERT INTO quant_backtest (task_id, strategy_id)
                VALUES ('task-1', 'strategy-uuid-1')
                """);

        migration.afterPropertiesSet();
        migration.afterPropertiesSet();

        assertThat(columnNames("quant_strategy"))
                .contains("uuid")
                .doesNotContain("strategy_id");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT uuid FROM quant_strategy WHERE id = 1", String.class))
                .isEqualTo("strategy-uuid-1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT strategy_id FROM quant_backtest WHERE task_id = 'task-1'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void shouldLeaveCurrentSchemaDataUntouched() {
        jdbcTemplate.execute("""
                CREATE TABLE quant_strategy (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE quant_backtest (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL UNIQUE,
                    strategy_id INTEGER NOT NULL
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO quant_strategy (uuid, name)
                VALUES ('strategy-uuid-2', '价值策略')
                """);
        jdbcTemplate.update("""
                INSERT INTO quant_backtest (task_id, strategy_id)
                VALUES ('task-2', 1)
                """);

        migration.afterPropertiesSet();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT uuid FROM quant_strategy WHERE id = 1", String.class))
                .isEqualTo("strategy-uuid-2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT strategy_id FROM quant_backtest WHERE task_id = 'task-2'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void shouldFailWithoutCorruptingOrphanBacktestReference() {
        createLegacyTables();
        jdbcTemplate.update("""
                INSERT INTO quant_strategy (strategy_id, name)
                VALUES ('strategy-uuid-3', '轮动策略')
                """);
        jdbcTemplate.update("""
                INSERT INTO quant_backtest (task_id, strategy_id)
                VALUES ('task-orphan', 'missing-strategy')
                """);

        assertThatThrownBy(migration::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法关联策略");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT strategy_id FROM quant_backtest WHERE task_id = 'task-orphan'",
                String.class))
                .isEqualTo("missing-strategy");
    }

    private void createLegacyTables() {
        jdbcTemplate.execute("""
                CREATE TABLE quant_strategy (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    strategy_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE quant_backtest (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL UNIQUE,
                    strategy_id TEXT NOT NULL
                )
                """);
    }

    private java.util.List<String> columnNames(String tableName) {
        return jdbcTemplate.query(
                "PRAGMA table_info(" + tableName + ")",
                (resultSet, rowNum) -> resultSet.getString("name"));
    }
}
