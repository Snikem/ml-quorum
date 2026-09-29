package monoforest.impl.features.queryFeatures;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Общая токенизация для количества слов и количества несловарных слов. */
public class QueryWordsFamily extends QueryFeatureFamily {
    public static final String WORD_COUNT = "QueryWordCount";
    public static final String NON_DICTIONARY_COUNT = "QueryNonDictionaryCount";
    private Set<String> dictionary;

    public QueryWordsFamily() { super(WORD_COUNT, NON_DICTIONARY_COUNT); }

    public QueryWordsFamily(Set<String> dictionary) {
        this();
        Set<String> normalized = new HashSet<>();
        for (String word : Objects.requireNonNull(dictionary, "dictionary")) normalized.add(word.trim().toLowerCase());
        this.dictionary = Collections.unmodifiableSet(normalized);
    }

    @Override public String getNameFamily() { return "QueryWords"; }
    @Override public String getDescription() { return "Количество слов и несловарных слов в запросе"; }

    @Override
    public void prepare() {
        if (dictionary != null) return;
        InputStream stream = getClass().getResourceAsStream("/words_alpha.txt");
        if (stream == null) throw new IllegalStateException("Не найден словарь /words_alpha.txt");
        Set<String> loaded = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) loaded.add(line.trim().toLowerCase());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить словарь", e);
        }
        dictionary = Collections.unmodifiableSet(loaded);
    }

    @Override
    protected float[] calculateValues(String query) {
        if (dictionary == null) throw new IllegalStateException("Сначала вызовите prepare()");
        if (query.isEmpty()) return new float[]{0, 0};
        String trimmed = query.trim();
        String[] words = trimmed.split("\\s+");
        int nonDictionary = 0;
        for (String word : words) if (!dictionary.contains(word.toLowerCase())) nonDictionary++;
        // Совместимость: старый счетчик несловарных слов проверял isEmpty(), а не trim().isEmpty().
        // Поэтому для строки из пробелов он проверяет пустой токен в словаре.
        return new float[]{trimmed.isEmpty() ? 0 : words.length, nonDictionary};
    }
}
