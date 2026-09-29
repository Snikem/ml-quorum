package monoforest.impl.features.documentFeatures.tests;

import org.junit.Test;

import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.documentFeatures.*;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamily.Comparison;
import monoforest.impl.features.documentFeatures.DocumentWordsFamily.Tokenization;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.util.*;

import static monoforest.impl.features.documentFeatures.DocumentWordsFamily.*;
import static monoforest.impl.features.documentFeatures.DocumentCharactersFamily.*;
import static monoforest.impl.features.documentFeatures.DocumentNumbersFamily.*;
import static monoforest.impl.features.documentFeatures.DocumentParagraphsFamily.*;
import static monoforest.impl.features.documentFeatures.DocumentSizeFamily.*;
import static monoforest.impl.features.documentFeatures.DocumentUrlsFamily.*;

/** JUnit-тест, запускается через mvn test. Фиксированные значения, индекс в памяти; старые факторы не используются. */
public class DocumentFeaturesTest {
    private static final Set<String> DICTIONARY = Set.of("cat", "dog", "hello", "world", "known");
    // Порядок эталонов совпадает со старым документным вектором, независимо от группировки семейств.
    private static final List<String> FIELDS = Arrays.asList(
            FIELD_WORD_COUNT, FIELD_VOWEL_COUNT, FIELD_SPECIAL_CHARACTER_COUNT, FIELD_PARAGRAPH_COUNT,
            FIELD_TITLE_NUMBER_COUNT, FIELD_BODY_NUMBER_COUNT,
            FIELD_TITLE_NON_DICTIONARY_COUNT, FIELD_BODY_NON_DICTIONARY_COUNT,
            FIELD_SIZE_KBYTE, FIELD_NON_DICTIONARY_RATIO, FIELD_NON_DICTIONARY_RATIO_COUNT, FIELD_HAS_URL);
    private static final Fixture[] FIXTURES = {
            new Fixture(null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Fixture("Cat 12", "dog dog!\n\nzzz 3.5 https://x",
                    7, 2, 5, 2, 1, 1, 1, 4, 33 / 1024f, 6 / 9f, 6, 1),
            new Fixture("hello,", "WORLD _ 007 café Привет 😀",
                    7, 2, 11, 1, 0, 1, 1, 5, 41 / 1024f, 4 / 6f, 4, 0),
            new Fixture("www", ".cat", 2, 1, 1, 1, 0, 0, 1, 1, 7 / 1024f, .5f, 1, 1),
            new Fixture(" \t", "\n\n", 0, 0, 0, 0, 0, 0, 0, 0, 4 / 1024f, 0, 0, 0)
    };

    @Test
    public void verifiesFeatureValues() throws Exception {
        List<DocumentFeatureFamily> families = DocumentFeatureFamilies.createDefault();
        families.set(0, new DocumentWordsFamily(Tokenization.WHITESPACE, Tokenization.UNICODE_WORDS, DICTIONARY));
        Set<String> names = new HashSet<>();
        for (DocumentFeatureFamily family : families) {
            family.prepare();
            for (String name : family.getAllFeaturesNames()) {
                if (!names.add(name)) throw new AssertionError("Повтор поля: " + name);
            }
        }
        if (!names.equals(new HashSet<>(FIELDS))) throw new AssertionError("Ожидалось 12 документных фичей");
        int conditions = 0;
        try (Directory directory = new ByteBuffersDirectory(); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (int i = 0; i < FIXTURES.length; i++) {
                    Fixture fixture = FIXTURES[i];
                    Document document = new Document();
                    document.add(new StringField("id", Integer.toString(i), Field.Store.YES));
                    for (DocumentFeatureFamily family : families) {
                        family.addToLuceneDocument(document, fixture.title, fixture.body);
                        // Повторная запись в Document заменяет поля, а не добавляет второе значение.
                        family.addToLuceneDocument(document, fixture.title, fixture.body);
                        for (String field : family.getAllFeaturesNames()) {
                            if (document.getFields(field).length != 2) throw new AssertionError("Дубли полей: " + field);
                        }
                    }
                    writer.addDocument(document);
                }
                Document oldDocument = new Document();
                oldDocument.add(new StringField("id", "without_features", Field.Store.YES));
                writer.addDocument(oldDocument);
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                for (DocumentFeatureFamily family : families) {
                    for (int i = 0; i < FIXTURES.length; i++) {
                        Fixture fixture = FIXTURES[i];
                        Document stored = searcher.doc(i);
                        List<FeatureBase> actual = family.calculateAllFeaturesInFamily(null, fixture.document());
                        List<FeatureBase> fromIndex = family.readFromLuceneDocument(stored);
                        List<String> fields = family.getAllFeaturesNames();
                        for (int j = 0; j < fields.size(); j++) {
                            String field = fields.get(j);
                            float expected = fixture.expected[FIELDS.indexOf(field)];
                            assertValue(expected, actual.get(j).value, field + " calculation");
                            assertValue(expected, fromIndex.get(j).value, field + " stored");
                            assertValue(expected, family.calculateFeatureByName(field,
                                    new String[]{"unrelated", "query"}, fixture.document()).value, field + " single");
                        }
                    }
                    for (String field : family.getAllFeaturesNames()) {
                        Set<Double> thresholds = new LinkedHashSet<>(Arrays.asList(
                                -Double.MAX_VALUE, -1d, -Double.MIN_VALUE, -0d, 0d, Double.MIN_VALUE,
                                .5d, 1d, 2.5d, (double) Float.MAX_VALUE, Double.MAX_VALUE));
                        for (Fixture fixture : FIXTURES) {
                            double value = fixture.expected[FIELDS.indexOf(field)];
                            thresholds.add(value);
                            thresholds.add(Math.nextDown(value));
                            thresholds.add(Math.nextUp(value));
                        }
                        for (double threshold : thresholds) {
                            for (Comparison comparison : Comparison.values()) {
                                Query query = family.buildLuceneQuery(field, null, threshold, comparison);
                                Set<String> expectedIds = new HashSet<>();
                                for (int i = 0; i < FIXTURES.length; i++) {
                                    float value = FIXTURES[i].expected[FIELDS.indexOf(field)];
                                    if (matches(value, threshold, comparison)) expectedIds.add(Integer.toString(i));
                                }
                                Set<String> actualIds = new HashSet<>();
                                for (ScoreDoc hit : searcher.search(query, reader.maxDoc()).scoreDocs) {
                                    actualIds.add(searcher.doc(hit.doc).get("id"));
                                }
                                if (!actualIds.equals(expectedIds)) {
                                    throw new AssertionError(field + " " + comparison + " " + threshold
                                            + ": expected=" + expectedIds + " actual=" + actualIds);
                                }
                                conditions++;
                            }
                        }
                        if (!family.buildLuceneQuery(field, null, 1).equals(
                                family.buildLuceneQuery(field, null, 1, Comparison.GT))) {
                            throw new AssertionError("По умолчанию должен использоваться GT");
                        }
                    }
                    try {
                        family.readFromLuceneDocument(searcher.doc(FIXTURES.length));
                        throw new AssertionError("Отсутствующее поле нельзя считать нулем");
                    } catch (IllegalStateException expected) { }
                }
            }
        }
        System.out.println("PASS: 6 семейств, 60 фиксированных значений, " + conditions
                + " диапазонных запросов и чтение из индекса.");
    }

