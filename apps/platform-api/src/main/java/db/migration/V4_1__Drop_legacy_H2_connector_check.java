package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** H2 gives the unnamed Phase 3 constraints generated names; PostgreSQL drops them in V4. */
public class V4_1__Drop_legacy_H2_connector_check extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        if (!"H2".equals(connection.getMetaData().getDatabaseProductName())) return;
        try (var statement = connection.createStatement();
             var checks = statement.executeQuery("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                     + "WHERE TABLE_NAME='CONNECTORS' AND CONSTRAINT_TYPE IN ('CHECK','UNIQUE')")) {
            java.util.List<String> legacy = new java.util.ArrayList<>();
            while (checks.next()) {
                String name = checks.getString(1);
                if (!"CONNECTORS_SYSTEM_TYPE_CHECK".equalsIgnoreCase(name)) legacy.add(name);
            }
            for (String name : legacy) {
                try (var update = connection.createStatement()) {
                    update.execute("ALTER TABLE connectors DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
                }
            }
        }
    }
}
