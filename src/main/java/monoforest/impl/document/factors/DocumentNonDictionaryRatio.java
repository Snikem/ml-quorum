package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentNonDictionaryRatio extends DocumentFactor {

    private Set<String> dictionary;
    private boolean isReady = false;

    // Паттерн для поиска слов (аналог Python r"\b\w+\b" с поддержкой Unicode)
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\b\\w+\\b", Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public String getName() {
        return "non_dictionary_ratio";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        float[] result = new float[2]; // [ratio, count]

        // Если словарь не загружен, возвращаем нули
        if (!isReady) {
            return result;
        }

        // Собираем текст: title + " " + document
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append(" ");
        if (document != null) sb.append(document);

        String text = sb.toString().toLowerCase(); // Сразу в нижний регистр

        int totalTokens = 0;
        int nonDictCount = 0;

        Matcher matcher = TOKEN_PATTERN.matcher(text);

        while (matcher.find()) {
            String token = matcher.group();
            totalTokens++;

            // Проверяем наличие в словаре
            if (!dictionary.contains(token)) {
                nonDictCount++;
            }
        }

        if (totalTokens == 0) {
            return new float[]{0f, 0f};
        }

        float ratio = (float) nonDictCount / totalTokens;

        result[0] = ratio;
        result[1] = (float) nonDictCount;

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 2; // Возвращаем ratio и count
    }

    @Override
    public String getDescription() {
        return "Доля и количество не словарных слов в документе";
    }

    @Override
    public void prepare() {
        dictionary = new HashSet<>();
        try {
            // Используем тот же файл словаря words_alpha.txt
            InputStream is = getClass().getResourceAsStream("/words_alpha.txt");
            if (is == null) {
                System.err.println("Error: Dictionary file '/words_alpha.txt' not found in resources!");
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                dictionary.add(line.trim().toLowerCase());
            }
            isReady = true;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

}