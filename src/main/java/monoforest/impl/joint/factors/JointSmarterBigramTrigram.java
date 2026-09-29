package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JointSmarterBigramTrigram extends JointFactor {

    // Статический сет стоп-слов для сверхбыстрого поиска за O(1)
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
            "if", "in", "into", "is", "it", "no", "not", "of", "on", "or",
            "such", "that", "the", "their", "then", "there", "these",
            "they", "this", "to", "was", "will", "with"
    ));

    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        // 1. Предварительная обработка: токенизация, удаление стоп-слов и окончаний
        List<String> queryWords = tokenizeAndClean(query);
        List<String> docWords = tokenizeAndClean(document);

        // 2. Считаем биграммы и триграммы по очищенным спискам
        float bigramCount = (float) countCommonNgrams(queryWords, docWords, 2);
        float trigramCount = (float) countCommonNgrams(queryWords, docWords, 3);

        return new float[]{bigramCount, trigramCount, bigramCount / docWords.size(), trigramCount/ docWords.size()};
    }

    private List<String> tokenizeAndClean(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();

        // Приводим к нижнему регистру и заменяем знаки препинания пробелами
        String cleanText = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] rawWords = cleanText.trim().split("\\s+");

        List<String> processedWords = new ArrayList<>(rawWords.length);

        for (String word : rawWords) {
            if (word.isEmpty() || STOP_WORDS.contains(word)) {
                continue; // Пропускаем пустые строки и стоп-слова
            }
            // Применяем быстрый стемминг (удаление окончаний) и добавляем в список
            processedWords.add(fastStem(word));
        }

        return processedWords;
    }

    /**
     * Быстрая эвристика для удаления частых английских окончаний.
     * Работает мгновенно, не требуя тяжелых библиотек.
     */
    private String fastStem(String word) {
        if (word.length() <= 3) return word; // Не трогаем слишком короткие слова

        if (word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("es")) return word.substring(0, word.length() - 2);
        if (word.endsWith("s")) return word.substring(0, word.length() - 1);
        if (word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.endsWith("ed")) return word.substring(0, word.length() - 2);

        return word;
    }

    private int countCommonNgrams(List<String> queryWords, List<String> docWords, int n) {
        if (queryWords.size() < n || docWords.size() < n) return 0;

        // Шаг 1: Собираем уникальные N-граммы из МАЛЕНЬКОГО запроса
        Set<String> queryNgrams = new HashSet<>();
        for (int i = 0; i <= queryWords.size() - n; i++) {
            queryNgrams.add(join(queryWords, i, n));
        }

        // Шаг 2: Идем по БОЛЬШОМУ документу и проверяем наличие в сете
        int count = 0;
        for (int i = 0; i <= docWords.size() - n; i++) {
            String currentDocNgram = join(docWords, i, n);
            if (queryNgrams.contains(currentDocNgram)) {
                count++;
            }
        }

        return count;
    }

    private String join(List<String> words, int start, int n) {
        if (n == 2) return words.get(start) + " " + words.get(start + 1);
        return words.get(start) + " " + words.get(start + 1) + " " + words.get(start + 2);
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        return null; // В соответствии с вашей изначальной реализацией
    }

    @Override
    public String getName() {
        return "JointBigramTrigramCleaned";
    }

    @Override
    public int getFeatureQty() {
        return 4;
    }

    @Override
    public String getDescription() {
        return "Дает 2 числа: пересечение биграмм и триграмм между запросом и документом (после удаления стоп-слов и базового стемминга). другие два числа нормализация ";
    }

    @Override
    public void prepare() {
        // Можно оставить пустым
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}
