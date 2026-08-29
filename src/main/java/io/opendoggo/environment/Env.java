package io.opendoggo.environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 读取配置：.env 优先，没有再回落进程环境变量。
 *
 * 对齐 s1 的 load_dotenv(override=True)。
 */
public final class Env {

    private final Map<String, String> fileEnv;

    private Env(Map<String, String> fileEnv) {
        this.fileEnv = fileEnv;
    }

    public static Env load() {
        return new Env(loadDotEnv(Path.of(".env")));
    }

    public String get(String key) {
        String fromFile = fileEnv.get(key);
        return fromFile != null ? fromFile : System.getenv(key);
    }

    private static Map<String, String> loadDotEnv(Path envFile) {
        Map<String, String> values = new HashMap<>();

        if (!Files.isRegularFile(envFile)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(
                    envFile,
                    StandardCharsets.UTF_8
            )) {
                parseEnvLine(line, values);
            }
        } catch (IOException exception) {
            System.err.println(
                    "Warning: unable to read .env: "
                            + exception.getMessage()
            );
        }

        return values;
    }

    private static void parseEnvLine(
            String line,
            Map<String, String> values
    ) {
        String trimmed = line.strip();

        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }

        int separator = trimmed.indexOf('=');

        if (separator <= 0) {
            return;
        }

        String key = trimmed.substring(0, separator).strip();
        String value =
                trimmed.substring(separator + 1).strip();

        if (value.length() >= 2
                && (value.startsWith("\"")
                        && value.endsWith("\"")
                || value.startsWith("'")
                        && value.endsWith("'"))) {

            value = value.substring(1, value.length() - 1);
        }

        values.put(key, value);
    }
}
