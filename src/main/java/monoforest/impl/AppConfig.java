package monoforest.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** JVM properties > environment variables > local .env. */
public final class AppConfig {
    private static final Properties properties = new Properties();
    static {
        Path config = Path.of(System.getProperty("monoforest.config", ".env"));
        if (Files.exists(config)) {
            try (InputStream input = Files.newInputStream(config)) {
                properties.load(input);
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot read configuration: " + config, error);
            }
        } else if (System.getProperty("monoforest.config") != null) {
            throw new IllegalArgumentException("Configuration not found: " + config);
        }
    }
    private AppConfig() {}
    public static String get(String key) {
        String value = System.getProperty(key);
        if (value == null) value = System.getenv(key);
        return value != null ? value : properties.getProperty(key);
    }
    private static String required(String key) {
        String value = get(key);
        if (value == null || value.isEmpty()) throw new IllegalStateException(key + " is not configured in .env, environment or JVM properties");
        return value;
    }
    public static String getIndexDir() {
        String override = System.getProperty("monoforest.index");
        return override != null ? override : required("LUCENE_INDEX_DIR");
    }
    public static String getTextField() {
        String value = get("LUCENE_TEXT_FIELD");
        return value == null || value.isEmpty() ? "text" : value;
    }
    public static String getTitleField() {
        String value = get("LUCENE_TITLE_FIELD");
        return value == null || value.isEmpty() ? "title" : value;
    }
    public static String getInputDir() { return required("MSMARCO_INPUT_DIR"); }
    public static String getModelPath() {
        String value = get("MONOFOREST_MODEL_PATH");
        return System.getProperty("monoforest.model", value == null ? "linear_monomials.json" : value);
    }
}
