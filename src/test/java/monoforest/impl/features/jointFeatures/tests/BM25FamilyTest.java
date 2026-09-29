package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;

import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.jointFeatures.BM25Family;
import monoforest.impl.features.jointFeatures.BM25Family.Comparison;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Автономный регрессионный тест: запустить main, внешний индекс и .env не нужны.
 * Мок-коллекция индексируется настоящим Lucene в памяти, чтобы зафиксировать
 * также статистику коллекции и проверить реальные результаты пороговых запросов.
 *
 * Эталоны получены отдельно обычными TermQuery/BooleanQuery и BM25Similarity
 * из Lucene 8.6.0 (k1 = 1.2, b = 0.75), без использования BM25Family.
 * В поле text: docCount = 6, sumTotalTermFreq = 114, avgdl = 19.
 * D6 содержит 101 токен; Lucene кодирует эту длину в norm с декодированным значением 96.
 * Не заменять константы результатами BM25Family: это скроет регрессии.
 */
public class BM25FamilyTest {
    private static final String FIELD = "text";
    private static final String TITLE = "only_title cat cat";
    private static final String[][] DOCUMENTS = {
            {"D1", "cat cat dog"},
            {"D2", "cat mouse"},
            {"D3", "dog mouse mouse"},
            {"D4", "bird dog"},
            {"D5", ""},
            {"D6", "cat ".repeat(100) + "dog"},
            {"D7", "DOG Cat cat,"}
    };

    // Порядок значений в каждой строке: D1, D2, D3, D4, D5, D6, D7.
    private static final Fixture[] FIXTURES = {
            new Fixture("cat dog", 0.87404406f, 0.49697345f, 0.3063804f, 0.31678575f, 0.0f, 0.73666215f, 0.0f),
            new Fixture("cat cat dog", 1.4417077f, 0.9939469f, 0.3063804f, 0.31678575f, 0.0f, 1.3977633f, 0.0f),
            new Fixture("dog mouse", 0.3063804f, 0.7382177f, 1.1496032f, 0.31678575f, 0.0f, 0.07556096f, 0.0f),
            new Fixture("bird dog", 0.3063804f, 0.0f, 0.3063804f, 1.4212558f, 0.0f, 0.07556096f, 0.0f),
            new Fixture("missing", 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            new Fixture("", 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            new Fixture("only_title", 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            new Fixture("Cat cat,", 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 2.1363838f)
    };

    @Test
    public void verifiesFeatureValues() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
             Analyzer analyzer = new WhitespaceAnalyzer()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                    .setSimilarity(new BM25Similarity(1.2f, 0.75f)))) {
                for (String[] fixture : DOCUMENTS) {
                    Document document = new Document();
                    document.add(new StringField("id", fixture[0], Field.Store.YES));
                    document.add(new StringField("title", TITLE, Field.Store.YES));
                    document.add(new TextField(FIELD, fixture[1], Field.Store.YES));
                    writer.addDocument(document);
                }
            }

            try (DirectoryReader reader = DirectoryReader.open(directory);
                 BM25Family family = new BM25Family(reader, FIELD)) {
                family.prepare();
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));
                if (searcher.collectionStatistics(FIELD).docCount() != 6
                        || searcher.collectionStatistics(FIELD).sumTotalTermFreq() != 114) {
                    throw new AssertionError("Изменилась статистика мок-коллекции");
                }
                MyTokenizer tokenizer = new MyTokenizer(analyzer);
                int valuesChecked = 0;
                int queriesChecked = 0;
                List<String> names = family.getAllFeaturesNames();
                if (!names.contains(BM25Family.BM25_SCORE)) throw new AssertionError("Нет BM25Score в семействе");
                int scoreIndex = names.indexOf(BM25Family.BM25_SCORE);

