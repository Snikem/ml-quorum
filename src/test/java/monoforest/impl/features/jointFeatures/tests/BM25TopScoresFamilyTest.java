package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;

import monoforest.impl.DocumentMarco;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.jointFeatures.BM25TopScoresFamily;
import monoforest.impl.features.jointFeatures.BM25TopScoresFamily.Comparison;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.Collections;
import java.util.List;

/** JUnit-тест, запускается через mvn test. Фиксированная коллекция в памяти, внешний индекс не нужен. */
public class BM25TopScoresFamilyTest {
    private static final String FIELD = "text";
    // Та же коллекция, на которой зафиксированы значения BM25FamilyTest.
    private static final String[] BODIES = {
            "cat cat dog", "cat mouse", "dog mouse mouse", "bird dog", "",
            "cat ".repeat(100) + "dog", "DOG Cat cat,"
    };
    // Эталоны стандартного BM25 Lucene 8.6 (k1=1.2, b=0.75), в порядке выдачи.
    // За концом массива ожидается 0. Не пересчитывать эталоны через тестируемое семейство.
    private static final Fixture[] FIXTURES = {
            new Fixture("cat dog", 0.87404406f, 0.73666215f, 0.49697345f, 0.31678575f, 0.3063804f),
            new Fixture("cat cat dog", 1.4417077f, 1.3977633f, 0.9939469f, 0.31678575f, 0.3063804f),
            new Fixture("dog mouse", 1.1496032f, 0.7382177f, 0.31678575f, 0.3063804f, 0.07556096f),
            new Fixture("bird dog", 1.4212558f, 0.3063804f, 0.3063804f, 0.07556096f),
            new Fixture("Cat cat,", 2.1363838f),
            new Fixture("missing"), new Fixture(""), new Fixture("only_title")
    };

    @Test
    public void verifiesFeatureValues() throws Exception {
        int valuesChecked = 0;
        int conditionsChecked = 0;
        try (Directory directory = new ByteBuffersDirectory(); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                    .setSimilarity(new BM25Similarity(1.2f, 0.75f)))) {
                for (String body : BODIES) {
                    Document document = new Document();
                    document.add(new TextField(FIELD, body, Field.Store.NO));
                    document.add(new StringField("title", "only_title cat cat", Field.Store.NO));
                    writer.addDocument(document);
                }
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher nativeSearcher = new IndexSearcher(reader);
                nativeSearcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));
                MyTokenizer tokenizer = new MyTokenizer(analyzer);
                DocumentMarco unrelatedDocument = new DocumentMarco();
                unrelatedDocument.setTokensBody(Collections.singletonList("unrelated"));
                for (int n : new int[]{1, 3, 10}) {
                    try (BM25TopScoresFamily family = new BM25TopScoresFamily(reader, FIELD, n)) {
                        family.prepare();
                        List<String> names = family.getAllFeaturesNames();
                        if (names.size() != n) throw new AssertionError("Ожидалось " + n + " фичей");
                        for (Fixture fixture : FIXTURES) {
                            String[] tokens = tokenizer.tokenize(fixture.query).toArray(new String[0]);
                            BooleanQuery.Builder nativeQuery = new BooleanQuery.Builder();
                            for (String token : tokens) nativeQuery.add(new TermQuery(new Term(FIELD, token)), BooleanClause.Occur.SHOULD);
                            TopDocs nativeTop = nativeSearcher.search(nativeQuery.build(), n);
                            List<FeatureBase> all = family.calculateAllFeaturesInFamily(tokens, null);
                            List<FeatureBase> otherDocument = family.calculateAllFeaturesInFamily(tokens, unrelatedDocument);
                            if (all.size() != n || otherDocument.size() != n) throw new AssertionError("Размер вектора != n");
                            for (int i = 0; i < n; i++) {
                                String name = "BM25Top" + (i + 1) + "Score";
                                if (!names.get(i).equals(name)) throw new AssertionError("Неверный порядок имен фичей");
                                float expected = i < fixture.scores.length ? fixture.scores[i] : 0f;
                                String label = "n=" + n + " [" + fixture.query + "] " + name;
                                assertScore(expected, all.get(i).value, label);
                                assertScore(expected, otherDocument.get(i).value, label + " другой документ");
                                assertScore(expected, family.calculateFeatureByName(name, tokens, null).value, label + " single");
                                assertScore(expected, i < nativeTop.scoreDocs.length ? nativeTop.scoreDocs[i].score : 0f, label + " native");
                                valuesChecked++;
                                for (double threshold : new double[]{0, expected, Math.nextDown((double) expected), Math.nextUp((double) expected)}) {
                                    for (Comparison comparison : Comparison.values()) {
                                        boolean matches;
                                        switch (comparison) {
                                            case GT: matches = expected > threshold; break;
                                            case LT: matches = expected < threshold; break;
                                            case GE: matches = expected >= threshold; break;
                                            case LE: matches = expected <= threshold; break;
                                            default: throw new AssertionError(comparison);
                                        }
                                        Query condition = family.buildLuceneQuery(name, tokens, threshold, comparison);
                                        if (nativeSearcher.count(condition) != (matches ? reader.numDocs() : 0)) {
                                            throw new AssertionError(label + ": неверное условие " + comparison + " " + threshold);
                                        }
                                        conditionsChecked++;
                                    }
                                }
                            }
                            // Изменение результата не должно влиять на следующий расчет.
                            all.get(0).value = -123f;
                            assertScore(fixture.scores.length == 0 ? 0f : fixture.scores[0],
                                    family.calculateFeatureByName(names.get(0), tokens, null).value, "Независимость расчетов");
                        }
                    }
                }
                try {
                    new BM25TopScoresFamily(reader, FIELD, 0);
                    throw new AssertionError("n=0 должен быть отклонен");
                } catch (IllegalArgumentException expected) { }
            }
        }
        System.out.println("PASS: " + valuesChecked + " эталонных значений при n=1,3,10; "
                + conditionsChecked + " условий; независимость от документа и нули для недостающих позиций.");
    }

    private static void assertScore(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            throw new AssertionError(label + ": ожидалось " + expected + ", получено " + actual);
        }
    }

    private static final class Fixture {
        private final String query;
        private final float[] scores;
        private Fixture(String query, float... scores) { this.query = query; this.scores = scores; }
    }
}
