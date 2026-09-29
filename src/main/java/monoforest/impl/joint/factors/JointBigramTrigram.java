package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class JointBigramTrigram extends JointFactor {

    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        // 1. Предварительная обработка (удаляем пунктуацию, приводим к нижнему регистру)
        String[] queryWords = tokenize(query);
        String[] docWords = tokenize(document);

        // 2. Считаем биграммы и триграммы
        float bigramCount = (float) countCommonNgrams(queryWords, docWords, 2);
        float trigramCount = (float) countCommonNgrams(queryWords, docWords, 3);

        return new float[]{bigramCount, trigramCount};
    }

    private String[] tokenize(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        // Оставляем только буквы и цифры, заменяя переносы строк и знаки препинания пробелами
        return text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim()
                .split("\\s+");
    }

    private int countCommonNgrams(String[] queryWords, String[] docWords, int n) {
        if (queryWords.length < n || docWords.length < n) return 0;

        // Шаг 1: Собираем уникальные N-граммы из МАЛЕНЬКОГО запроса
        Set<String> queryNgrams = new HashSet<>();
        for (int i = 0; i <= queryWords.length - n; i++) {
            queryNgrams.add(join(queryWords, i, n));
        }

        // Шаг 2: Идем по БОЛЬШОМУ документу и проверяем наличие в сете
        int count = 0;
        for (int i = 0; i <= docWords.length - n; i++) {
            String currentDocNgram = join(docWords, i, n);
            if (queryNgrams.contains(currentDocNgram)) {
                count++;
            }
        }

        return count;
    }

    private String join(String[] words, int start, int n) {
        if (n == 2) return words[start] + " " + words[start + 1];
        return words[start] + " " + words[start + 1] + " " + words[start + 2];
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        return null;
    }

    @Override
    public String getName() {
        return "JointBigramTrigram";
    }

    @Override
    public int getFeatureQty() {
        return 2;
    }

    @Override
    public String getDescription() {
        return "дает два числа, первое это количестово которые есть и в запросе и в документе биграммы, другое число с триграммами";
    }

    @Override
    public void prepare() {

    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}
