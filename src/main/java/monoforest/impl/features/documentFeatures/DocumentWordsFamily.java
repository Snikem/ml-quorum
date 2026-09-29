package monoforest.impl.features.documentFeatures;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Общие токены и словарь для количества слов, несловарных слов и их доли. */
public class DocumentWordsFamily extends DocumentFeatureFamily {
    public enum Tokenization { WHITESPACE, UNICODE_WORDS }

    // Изменение параметров или словаря требует перестроения индекса и датасета.
    public static final Tokenization NON_DICTIONARY_COUNT_TOKENIZATION = Tokenization.WHITESPACE;
    public static final Tokenization NON_DICTIONARY_RATIO_TOKENIZATION = Tokenization.UNICODE_WORDS;

    public static final String FIELD_WORD_COUNT = "doc_word_count";
    public static final String FIELD_TITLE_NON_DICTIONARY_COUNT = "doc_title_non_dictionary_count";
    public static final String FIELD_BODY_NON_DICTIONARY_COUNT = "doc_body_non_dictionary_count";
    public static final String FIELD_NON_DICTIONARY_RATIO = "doc_non_dictionary_ratio";
    public static final String FIELD_NON_DICTIONARY_RATIO_COUNT = "doc_non_dictionary_ratio_count";
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b\\w+\\b", Pattern.UNICODE_CHARACTER_CLASS);

    private final Tokenization countTokenization;
    private final Tokenization ratioTokenization;
    private Set<String> dictionary;

    public DocumentWordsFamily() {
        this(NON_DICTIONARY_COUNT_TOKENIZATION, NON_DICTIONARY_RATIO_TOKENIZATION);
    }

    public DocumentWordsFamily(Tokenization countTokenization, Tokenization ratioTokenization) {
        super(FIELD_WORD_COUNT, FIELD_TITLE_NON_DICTIONARY_COUNT, FIELD_BODY_NON_DICTIONARY_COUNT,
                FIELD_NON_DICTIONARY_RATIO, FIELD_NON_DICTIONARY_RATIO_COUNT);
        this.countTokenization = Objects.requireNonNull(countTokenization, "countTokenization");
        this.ratioTokenization = Objects.requireNonNull(ratioTokenization, "ratioTokenization");
    }

    /** Явный словарь для тестов или другого корпуса. prepare() читать файл не будет. */
    public DocumentWordsFamily(Tokenization countTokenization, Tokenization ratioTokenization, Set<String> dictionary) {
        this(countTokenization, ratioTokenization);
        Set<String> normalized = new HashSet<>();
        for (String word : Objects.requireNonNull(dictionary, "dictionary")) {
            normalized.add(word.trim().toLowerCase());
        }
        this.dictionary = Collections.unmodifiableSet(normalized);
    }

    @Override public String getNameFamily() { return "DocumentWords"; }
    @Override public String getDescription() { return "Количество слов, несловарных слов и их доля"; }

    /** Вызвать до обработки документов. Отсутствие словаря — ошибка, а не нулевые фичи. */
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
            throw new UncheckedIOException("Не удалось загрузить /words_alpha.txt", e);
        }
        dictionary = Collections.unmodifiableSet(loaded);
    }

    @Override
    protected float[] calculateValues(String title, String body) {
        if (dictionary == null) throw new IllegalStateException("Сначала вызовите prepare()");
        String[] titleWords = whitespaceWords(title);
        String[] bodyWords = whitespaceWords(body);
        WordStats titleCount = wordStats(title, titleWords, countTokenization);
        WordStats bodyCount = wordStats(body, bodyWords, countTokenization);
        WordStats titleRatio = countTokenization == ratioTokenization
                ? titleCount : wordStats(title, titleWords, ratioTokenization);
        WordStats bodyRatio = countTokenization == ratioTokenization
                ? bodyCount : wordStats(body, bodyWords, ratioTokenization);
        int total = titleRatio.total + bodyRatio.total;
        int nonDictionary = titleRatio.nonDictionary + bodyRatio.nonDictionary;
        return new float[]{titleWords.length + bodyWords.length,
                titleCount.nonDictionary, bodyCount.nonDictionary,
                total == 0 ? 0f : (float) nonDictionary / total, nonDictionary};
    }

    private static String[] whitespaceWords(String text) {
        return text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
    }

    private WordStats wordStats(String text, String[] whitespaceWords, Tokenization tokenization) {
        WordStats stats = new WordStats();
        if (tokenization == Tokenization.WHITESPACE) {
            for (String word : whitespaceWords) addWord(stats, word.toLowerCase());
        } else {
            Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase());
            while (matcher.find()) addWord(stats, matcher.group());
        }
        return stats;
    }

    private void addWord(WordStats stats, String word) {
        stats.total++;
        if (!dictionary.contains(word)) stats.nonDictionary++;
    }

    private static final class WordStats {
        private int total;
        private int nonDictionary;
    }
}
