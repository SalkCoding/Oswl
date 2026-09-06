package com.salkcoding.oswl.service.notification;

import org.junit.jupiter.api.Test;
import org.h2.tools.RunScript;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class WebPushMigrationTest {
    @Test void migrationsApplyTwiceAndEnforceOwnershipAndDeliveryUniqueness() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:push-migrations;MODE=PostgreSQL","sa", "")) {
            RunScript.execute(connection,new StringReader("CREATE TABLE libraries(id BIGINT PRIMARY KEY); CREATE TABLE users(id BIGINT PRIMARY KEY); CREATE TABLE projects(id BIGINT PRIMARY KEY);"));
            for(int repeat=0;repeat<2;repeat++) for(String file:new String[]{"V32__vulnerability_lookup_outcomes.sql","V33__custom_scan_rules.sql","V34__web_push_notifications.sql"}) {
                var path=Path.of("src/main/resources/db/migration",file);
                try(var reader=Files.newBufferedReader(path)) {RunScript.execute(connection,reader);}
            }
            try(var statement=connection.createStatement()) {
                statement.execute("INSERT INTO users VALUES(1); INSERT INTO projects VALUES(1); INSERT INTO web_push_subscriptions VALUES(1,1,'hash','encrypted',CURRENT_TIMESTAMP); INSERT INTO web_push_deliveries VALUES(1,1,1,'event','GATE_FAILURE',0,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)");
                assertThatThrownBy(()->statement.execute("INSERT INTO web_push_deliveries VALUES(2,1,1,'event','GATE_FAILURE',0,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)")).isInstanceOf(java.sql.SQLException.class);
                statement.execute("DELETE FROM users WHERE id=1");
                for(String table:new String[]{"web_push_deliveries","web_push_subscriptions"}) try(var result=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {result.next();assertThat(result.getInt(1)).isZero();}
            }
        }
    }
}
