package edu.unlv.cs.evol.integration;

import edu.unlv.cs.evol.integration.database.DatabaseUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Fail-fast environment checks run before the integration pipeline does any
 * work. Each failure is a human-readable line naming the problem and the fix;
 * all failures are collected so a fresh machine can be fixed in one pass
 * instead of one crash at a time.
 *
 * Motivated by a fresh-server run that died on PR 1/5 with an NPE cascade
 * whose root cause was simply a missing git identity.
 */
public final class PipelinePreflight {

    private PipelinePreflight() {
    }

    /**
     * @throws IllegalStateException listing every failed check
     */
    public static void run() {
        List<String> failures = new ArrayList<>();
        checkGitIdentity(failures);
        checkDatabase(failures);
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder("[Preflight] pipeline environment is not ready:\n");
            for (String failure : failures) {
                message.append("  - ").append(failure).append('\n');
            }
            throw new IllegalStateException(message.toString());
        }
        System.out.println("[Preflight] git identity + database checks passed");
    }

    /*
     * git commit refuses to run without user.name/user.email and the failure
     * surfaces far from the cause (empty commit -> null hash -> NPE).
     */
    private static void checkGitIdentity(List<String> failures) {
        String name = gitConfig("user.name");
        String email = gitConfig("user.email");
        if (name == null || name.isEmpty() || email == null || email.isEmpty()) {
            failures.add("git identity is not configured — run:"
                    + " git config --global user.name \"RePatch Pipeline\" &&"
                    + " git config --global user.email \"repatch@evol-lab.local\"");
        }
    }

    /** @return the config value, empty when unset, or null when git itself failed */
    private static String gitConfig(String key) {
        try {
            Process process = new ProcessBuilder("git", "config", "--get", key)
                    .redirectErrorStream(true).start();
            String value;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                value = reader.readLine();
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            // exit 1 = key unset; the read line is null then
            return value == null ? "" : value.trim();
        } catch (IOException e) {
            return null; // git binary missing/unusable — reported as missing identity
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /*
     * DriverManager's SPI lookup fails under the plugin classloader ("No
     * suitable driver"), so load the driver class explicitly and connect
     * through it — the same way ActiveJDBC's Base.open does.
     */
    private static void checkDatabase(List<String> failures) {
        try {
            Driver driver = (Driver) Class.forName(DatabaseUtils.getDatabaseDriver(), true,
                    PipelinePreflight.class.getClassLoader()).getDeclaredConstructor().newInstance();
            Properties props = new Properties();
            props.setProperty("user", DatabaseUtils.getDatabaseUser());
            props.setProperty("password", DatabaseUtils.getDatabasePassword());
            props.setProperty("connectTimeout", "10000");
            try (Connection connection = driver.connect(DatabaseUtils.getDatabaseUrlWithoutDbName(), props)) {
                if (connection == null) {
                    failures.add("JDBC driver " + DatabaseUtils.getDatabaseDriver() + " rejected URL "
                            + DatabaseUtils.getDatabaseUrlWithoutDbName());
                }
                // reachable — schema creation happens later in the normal flow
            }
        } catch (Exception e) {
            failures.add("MySQL is not reachable at " + DatabaseUtils.getDatabaseUrlWithoutDbName()
                    + " as user '" + DatabaseUtils.getDatabaseUser() + "' — " + e.getMessage()
                    + " (is MySQL running? are JDBC_USER/JDBC_PASSWORD set?)");
        }
    }
}
