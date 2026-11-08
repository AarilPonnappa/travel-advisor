package com.example.jarvis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;

@SpringBootApplication
@ConfigurationPropertiesScan // binds the road-trip ScoreWeights from application.yml
public class JarvisApplication {

    public static void main(String[] args) {
        // devtools re-runs main after every restart; the settings are already in place by then
        if (System.getProperty("jarvis.startup-checked") == null) {
            System.setProperty("jarvis.startup-checked", "true");
            String report = prepareSettings(Path.of("").toAbsolutePath(), codeLocation(), System.getenv(), System::setProperty);
            System.err.println("\n*** Jarvis: " + report + " ***\n");
        }
        SpringApplication.run(JarvisApplication.class, args);
    }

    // load .env into system properties (spring reads those before application.properties)
    // and return one line saying where the database settings came from, or what's wrong with them
    static String prepareSettings(Path workingDir, Path codeLocation, Map<String, String> env, BiConsumer<String, String> setProperty) {
        Path file = EnvFile.locate(workingDir, codeLocation);
        Map<String, String> set = EnvFile.overrides(EnvFile.parse(file), env);
        set.forEach(setProperty);

        String url = set.getOrDefault("DB_URL", env.get("DB_URL"));
        String source = set.containsKey("DB_URL") ? "DB_URL in " + file : "the DB_URL environment variable";
        String hint = databaseHint(url, source, file, workingDir);
        if (hint != null) return hint;

        String user = set.getOrDefault("DB_USERNAME", env.getOrDefault("DB_USERNAME", "?"));
        String where = file == null ? "environment variables" : file + " (" + set.size() + " settings) + environment variables";
        return "settings from " + where + ". Database " + url.replaceFirst("\\?.*$", "") + " as " + user;
    }

    static String databaseHint(String url, String source, Path file, Path workingDir) {
        if (url == null || url.isBlank()) {
            String looked = file == null
                    ? "no .env found in " + workingDir + " or the project folder"
                    : file + " has no DB_URL line";
            return "DB_URL isn't set (" + looked + "). Copy .env.example to .env in the project folder (next to pom.xml) "
                    + "and fill in DB_URL, DB_USERNAME and DB_PASSWORD, or set them in your IDE run configuration.";
        }
        url = url.strip();
        if (!url.startsWith("jdbc:postgresql://")) {
            return source + " must look like jdbc:postgresql://<host>:5432/<database>?sslmode=require (it starts with \""
                    + url.substring(0, Math.min(url.length(), 12)) + "...\").";
        }
        return null;
    }

    // where our compiled classes live (target/classes in a normal maven layout), used to find the project folder
    private static Path codeLocation() {
        try {
            return Path.of(JarvisApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return null;
        }
    }

    // one shared builder with timeouts, every api client builds its RestClient from this
    @Bean
    RestClient.Builder restClientBuilder(@Value("${jarvis.http.connect-timeout-ms:5000}") long connectMs,
                                         @Value("${jarvis.http.read-timeout-ms:45000}") long readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder().requestFactory(factory);
    }
}
