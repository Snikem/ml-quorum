package monoforest.impl.joint.factors;

import monoforest.impl.joint.JointFactor;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JointTFIDF extends JointFactor {

    // === КОНФИГУРАЦИЯ ===
    // Используем тот же путь, что и для BM25
    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";
    private static final String FIELD_NAME = "text";

    private IndexReader reader;
    private Analyzer analyzer;
    private boolean isReady = false;
    private long totalNumDocs;

    @Override
    public String getName() {
        return "JointTFIDF_Score";
    }

    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        if (!isReady || query == null) return new float[]{0f};

        // Собираем текст для подсчета TF
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append(" ");
        if (document != null) sb.append(document);
        String docText = sb.toString();

        if (docText.trim().isEmpty()) return new float[]{0f};

        try {
            // 1. Токенизация (тем же анализатором, что и индекс!)
            List<String> queryTokens = analyze(query);
            List<String> docTokens = analyze(docText);

            if (docTokens.isEmpty() || queryTokens.isEmpty()) return new float[]{0f};

            // 2. Считаем TF (частоту слов) в текущем документе
            Map<String, Integer> docTF = new HashMap<>();
            for (String t : docTokens) {
                docTF.put(t, docTF.getOrDefault(t, 0) + 1);
            }

            // 3. Вычисляем суммарный TF-IDF
            double totalScore = 0.0;

            for (String qTerm : queryTokens) {
                // Если слова нет в документе, TF = 0 -> вклад 0
                if (!docTF.containsKey(qTerm)) continue;

                int tf = docTF.get(qTerm);

                // Получаем IDF из индекса
                double idf = getIDF(qTerm);

                // TF * IDF
                // Можно использовать Math.sqrt(tf) для сглаживания, но классика просто tf * idf
                totalScore += (tf * idf);
            }

            return new float[] { (float) totalScore };

        } catch (IOException e) {
            e.printStackTrace();
            return new float[]{0f};
        }
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        if (!isReady || queries == null) {
            System.out.println("queries == null");
            return new ArrayList<float[]>();
        }



        // Собираем текст для подсчета TF
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title).append(" ");
        if (document != null) sb.append(document);
        String docText = sb.toString();

        if (docText.trim().isEmpty()){
            System.out.println("docText.trim().isEmpty()");
            return new ArrayList<float[]>();
        }

        try {
            // 1. Токенизация (тем же анализатором, что и индекс!)
            ArrayList<List<String>> listQueryTokens = new ArrayList<>();
            for( String query : queries) {
                listQueryTokens.add(analyze(query));
            }
            List<String> docTokens = analyze(docText);

            if (docTokens.isEmpty() || listQueryTokens.isEmpty()){
                System.out.println("docTokens.isEmpty() || listQueryTokens.isEmpty()");
                return new ArrayList<float[]>();
            }

            // 2. Считаем TF (частоту слов) в текущем документе
            Map<String, Integer> docTF = new HashMap<>();
            for (String t : docTokens) {
                docTF.put(t, docTF.getOrDefault(t, 0) + 1);
            }
            ArrayList<float[]> result = new ArrayList<float[]>();
            for(List<String> queryTokens : listQueryTokens ) {
                // 3. Вычисляем суммарный TF-IDF
                double totalScore = 0.0;

                for (String qTerm : queryTokens) {
                    // Если слова нет в документе, TF = 0 -> вклад 0
                    if (!docTF.containsKey(qTerm)) continue;

                    int tf = docTF.get(qTerm);

                    // Получаем IDF из индекса
                    double idf = getIDF(qTerm);

                    // TF * IDF
                    // Можно использовать Math.sqrt(tf) для сглаживания, но классика просто tf * idf
                    totalScore += (tf * idf);
                }
                result.add(new float[] { (float) totalScore });
            }

            return result;

        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<float[]>();
        }
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Sum of TF-IDF scores (TF from text, IDF from Index)";
    }

    @Override
    public void prepare() {
        try {
            // Открываем индекс только для чтения статистики
            reader = DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_PATH)));

            // Анализатор должен совпадать с тем, что в индексе (WhitespaceAnalyzer)
            analyzer = new WhitespaceAnalyzer();

            totalNumDocs = reader.numDocs();

            isReady = true;
            System.out.println("JointTFIDF prepared. Total docs: " + totalNumDocs);

        } catch (IOException e) {
            System.err.println("Error opening index for TFIDF: " + INDEX_PATH);
            e.printStackTrace();
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    // === ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ===

    /**
     * Считает классический IDF (как в Lucene ClassicSimilarity)
     */
    private double getIDF(String termText) throws IOException {
        Term term = new Term(FIELD_NAME, termText);
        long docFreq = reader.docFreq(term); // В скольких документах встречается слово

        if (docFreq == 0) {
            // Если слова нет в индексе вообще, даем ему высокий вес (или 0, зависит от логики)
            // Обычно принято возвращать log(N/1)
            return Math.log((double) (totalNumDocs + 1));
        }

        // Формула: log( (N + 1) / (docFreq + 1) ) + 1
        return Math.log((double) (totalNumDocs + 1) / (double) (docFreq + 1)) + 1.0;
    }

    /**
     * Токенизация строки в список слов
     */
    private List<String> analyze(String text) throws IOException {
        List<String> result = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_NAME, text)) {
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                result.add(attr.toString());
            }
            tokenStream.end();
        }
        return result;
    }
}