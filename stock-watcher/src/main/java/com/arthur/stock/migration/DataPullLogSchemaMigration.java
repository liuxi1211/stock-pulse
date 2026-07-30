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
import java.util.List;

/** data_pull_log 存量结构的幂等迁移。 */
@Slf4j
@Component
@RequiredArgsConstructor
@DependsOnDatabaseInitialization
public class DataPullLogSchemaMigration implements InitializingBean {

    private static final String TABLE_NAME = "data_pull_log";
    private static final List<String> LEGACY_COLUMNS = List.of(
            "success_count", "fail_count", "error_stack");

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        if (!tableExists()) {
            log.debug("data_pull_log 尚未创建，跳过存量结构迁移");
            return;
        }
        for (String column : LEGACY_COLUMNS) {
            if (columnExists(column)) {
                jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " DROP COLUMN " + column);
                log.info("数据库迁移完成：已删除 {}.{}", TABLE_NAME, column);
            }
        }
    }

    private boolean tableExists() {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(
                    connection.getCatalog(), null, TABLE_NAME, new String[]{"TABLE"})) {
                return tables.next();
            }
        });
    }

    private boolean columnExists(String expectedColumn) {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(
                    connection.getCatalog(), null, TABLE_NAME, null)) {
                while (columns.next()) {
                    if (expectedColumn.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
                return false;
            }
        });
    }
}
