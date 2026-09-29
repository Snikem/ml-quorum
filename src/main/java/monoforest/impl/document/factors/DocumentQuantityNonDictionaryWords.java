package monoforest.impl.document.factors;

import monoforest.impl.document.DocumentFactor;
import org.apache.lucene.search.Query;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class DocumentQuantityNonDictionaryWords extends DocumentFactor {

    private Set<String> dictionary;
    private boolean isReady = false;

    @Override
    public String getName() {
        return "DocumentQuantityNonDictionaryWords";
    }

    @Override
    public float[] calculateScore(String title, String document, String doc_id) {
        float[] result = new float[2];

        if (!isReady) {
            return result; // Возвращаем [0, 0] если словарь не загрузился
        }

        result[0] = countNonDictWords(title);
        result[1] = countNonDictWords(document);

        return result;
    }

    @Override
    public int getFeatureQty() {
        return 2;
    }

    @Override
    public String getDescription() {
        return "Количество не словарных слов в заголовке и документе";
    }

    @Override
    public void prepare() {
        dictionary = new HashSet<>();
        try {
            // Используем тот же файл словаря
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

    private int countNonDictWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        int count = 0;
        String[] words = text.trim().split("\\s+");
        for (String word : words) {
            if (!dictionary.contains(word.toLowerCase())) {
                count++;
            }
        }
        return count;
    }
}