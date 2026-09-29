package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class QueryQuantityNonDictionaryWords extends QueryFactor {

    private Set<String> dictionary;
    private boolean isReady = false;

    @Override
    public String getName() {
        return "QueryQuantityNonDictionaryWords";
    }

    @Override
    public float[] calculateScore(String query) {
        if (!isReady || query == null || query.isEmpty()) {
            return new float[]{0f};
        }

        int nonDictCount = 0;
        // Разбиваем по пробелам, как в Python split()
        String[] words = query.trim().split("\\s+");

        for (String word : words) {
            // Приводим к нижнему регистру
            String lowerWord = word.toLowerCase();
            // Если слова нет в словаре — увеличиваем счетчик
            if (!dictionary.contains(lowerWord)) {
                nonDictCount++;
            }
        }

        return new float[] { (float) nonDictCount };
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Количество слов в запросе, которых нет в словаре";
    }

    @Override
    public void prepare() {
        dictionary = new HashSet<>();
        try {
            // Загружаем файл words_alpha.txt из ресурсов
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
            System.out.println("Dictionary loaded. Words count: " + dictionary.size());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}