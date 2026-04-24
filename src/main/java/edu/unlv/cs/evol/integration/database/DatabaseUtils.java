package edu.unlv.cs.evol.integration.database;

import org.javalite.activejdbc.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.javalite.common.Util.blank;

/**
 * A class to create the integration database if it does not exist.
 * 
 * @author Mehran Mahmoudi
 * @author Daniel Ogenrwot
 */
public class DatabaseUtils {
    private static final String CREATE_COMPARISON_SCHEMA_FILE = "/create_integration_schema.sql";
    private static final String DEFAULT_DELIMITER = ";";
    private static final String DELIMITER_KEYWORD = "DELIMITER";
    private static final String[] COMMENT_CHARS = new String[] { "--", "#", "//" };
    private static final String DB_URL = System.getenv("JDBC_URL") != null ? System.getenv("JDBC_URL")
            : "jdbc:mysql://localhost/refactoring_aware_integration_repatch?serverTimezone=UTC";
    private static final String DB_URL_WITHOUT_DBNAME = System.getenv("JDBC_URL_WITHOUT_DATABASE") != null ? System.getenv("JDBC_URL_WITHOUT_DATABASE")
            : "jdbc:mysql://localhost?serverTimezone=UTC";
    private static final String DB_NAME = "refactoring_aware_integration_repatch";
    private static final String DB_USER = System.getenv("JDBC_USER") != null ? System.getenv("JDBC_USER") : "root";
    private static final String DB_PASSWORD = System.getenv("JDBC_PASSWORD") != null ? System.getenv("JDBC_PASSWORD")
            : "root";

    public static void createDatabase(boolean isIntegration) throws Exception {
        try {
            if (isIntegration) {
                Base.open("com.mysql.jdbc.Driver", DB_URL,
                        DB_USER, DB_PASSWORD);
            }

            Base.close();

        } catch (InitException e) {
            DB db = new DB("create_db").open("com.mysql.jdbc.Driver", DB_URL_WITHOUT_DBNAME, DB_USER, DB_PASSWORD);

            String dbName;
            if (isIntegration) {
                // dbName = "refactoring_aware_integration_repatch";
                URL scriptInputStream = DatabaseUtils.class.getResource(CREATE_COMPARISON_SCHEMA_FILE);
                DatabaseUtils.createDatabase(scriptInputStream.openStream(), db,
                        "refactoring_aware_integration_repatch", DB_NAME);
            }

            db.close();
        }

    }

    private static void createDatabase(InputStream scriptInputStream, DB db, String defaultDbName, String newDbName)
            throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(scriptInputStream));
        String delimiter = DEFAULT_DELIMITER;
        List<String> statements = new ArrayList<>();
        String currentStatement = "";
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim().replace(defaultDbName, newDbName);
            if (!commentLine(line) && !blank(line)) {
                if (line.startsWith(DELIMITER_KEYWORD)) {
                    delimiter = line.substring(10).trim();
                } else if (line.endsWith(delimiter)) {
                    currentStatement += line.substring(0, line.length() - delimiter.length());
                    if (!blank(currentStatement)) {
                        statements.add(currentStatement);
                    }
                    currentStatement = "";
                } else {
                    currentStatement += line + System.getProperty("line.separator");
                }
            }
        }
        try {
            reader.close();
            scriptInputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!blank(currentStatement)) {
            statements.add(currentStatement);
        }

        for (String statement : statements) {
            db.exec(statement);
        }
    }

    private static boolean commentLine(String line) {
        for (String cc : COMMENT_CHARS) {
            if (line.trim().startsWith(cc)) {
                return true;
            }
        }
        return false;
    }
    
    public static String getDatabaseUrl() {
        return DB_URL;
    }
    public static String getDatabaseUser() {
        return DB_USER;
    }
    public static String getDatabasePassword() {
        return DB_PASSWORD;         
    }
    public static String getDatabaseName() {
        return DB_NAME;
    }

}