    @Test
    public void checksTokenizationParameters() throws Exception {
        for (Tokenization count : Tokenization.values()) {
            for (Tokenization ratio : Tokenization.values()) {
                DocumentWordsFamily family = new DocumentWordsFamily(count, ratio, DICTIONARY);
                DocumentMarco document = new DocumentMarco();
                document.setTitle("Cat, zzz");
                document.setBody("dog! dog");
                float[] expected = {4, count == Tokenization.WHITESPACE ? 2 : 1,
                        count == Tokenization.WHITESPACE ? 1 : 0,
                        ratio == Tokenization.WHITESPACE ? .75f : .25f,
                        ratio == Tokenization.WHITESPACE ? 3 : 1};
                List<FeatureBase> actual = family.calculateAllFeaturesInFamily(null, document);
                try (Directory directory = new ByteBuffersDirectory(); WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
                    try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                        Document indexed = new Document();
                        family.addToLuceneDocument(indexed, document.getTitle(), document.getBody());
                        writer.addDocument(indexed);
                    }
                    try (DirectoryReader reader = DirectoryReader.open(directory)) {
                        IndexSearcher searcher = new IndexSearcher(reader);
                        for (int i = 0; i < expected.length; i++) {
                            String field = family.getAllFeaturesNames().get(i);
                            assertValue(expected[i], actual.get(i).value, count + "/" + ratio + " " + field);
                            if (searcher.count(family.buildLuceneQuery(field, null, expected[i], Comparison.GT)) != 0
                                    || searcher.count(family.buildLuceneQuery(field, null, expected[i], Comparison.GE)) != 1) {
                                throw new AssertionError("Параметры токенизации не учтены при индексации");
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean matches(float value, double threshold, Comparison comparison) {
        switch (comparison) {
            case GT: return value > threshold;
            case GE: return value >= threshold;
            case LT: return value < threshold;
            case LE: return value <= threshold;
            default: throw new AssertionError(comparison);
        }
    }

    private static void assertValue(float expected, float actual, String label) {
        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static final class Fixture {
        private final String title;
        private final String body;
        private final float[] expected;
        private Fixture(String title, String body, float... expected) {
            this.title = title;
            this.body = body;
            this.expected = expected;
        }
        private DocumentMarco document() {
            DocumentMarco document = new DocumentMarco();
            document.setTitle(title);
            document.setBody(body);
            return document;
        }
    }
}
