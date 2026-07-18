package com.arthur.stock.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite → MySQL 一次性数据迁移工具。
 * 启用方式：启动参数加 --app.migrate=true，或在 application-secret.properties 中设置 app.migrate=true。
 * 迁移按外键依赖顺序逐表进行，每批 1000 条，源 SQLite 文件保留作为备份。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.migrate", havingValue = "true")
public class SQLiteToMysqlMigrator implements CommandLineRunner {

    private static final int BATCH_SIZE = 1000;

    private static final List<String> TABLES_IN_ORDER = List.of(
            "sys_user", "sys_watchlist", "stock_basic", "trade_cal",
            "daily_quote", "adj_factor", "dividend"
    );

    @Value("${app.migrate.sqlite-path:./data/stock_watcher.db}")
    private String sqlitePath;

    @Value("${spring.datasource.url}")
    private String mysqlUrl;

    @Value("${spring.datasource.username}")
    private String mysqlUser;

    @Value("${spring.datasource.password}")
    private String mysqlPassword;

    @Override
    public void run(String... args) throws Exception {
        log.info("==== 开始 SQLite → MySQL 数据迁移 ====");
        log.info("SQLite 源: {}", sqlitePath);
        log.info("MySQL 目标: {}", mysqlUrl);

        String sqliteUrl = "jdbc:sqlite:" + sqlitePath;
        try (Connection src = DriverManager.getConnection(sqliteUrl);
             Connection dst = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword)) {

            dst.setAutoCommit(false);

            int totalMigrated = 0;
            for (String table : TABLES_IN_ORDER) {
                int n = migrateTable(src, dst, table);
                totalMigrated += n;
                log.info("表 {} 迁移完成：{} 行", table, n);
            }

            log.info("==== 迁移完成，共 {} 行 ====", totalMigrated);
            log.info("SQLite 源文件保留作为备份：{}", sqlitePath);
        } catch (Exception e) {
            log.error("迁移失败", e);
            throw e;
        }
    }

    private int migrateTable(Connection src, Connection dst, String table) throws Exception {
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> colNames = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                colNames.add(meta.getColumnName(i));
            }

            StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ")
                    .append(table).append(" (");
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < colNames.size(); i++) {
                if (i > 0) {
                    sqlBuilder.append(", ");
                    placeholders.append(", ");
                }
                sqlBuilder.append(colNames.get(i));
                placeholders.append("?");
            }
            sqlBuilder.append(") VALUES (").append(placeholders).append(")");

            String insertSql = sqlBuilder.toString();
            int total = 0;
            int batchCount = 0;

            try (PreparedStatement ps = dst.prepareStatement(insertSql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        Object val = rs.getObject(i);
                        row.put(colNames.get(i - 1), val);
                    }
                    for (int i = 0; i < colNames.size(); i++) {
                        Object v = row.get(colNames.get(i));
                        if (v instanceof Boolean) {
                            ps.setInt(i + 1, ((Boolean) v) ? 1 : 0);
                        } else {
                            ps.setObject(i + 1, v);
                        }
                    }
                    ps.addBatch();
                    batchCount++;
                    total++;
                    if (batchCount >= BATCH_SIZE) {
                        ps.executeBatch();
                        dst.commit();
                        batchCount = 0;
                    }
                }
                if (batchCount > 0) {
                    ps.executeBatch();
                    dst.commit();
                }
            }
            return total;
        } catch (Exception e) {
            dst.rollback();
            throw new RuntimeException("迁移表 " + table + " 失败", e);
        }
    }
}
