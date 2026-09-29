package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import org.apache.lucene.search.Query;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;

public class QueryFrequencyWords extends QueryFactor {

    //TODO: стыдоба
    private static final String DATA_PATH = "/Volumes/Ex_Volume/msmarco/docv2_train_queries.tsv";

    private Map<String, Integer> wordFreq;
    private boolean isReady = false;

    @Override
    public String getName() {
        return "Frequency_words_in_query";
    }

    @Override
    public float[] calculateScore(String query) {
        if (!isReady || query == null || query.trim().isEmpty()) {
            return new float[]{0f, 0f, 0f};
        }

        String[] words = query.toLowerCase().split("\\s+");
        List<Integer> counts = new ArrayList<>();

        for (String word : words) {
            // Получаем частоту, если слова нет — 0
            counts.add(wordFreq.getOrDefault(word, 0));
        }

        if (counts.isEmpty()) {
            return new float[]{0f, 0f, 0f};
        }

        // Считаем метрики: min, max, mean
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        double sum = 0;

        for (int c : counts) {
            if (c < min) min = c;
            if (c > max) max = c;
            sum += c;
        }

        float mean = (float) (sum / counts.size());

        return new float[] {
                (float) Math.log(1 + min),
                (float) Math.log(1 + max),
                (float) Math.log(1 + mean)
        };
    }

    @Override
    public int getFeatureQty() {
        return 3; // min, max, mean
    }

    @Override
    public String getDescription() {
        return "Частота слов запроса в выборке (min, max, mean)";
    }

    @Override
    public void prepare() {
        wordFreq = new HashMap<>();
        File file = new File(DATA_PATH);

        if (!file.exists()) {
            System.err.println("Error: Query freq file not found at: " + DATA_PATH);
            return;
        }

        System.out.println("Loading query frequencies from: " + DATA_PATH);
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Формат MS MARCO TSV: id \t text
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;

                String text = parts[1].toLowerCase();
                String[] words = text.split("\\s+");

                // Логика Python: word_freq.update(set(query_text))
                // Берем уникальные слова в рамках одного запроса
                Set<String> uniqueWords = new HashSet<>(Arrays.asList(words));

                for (String w : uniqueWords) {
                    wordFreq.put(w, wordFreq.getOrDefault(w, 0) + 1);
                }
            }
            isReady = true;
            System.out.println("Query frequencies loaded. Vocabulary size: " + wordFreq.size());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }
}