                for (Fixture fixture : FIXTURES) {
                    String[] tokens = tokenizer.tokenize(fixture.query).toArray(new String[0]);
                    Map<String, Float> nativeScores = nativeScores(searcher, tokens);
                    for (int i = 0; i < DOCUMENTS.length; i++) {
                        DocumentMarco document = new DocumentMarco();
                        document.setDoc_id(DOCUMENTS[i][0]);
                        document.setTitle(TITLE);
                        document.setBody(DOCUMENTS[i][1]);
                        document.setTokensBody(tokenizer.tokenize(document.getBody()));
                        document.tokenizeTitle(tokenizer);
                        String label = document.getDoc_id() + " + [" + fixture.query + "]";

                        float single = family.calculateFeatureByName(BM25Family.BM25_SCORE, tokens, document).value;
                        float batch = family.calculateAllFeaturesInFamily(tokens, document).get(scoreIndex).value;
                        assertScore(fixture.expected[i], nativeScores.getOrDefault(document.getDoc_id(), 0f), label + " native Lucene");
                        assertScore(fixture.expected[i], single, label + " single");
                        assertScore(fixture.expected[i], batch, label + " batch");
                        System.out.println("PASS " + label + " = " + Float.toString(single));
                        valuesChecked++;
                    }

                    // Включаем точные значения float и соседние double-пороги.
                    // Это проверяет строгость неравенств без округления порога до float.
                    double boundary = fixture.expected[0];
                    double[] thresholds = {-1.0, 0.0, 0.3, 0.5, 1.0,
                            boundary, Math.nextDown(boundary), Math.nextUp(boundary)};
                    for (double threshold : thresholds) {
                        for (Comparison comparison : Comparison.values()) {
                            assertQuery(searcher, family, tokens, fixture, threshold, comparison);
                            queriesChecked++;
                        }
                    }
                }
                System.out.println("PASS: " + valuesChecked + " фиксированных пар документ-запрос; "
                        + queriesChecked + " пороговых запросов Lucene.");
            }
        }
    }

    private static Map<String, Float> nativeScores(IndexSearcher searcher, String[] tokens) throws Exception {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String token : tokens) query.add(new TermQuery(new Term(FIELD, token)), BooleanClause.Occur.SHOULD);
        Map<String, Float> scores = new HashMap<>();
        for (ScoreDoc hit : searcher.search(query.build(), DOCUMENTS.length).scoreDocs) {
            scores.put(searcher.doc(hit.doc).get("id"), hit.score);
        }
        return scores;
    }

    private static void assertQuery(IndexSearcher searcher, BM25Family family, String[] tokens,
                                    Fixture fixture, double threshold, Comparison comparison) throws Exception {
        Set<String> expectedIds = new HashSet<>();
        Map<String, Float> expectedScores = new HashMap<>();
        for (int i = 0; i < DOCUMENTS.length; i++) {
            float score = fixture.expected[i];
            boolean matches;
            switch (comparison) {
                case GT: matches = score > threshold; break;
                case LT: matches = score < threshold; break;
                case GE: matches = score >= threshold; break;
                case LE: matches = score <= threshold; break;
                default: throw new AssertionError(comparison);
            }
            if (matches) expectedIds.add(DOCUMENTS[i][0]);
            expectedScores.put(DOCUMENTS[i][0], score);
        }

        Query query = family.buildLuceneQuery(BM25Family.BM25_SCORE, tokens, threshold, comparison);
        Set<String> actualIds = new HashSet<>();
        for (ScoreDoc hit : searcher.search(query, DOCUMENTS.length).scoreDocs) {
            String id = searcher.doc(hit.doc).get("id");
            actualIds.add(id);
            assertScore(expectedScores.get(id), hit.score, id + " Lucene [" + fixture.query + "]");
        }
        if (!actualIds.equals(expectedIds)) {
            throw new AssertionError(query + ": ожидались " + expectedIds + ", найдены " + actualIds);
        }
    }

    private static void assertScore(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            throw new AssertionError(label + ": ожидалось " + expected + ", получено " + actual);
        }
    }

    private static final class Fixture {
        private final String query;
        private final float[] expected;

        private Fixture(String query, float... expected) {
            if (expected.length != DOCUMENTS.length) throw new IllegalArgumentException("Нужен эталон для каждого документа");
            this.query = query;
            this.expected = Arrays.copyOf(expected, expected.length);
        }
    }
}
