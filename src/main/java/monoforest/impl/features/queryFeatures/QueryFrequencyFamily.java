package monoforest.impl.features.queryFeatures;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Min/max/mean частоты слов в обучающих запросах, один проход для трех производных фичей. */
public class QueryFrequencyFamily extends QueryFeatureFamily {
    public static final String MIN_LOG_FREQUENCY = "QueryMinLogFrequency";
    public static final String MAX_LOG_FREQUENCY = "QueryMaxLogFrequency";
    public static final String MEAN_LOG_FREQUENCY = "QueryMeanLogFrequency";
    public static final Path DEFAULT_DATA_PATH = Paths.get("/Volumes/Ex_Volume/msmarco/docv2_train_queries.tsv");
    private final Path dataPath;
    private Map<String, Integer> frequencies;

    public QueryFrequencyFamily() { this(DEFAULT_DATA_PATH); }

    public QueryFrequencyFamily(Path dataPath) {
        super(MIN_LOG_FREQUENCY, MAX_LOG_FREQUENCY, MEAN_LOG_FREQUENCY);
        this.dataPath = Objects.requireNonNull(dataPath, "dataPath");
    }

    /** Готовая таблица частот: ключи уже в нижнем регистре, как после чтения TSV. */
    public QueryFrequencyFamily(Map<String, Integer> frequencies) {
        this(DEFAULT_DATA_PATH);
        Map<String, Integer> copy = new HashMap<>();
        Objects.requireNonNull(frequencies, "frequencies").forEach((word, frequency) -> {
            Objects.requireNonNull(word, "word");
            if (frequency == null || frequency < 0) throw new IllegalArgumentException("Частота должна быть >= 0");
            copy.put(word, frequency);
        });
        this.frequencies = Collections.unmodifiableMap(copy);
    }

    @Override public String getNameFamily() { return "QueryFrequency"; }
    @Override public String getDescription() { return "Логарифмы min/max/mean частот слов в обучающих запросах"; }

    @Override
    public void prepare() {
        if (frequencies != null) return;
        Map<String, Integer> loaded = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(dataPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                Set<String> uniqueWords = new HashSet<>(Arrays.asList(parts[1].toLowerCase().split("\\s+")));
                for (String word : uniqueWords) loaded.merge(word, 1, Integer::sum);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить частоты из " + dataPath, e);
        }
        frequencies = Collections.unmodifiableMap(loaded);
    }

    @Override
    protected float[] calculateValues(String query) {
        if (frequencies == null) throw new IllegalStateException("Сначала вызовите prepare()");
        if (query.trim().isEmpty()) return new float[]{0, 0, 0};
        // Не trim(): старый расчет учитывал пустой первый токен при начальном пробеле.
        String[] words = query.toLowerCase().split("\\s+");
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        double sum = 0;
        for (String word : words) {
            int frequency = frequencies.getOrDefault(word, 0);
            min = Math.min(min, frequency);
            max = Math.max(max, frequency);
            sum += frequency;
        }
        // Сохраняем float-округление среднего перед log из старого расчета.
        float mean = (float) (sum / words.length);
        return new float[]{(float) Math.log(1 + min), (float) Math.log(1 + max), (float) Math.log(1 + mean)};
    }
}
