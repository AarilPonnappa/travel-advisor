package com.example.jarvis;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// reads the project's .env before spring starts, so a slightly-off file still works:
// quotes, "export", a Render-style postgres:// url, or an IDE that runs from the wrong folder
final class EnvFile {

    private EnvFile() {
    }

    // look in the folder we were started from first, then walk up from target/classes to the project folder
    static Path locate(Path workingDir, Path codeLocation) {
        Path here = workingDir.resolve(".env");
        if (Files.isRegularFile(here)) return here;
        Path p = codeLocation;
        for (int i = 0; p != null && i < 4; i++, p = p.getParent()) {
            Path candidate = p.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    // KEY=value lines; tolerates "export ", spaces around "=", and quotes around the value
    static Map<String, String> parse(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (Exception e) {
            return out;
        }
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String value = stripQuotes(line.substring(eq + 1).strip());
            out.put(key, value);
        }
        return out;
    }

    // what to set as system properties: .env values the real environment doesn't already have,
    // plus a jdbc version of DB_URL if it was pasted in Render's postgres:// form (from either place)
    static Map<String, String> overrides(Map<String, String> fromFile, Map<String, String> realEnv) {
        Map<String, String> set = new LinkedHashMap<>();
        fromFile.forEach((k, v) -> {
            String existing = realEnv.get(k);
            if ((existing == null || existing.isBlank()) && !v.isBlank()) set.put(k, v);
        });

        String url = pick("DB_URL", set, realEnv);
        if (url != null && (url.startsWith("postgres://") || url.startsWith("postgresql://"))) {
            JdbcParts parts = toJdbc(url);
            if (parts != null) {
                set.put("DB_URL", parts.jdbcUrl());
                if (parts.user() != null && pick("DB_USERNAME", set, realEnv) == null) set.put("DB_USERNAME", parts.user());
                if (parts.password() != null && pick("DB_PASSWORD", set, realEnv) == null) set.put("DB_PASSWORD", parts.password());
            }
        }
        return set;
    }

    record JdbcParts(String jdbcUrl, String user, String password) {
    }

    // postgresql://user:pass@host[:port]/db[?query] -> jdbc:postgresql://host:port/db?sslmode=require
    static JdbcParts toJdbc(String url) {
        try {
            URI u = URI.create(url.replaceFirst("^postgres(ql)?://", "postgresql://"));
            if (u.getHost() == null || u.getPath() == null || u.getPath().length() < 2) return null;
            int port = u.getPort() > 0 ? u.getPort() : 5432;
            String query = u.getRawQuery();
            // render / any remote host needs ssl; a docker-compose service name like "db" doesn't
            if (query == null && u.getHost().contains(".") && !u.getHost().equals("127.0.0.1")) query = "sslmode=require";
            String jdbc = "jdbc:postgresql://" + u.getHost() + ":" + port + u.getRawPath() + (query == null ? "" : "?" + query);
            String user = null;
            String pass = null;
            if (u.getUserInfo() != null) {
                String[] up = u.getUserInfo().split(":", 2);
                user = up[0];
                if (up.length > 1) pass = up[1];
            }
            return new JdbcParts(jdbc, user, pass);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String pick(String key, Map<String, String> set, Map<String, String> env) {
        String v = set.get(key);
        if (v == null || v.isBlank()) v = env.get(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    static String stripQuotes(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
