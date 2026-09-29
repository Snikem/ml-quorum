package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;

import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.jointFeatures.TFIDFFamily;
import monoforest.impl.features.jointFeatures.TFIDFFamily.Comparison;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
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
 * также статистику IDF и проверить реальные результаты пороговых запросов.
 *
 * N = 5; df(cat) = df(mouse) = 2; df(dog) = 3; df(bird) = 1.
 * IDF = ln((N + 1) / (df + 1)) + 1. TF — число вхождений в body.
 * Эталоны рассчитаны отдельно по этой формуле и округлены до float.
 * Не заменять константы результатами TFIDFFamily: это скроет регрессии.
 */
public class TFIDFFamilyTest {
    private static final String FIELD = "text";
    private static final String TITLE = "only_title cat cat";
    private static final String[][] DOCUMENTS = {
            {"D1", "cat cat dog"},
            {"D2", "cat mouse"},
            {"D3", "dog mouse mouse"},
            {"D4", "bird dog"},
            {"D5", ""}
    };

    // Порядок значений в каждой строке: D1, D2, D3, D4, D5.
    private static final Fixture[] FIXTURES = {
            new Fixture("cat dog", 4.79175949f, 1.69314718f, 1.40546513f, 1.40546513f, 0f),
            new Fixture("cat cat dog", 8.17805386f, 3.38629436f, 1.40546513f, 1.40546513f, 0f),
            new Fixture("dog mouse", 1.40546513f, 1.69314718f, 4.79175949f, 1.40546513f, 0f),
            new Fixture("bird dog", 1.40546513f, 0f, 1.40546513f, 3.50407743f, 0f),
            new Fixture("missing", 0f, 0f, 0f, 0f, 0f),
            new Fixture("", 0f, 0f, 0f, 0f, 0f),
            new Fixture("only_title", 0f, 0f, 0f, 0f, 0f)
    };

    @Test
    public void verifiesFeatureValues() throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
             Analyzer analyzer = new WhitespaceAnalyzer()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (String[] fixture : DOCUMENTS) {
                    Document document = new Document();
                    document.add(new StringField("id", fixture[0], Field.Store.YES));
                    document.add(new StringField("title", TITLE, Field.Store.YES));
                    document.add(new TextField(FIELD, fixture[1], Field.Store.YES));
                    writer.addDocument(document);
                }
            }

            try (DirectoryReader reader = DirectoryReader.open(directory);
                 TFIDFFamily family = new TFIDFFamily(reader, FIELD)) {
                family.prepare();
                IndexSearcher searcher = new IndexSearcher(reader);
                MyTokenizer tokenizer = new MyTokenizer(analyzer);
                int valuesChecked = 0;
                int queriesChecked = 0;
                List<String> names = family.getAllFeaturesNames();
                if (!names.contains(TFIDFFamily.TFIDF_SCORE)) throw new AssertionError("Нет TFIDFScore в семействе");
                int scoreIndex = names.indexOf(TFIDFFamily.TFIDF_SCORE);

                for (Fixture fixture : FIXTURES) {
                    String[] tokens = tokenizer.tokenize(fixture.query).toArray(new String[0]);
                    for (int i = 0; i < DOCUMENTS.length; i++) {
                        DocumentMarco document = new DocumentMarco();
                        document.setDoc_id(DOCUMENTS[i][0]);
                        document.setTitle(TITLE);
                        document.setBody(DOCUMENTS[i][1]);
                        document.setTokensBody(tokenizer.tokenize(document.getBody()));
                        document.tokenizeTitle(tokenizer);
                        String label = document.getDoc_id() + " + [" + fixture.query + "]";

                        float single = family.calculateFeatureByName(TFIDFFamily.TFIDF_SCORE, tokens, document).value;
                        float batch = family.calculateAllFeaturesInFamily(tokens, document).get(scoreIndex).value;
                        assertScore(fixture.expected[i], single, label + " single");
                        assertScore(fixture.expected[i], batch, label + " batch");
                        System.out.println("PASS " + label + " = " + Float.toString(single));
                        valuesChecked++;
                    }

                    // Включаем точные значения float и соседние double-пороги.
                    // Это проверяет строгость неравенств без округления порога до float.
                    double boundary = fixture.expected[0];
                    double[] thresholds = {-1.0, 0.0, 1.5, 3.0, 10.0,
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

    private static void assertQuery(IndexSearcher searcher, TFIDFFamily family, String[] tokens,
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

        Query query = family.buildLuceneQuery(TFIDFFamily.TFIDF_SCORE, tokens, threshold, comparison);
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
