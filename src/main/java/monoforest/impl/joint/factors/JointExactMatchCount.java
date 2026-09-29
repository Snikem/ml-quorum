package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import org.apache.lucene.search.Query;

import java.util.*;
import java.util.regex.Pattern;

public class JointExactMatchCount extends JointFactor {

    // Паттерн: всё, что НЕ буквы, НЕ цифры и НЕ пробелы
    private static final Pattern TOKENIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9\\s]");

    @Override
    public String getName() {
        return "feature_exact_match_count";
    }

    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        // 1. Токенизация в множества (Set)
        Set<String> qWords = tokenizeSet(query);
        Set<String> dWords = tokenizeSet(document);
        int len_query = qWords.size();
        // 2. Находим пересечение (intersection)
        // retainAll оставляет в qWords только те элементы, которые есть в dWords
        qWords.retainAll(dWords);

        // 3. Возвращаем размер пересечения
        return new float[] { (float) qWords.size() / len_query };
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        int numQueries = queries.size();

        ArrayList<float[]> result = new ArrayList<>(numQueries);

        Set<String> dWords = tokenizeSet(document);

        if (dWords.isEmpty()) {
            float[] zero = new float[]{0f};
            for (int i = 0; i < numQueries; i++) {
                result.add(new float[]{0f});
            }
            return result;
        }

        for (int i = 0; i < numQueries; i++) {
            String query = queries.get(i);

            Set<String> qWords = tokenizeSet(query);

            qWords.retainAll(dWords);

            result.add(new float[] { (float) qWords.size() });
        }

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Количество одинаковых уникальных слов в документе и запросе деленное на количество слов в запросе";
    }

    @Override
    public void prepare() {
        // Подготовка не требуется
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    /**
     * Токенизация: lowercase -> удаление мусора -> split -> set
     */
    private Set<String> tokenizeSet(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptySet();
        }

        // 1. Lowercase
        String lower = text.toLowerCase();
        String noApostrophes = lower.replace("'", "");

        // 2. Замена спецсимволов на пробел
        String cleaned = TOKENIZE_PATTERN.matcher(noApostrophes).replaceAll(" ");

        // Убираем лишние пробелы по краям
        String trimmed = cleaned.trim();

        // 3. Защита от пустой строки после очистки от спецсимволов
        if (trimmed.isEmpty()) {
            return Collections.emptySet();
        }

        // 4. Split по пробелам
        String[] tokens = trimmed.split("\\s+");

        // 5. Собираем в Set
        Set<String> result = new HashSet<>(Arrays.asList(tokens));

        return result;
    }

    public Query buildQuery(float threshold, String query, int featureIndex){
        return null;
    }
}