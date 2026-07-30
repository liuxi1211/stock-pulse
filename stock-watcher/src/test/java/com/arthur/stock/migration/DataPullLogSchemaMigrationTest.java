package com.arthur.stock.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataPullLogSchemaMigrationTest {

    @Test
    void shouldSkipWhenTableDoesNotExist() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate(false, false);
        DataPullLogSchemaMigration migration = new DataPullLogSchemaMigration(jdbcTemplate);

        assertDoesNotThrow(migration::afterPropertiesSet);
    }

    @Test
    void shouldBeIdempotentWhenLegacyColumnsDoNotExist() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate(true, false);
        DataPullLogSchemaMigration migration = new DataPullLogSchemaMigration(jdbcTemplate);

        assertDoesNotThrow(migration::afterPropertiesSet);
        assertDoesNotThrow(migration::afterPropertiesSet);
    }

    private JdbcTemplate jdbcTemplate(boolean tableExists, boolean columnExists) throws Exception {
        JdbcTemplate template = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        AtomicInteger tableChecks = new AtomicInteger();

        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("stock");
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(tables);
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(tables.next()).thenAnswer(invocation -> tableChecks.getAndIncrement() == 0 && tableExists);
        when(columns.next()).thenReturn(columnExists, false);
        when(columns.getString("COLUMN_NAME")).thenReturn("success_count");
        when(template.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
        return template;
    }
}
