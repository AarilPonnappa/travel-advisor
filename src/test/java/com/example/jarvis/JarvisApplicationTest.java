package com.example.jarvis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// the .env loading and database checks that run before spring starts
class JarvisApplicationTest {

    private static final String JDBC = "jdbc:postgresql://dpg-example.oregon-postgres.render.com:5432/jarvis?sslmode=require";

    @Test
    void missingDbUrlGetsAClearHint(@TempDir Path dir) {
        Map<String, String> props = new HashMap<>();
        String report = JarvisApplication.prepareSettings(dir, null, Map.of(), props::put);
        assertThat(report).contains("DB_URL isn't set").contains("no .env found").contains("pom.xml");
        assertThat(props).isEmpty();
    }

    @Test
    void plainDotEnvIsLoadedIntoSystemProperties(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), """
                # local settings
                DB_URL=%s
                DB_USERNAME=jarvis_user
                DB_PASSWORD=s3cret#with=signs
                GEOAPIFY_API_KEY=
                """.formatted(JDBC));
        Map<String, String> props = new HashMap<>();
        String report = JarvisApplication.prepareSettings(dir, null, Map.of(), props::put);
        assertThat(props).containsEntry("DB_URL", JDBC).containsEntry("DB_USERNAME", "jarvis_user")
                .containsEntry("DB_PASSWORD", "s3cret#with=signs")
                .doesNotContainKey("GEOAPIFY_API_KEY"); // blank values fall through to the property defaults
        assertThat(report).contains("settings from").contains("oregon-postgres.render.com:5432/jarvis as jarvis_user")
                .doesNotContain("s3cret").doesNotContain("sslmode");
    }

    @Test
    void quotesExportAndSpacesAreTolerated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), """
                export DB_URL="%s"
                DB_USERNAME = 'jarvis_user'
                """.formatted(JDBC));
        Map<String, String> props = new HashMap<>();
        assertThat(JarvisApplication.prepareSettings(dir, null, Map.of(), props::put)).contains("settings from");
        assertThat(props).containsEntry("DB_URL", JDBC).containsEntry("DB_USERNAME", "jarvis_user");
    }

    @Test
    void renderStyleUrlIsConvertedToJdbc(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"),
                "DB_URL=postgresql://render_user:pw123@dpg-example.oregon-postgres.render.com/jarvis\n");
        Map<String, String> props = new HashMap<>();
        String report = JarvisApplication.prepareSettings(dir, null, Map.of(), props::put);
        assertThat(props).containsEntry("DB_URL", JDBC).containsEntry("DB_USERNAME", "render_user")
                .containsEntry("DB_PASSWORD", "pw123");
        assertThat(report).contains("as render_user").doesNotContain("pw123");

        // a docker-compose style host keeps its own port and gets no ssl
        assertThat(EnvFile.toJdbc("postgres://u:p@db:5433/jarvis").jdbcUrl()).isEqualTo("jdbc:postgresql://db:5433/jarvis");
    }

    @Test
    void renderStyleUrlInRealEnvironmentIsConvertedToo(@TempDir Path dir) {
        Map<String, String> props = new HashMap<>();
        Map<String, String> env = Map.of("DB_URL", "postgres://u:p@dpg-example.oregon-postgres.render.com/jarvis",
                "DB_USERNAME", "kept_user");
        JarvisApplication.prepareSettings(dir, null, env, props::put);
        assertThat(props).containsEntry("DB_URL", JDBC).containsEntry("DB_PASSWORD", "p")
                .doesNotContainKey("DB_USERNAME"); // the real variable already says who to log in as
    }

    @Test
    void realEnvironmentVariablesWinOverDotEnv(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "DB_URL=" + JDBC + "\nDB_USERNAME=file_user\n");
        Map<String, String> props = new HashMap<>();
        String report = JarvisApplication.prepareSettings(dir, null,
                Map.of("DB_URL", "jdbc:postgresql://localhost:5432/jarvis", "DB_USERNAME", "env_user"), props::put);
        assertThat(props).isEmpty();
        assertThat(report).contains("localhost:5432/jarvis as env_user");
    }

    @Test
    void dotEnvIsFoundFromTargetClassesWhenStartedElsewhere(@TempDir Path dir) throws Exception {
        // IDE started the app from some other folder; the compiled classes still sit in <project>/target/classes
        Path project = Files.createDirectories(dir.resolve("jarvis"));
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        Path elsewhere = Files.createDirectories(dir.resolve("somewhere-else"));
        Files.writeString(project.resolve(".env"), "DB_URL=" + JDBC + "\n");
        Map<String, String> props = new HashMap<>();
        String report = JarvisApplication.prepareSettings(elsewhere, classes, Map.of(), props::put);
        assertThat(props).containsEntry("DB_URL", JDBC);
        assertThat(report).contains(project.resolve(".env").toString());
    }

    @Test
    void wrongKindOfUrlIsExplained(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "DB_URL=mysql://x/y\n");
        String report = JarvisApplication.prepareSettings(dir, null, Map.of(), (k, v) -> { });
        assertThat(report).contains("must look like jdbc:postgresql://").contains("mysql://x/y");

        Files.writeString(dir.resolve(".env"), "DB_USERNAME=someone\n");
        assertThat(JarvisApplication.prepareSettings(dir, null, Map.of(), (k, v) -> { })).contains("has no DB_URL line");
    }

    @Test
    void envExampleIsValidPropertiesWithEveryKey() throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of(".env.example"))) {
            p.load(r);
        }
        assertThat(p.stringPropertyNames()).contains("DB_URL", "DB_USERNAME", "DB_PASSWORD", "OPENROUTESERVICE_API_KEY",
                "GEOAPIFY_API_KEY", "TRANSITOUS_USER_AGENT", "SERPAPI_API_KEY", "SEARCHAPI_API_KEY", "DUFFEL_ACCESS_TOKEN",
                "GEMINI_PLANNER_API_KEY", "GEMINI_CHAT_API_KEY");
        assertThat(p.getProperty("TRANSITOUS_USER_AGENT")).isEqualTo("jarvis-travel-advisor/1.0 (mailto:you@example.com)");
        // our own reader and spring's .properties reader agree on every line of the example
        Map<String, String> ours = EnvFile.parse(Path.of(".env.example"));
        p.forEach((k, v) -> assertThat(ours).containsEntry(k.toString(), v.toString()));
    }
}
