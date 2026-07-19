package com.arthur.stock.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Locale;

/**
 * 策略模块存量表结构迁移。
 *
 * <p>{@code schema-*.sql} 使用 {@code CREATE TABLE IF NOT EXISTS}，只能初始化新库，
 * 无法把旧库中的 {@code quant_strategy.strategy_id} 自动升级为 {@code uuid}。
 * 本迁移在数据库初始化完成后、应用对外提供服务前幂等执行，并保留已有策略与回测数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOnDatabaseInitialization
public class StrategySchemaMigration implements InitializingBean {

    private static final String STRATEGY_TABLE = "quant_strategy";
    private static final String BACKTEST_TABLE = "quant_backtest";
    private static final String LEGACY_STRATEGY_ID_COLUMN = "strategy_id";
    private static final String UUID_COLUMN = "uuid";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        if (!tableExists(STRATEGY_TABLE)) {
            log.debug("策略表尚未创建，跳过存量结构迁移");
            return;
        }

        DatabaseType databaseType = detectDatabaseType();
        migrateStrategyUuidColumn(databaseType);
        migrateBacktestStrategyReferences(databaseType);
    }

    private void migrateStrategyUuidColumn(DatabaseType databaseType) {
        boolean hasUuid = columnExists(STRATEGY_TABLE, UUID_COLUMN);
        boolean hasLegacyStrategyId = columnExists(STRATEGY_TABLE, LEGACY_STRATEGY_ID_COLUMN);

        if (hasUuid) {
            return;
        }
        if (!hasLegacyStrategyId) {
            throw new IllegalStateException(
                    "quant_strategy 同时缺少 uuid 与 strategy_id，无法自动迁移");
        }

        String renameSql = switch (databaseType) {
            case MYSQL -> """
                    ALTER TABLE quant_strategy
                    CHANGE COLUMN strategy_id uuid VARCHAR(64) NOT NULL
                    COMMENT '策略业务标识（UUID，仅用于前端交互，防止id遍历）'
                    """;
            case SQLITE -> "ALTER TABLE quant_strategy RENAME COLUMN strategy_id TO uuid";
        };
        jdbcTemplate.execute(renameSql);
        log.info("数据库迁移完成：quant_strategy.strategy_id -> uuid");
    }

    private void migrateBacktestStrategyReferences(DatabaseType databaseType) {
        if (!tableExists(BACKTEST_TABLE)
                || !columnExists(BACKTEST_TABLE, LEGACY_STRATEGY_ID_COLUMN)) {
            return;
        }

        int migratedRows = switch (databaseType) {
            case MYSQL -> migrateMysqlBacktestReferences();
            case SQLITE -> migrateSqliteBacktestReferences();
        };
        if (migratedRows > 0) {
            log.info("数据库迁移完成：quant_backtest.strategy_id 已转换为策略主键，共 {} 行",
                    migratedRows);
        }
    }

    private int migrateMysqlBacktestReferences() {
        int migratedRows = jdbcTemplate.update("""
                UPDATE quant_backtest b
                INNER JOIN quant_strategy s ON b.strategy_id = s.uuid
                SET b.strategy_id = CAST(s.id AS CHAR)
                """);

        Long invalidRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM quant_backtest
                WHERE strategy_id IS NULL
                   OR strategy_id NOT REGEXP '^[0-9]+$'
                """, Long.class);
        if (invalidRows != null && invalidRows > 0) {
            throw new IllegalStateException(
                    "quant_backtest 存在 " + invalidRows + " 条无法关联策略的历史记录，请先修复数据");
        }

        if (!isBigIntColumn(BACKTEST_TABLE, LEGACY_STRATEGY_ID_COLUMN)) {
            jdbcTemplate.execute("""
                    ALTER TABLE quant_backtest
                    MODIFY COLUMN strategy_id BIGINT NOT NULL
                    COMMENT '策略主表ID（quant_strategy.id，内部关联用）'
                    """);
            log.info("数据库迁移完成：quant_backtest.strategy_id 类型已调整为 BIGINT");
        }
        return migratedRows;
    }

    private int migrateSqliteBacktestReferences() {
        Long invalidRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM quant_backtest b
                LEFT JOIN quant_strategy s
                  ON CAST(b.strategy_id AS TEXT) = s.uuid
                WHERE s.id IS NULL
                  AND (
                    CAST(b.strategy_id AS TEXT) = ''
                    OR CAST(b.strategy_id AS TEXT) GLOB '*[^0-9]*'
                  )
                """, Long.class);
        if (invalidRows != null && invalidRows > 0) {
            throw new IllegalStateException(
                    "quant_backtest 存在 " + invalidRows + " 条无法关联策略的历史记录，请先修复数据");
        }

        int migratedRows = jdbcTemplate.update("""
                UPDATE quant_backtest
                SET strategy_id = (
                    SELECT s.id
                    FROM quant_strategy s
                    WHERE s.uuid = CAST(quant_backtest.strategy_id AS TEXT)
                )
                WHERE EXISTS (
                    SELECT 1
                    FROM quant_strategy s
                    WHERE s.uuid = CAST(quant_backtest.strategy_id AS TEXT)
                )
                """);
        jdbcTemplate.update("""
                UPDATE quant_backtest
                SET strategy_id = CAST(strategy_id AS INTEGER)
                WHERE CAST(strategy_id AS TEXT) <> ''
                  AND CAST(strategy_id AS TEXT) NOT GLOB '*[^0-9]*'
                """);
        return migratedRows;
    }

    private DatabaseType detectDatabaseType() {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        String normalizedName = productName == null
                ? ""
                : productName.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("mysql") || normalizedName.contains("mariadb")) {
            return DatabaseType.MYSQL;
        }
        if (normalizedName.contains("sqlite")) {
            return DatabaseType.SQLITE;
        }
        throw new IllegalStateException("不支持的数据库类型: " + productName);
    }

    private boolean tableExists(String tableName) {
        return findColumn(tableName, null) != null;
    }

    private boolean columnExists(String tableName, String columnName) {
        return findColumn(tableName, columnName) != null;
    }

    private boolean isBigIntColumn(String tableName, String columnName) {
        ColumnMetadata metadata = findColumn(tableName, columnName);
        return metadata != null
                && ("BIGINT".equalsIgnoreCase(metadata.typeName())
                || "INT8".equalsIgnoreCase(metadata.typeName()));
    }

    private ColumnMetadata findColumn(String tableName, String expectedColumnName) {
        return jdbcTemplate.execute((ConnectionCallback<ColumnMetadata>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(
                    connection.getCatalog(), null, tableName, null)) {
                while (columns.next()) {
                    String actualColumnName = columns.getString("COLUMN_NAME");
                    if (expectedColumnName == null
                            || expectedColumnName.equalsIgnoreCase(actualColumnName)) {
                        return new ColumnMetadata(
                                actualColumnName,
                                columns.getString("TYPE_NAME"));
                    }
                }
            }
            return null;
        });
    }

    private enum DatabaseType {
        MYSQL,
        SQLITE
    }

    private record ColumnMetadata(String name, String typeName) {
    }
}
