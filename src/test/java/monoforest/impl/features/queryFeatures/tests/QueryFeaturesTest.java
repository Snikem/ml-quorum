package monoforest.impl.features.queryFeatures.tests;

import org.junit.Test;

import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.queryFeatures.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * JUnit-тест, запускается через mvn test. Фиксированные запросы, словарь и небольшой TSV вместо внешнего корпуса.
 * Для существительных используется ресурс /en-pos-maxent.bin, как при обычном расчете.
 * Старые классы в тесте не используются; эталоны зафиксированы после независимого сравнения.
 */
public class QueryFeaturesTest {
    private static final Set<String> DICTIONARY = Set.of("cat", "dog", "hello", "world", "known");
    private static final Map<String, Integer> FREQUENCIES = Map.of("cat", 3, "dog", 2, "mouse", 1, "bird", 1);
    // words, non-dictionary, vowels, consonants, special, numbers, nouns, log(min/max/mean).
    private static final Fixture[] FIXTURES = {
            new Fixture(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Fixture("", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Fixture(" \t", 0, 1, 0, 0, 0, 0, 0, 0, 0, 0),
            new Fixture("cat dog", 2, 0, 2, 4, 0, 0, 2, 1.0986123f, 1.3862944f, 1.2527629f),
            new Fixture("Cat, dog! 12.5", 3, 3, 2, 4, 3, 1, 2, 0, 0, 0),
            new Fixture("  cat cat mouse ", 3, 1, 5, 6, 0, 0, 3, 0, 1.3862944f, 1.011601f),
            new Fixture("The dog eats food.", 4, 3, 6, 8, 1, 0, 2, 0, 1.0986123f, .4054651f),
            new Fixture("Привет café 😀", 3, 3, 1, 2, 9, 0, 2, 0, 0, 0)
    };

    @Test
    public void verifiesFeatureValues() throws Exception {
        List<QueryFeatureFamily> families = QueryFeatureFamilies.createDefault();
        families.set(0, new QueryWordsFamily(DICTIONARY));
        families.set(4, new QueryFrequencyFamily(FREQUENCIES));
        Set<String> uniqueNames = new HashSet<>();
        for (QueryFeatureFamily family : families) {
            family.prepare();
            for (String name : family.getAllFeaturesNames()) {
                if (!uniqueNames.add(name)) throw new AssertionError("Повтор имени: " + name);
            }
        }
        if (uniqueNames.size() != 10) throw new AssertionError("Ожидалось 10 признаков");

        int valuesChecked = 0;
        for (Fixture fixture : FIXTURES) {
            int position = 0;
            for (QueryFeatureFamily family : families) {
                List<FeatureBase> all = family.calculateAllFeaturesInFamily(fixture.query);
                List<String> names = family.getAllFeaturesNames();
                if (all.size() != names.size()) throw new AssertionError("Размер вектора");
                for (int i = 0; i < all.size(); i++) {
                    String label = "[" + fixture.query + "] " + names.get(i);
                    float expected = fixture.values[position++];
                    assertValue(expected, all.get(i).value, label);
                    assertValue(expected, family.calculateFeatureByName(names.get(i), fixture.query).value, label + " single");
                    valuesChecked++;
                }
            }
        }

        DocumentMarco unrelated = new DocumentMarco();
        unrelated.setTitle("unrelated title");
        unrelated.setBody("unrelated body");
        for (QueryFeatureFamily family : families) {
            String[] tokens = {"cat", "dog"};
            List<FeatureBase> raw = family.calculateAllFeaturesInFamily("cat dog");
            List<FeatureBase> adapter = family.calculateAllFeaturesInFamily(tokens, unrelated);
            List<FeatureBase> withoutDocument = family.calculateAllFeaturesInFamily(tokens, null);
            for (int i = 0; i < raw.size(); i++) {
                String name = family.getAllFeaturesNames().get(i);
                assertValue(raw.get(i).value, adapter.get(i).value, name + " adapter");
                assertValue(raw.get(i).value, withoutDocument.get(i).value, name + " document independence");
                assertValue(raw.get(i).value, family.calculateFeatureByName(name, tokens, unrelated).value, name + " single adapter");
            }
            try {
                family.buildLuceneQuery(family.getAllFeaturesNames().get(0), tokens, 1);
                throw new AssertionError("Запросные семейства не должны строить Lucene-запрос");
            } catch (UnsupportedOperationException expected) { }
            try {
                family.calculateFeatureByName("unknown", "cat dog");
                throw new AssertionError("Неизвестная фича должна быть отклонена");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS: 5 семейств, " + valuesChecked + " фиксированных значений и независимость от документа.");
    }

    @Test
    public void checksTsvLoading() throws Exception {
        Path path = Files.createTempFile("query-frequency-fixture-", ".tsv");
        try {
            Files.writeString(path, "1\tcat cat dog\n2\tcat mouse\n3\tdog bird\n4\tCAT\nbroken_line\n", StandardCharsets.UTF_8);
            QueryFrequencyFamily family = new QueryFrequencyFamily(path);
            family.prepare();
            family.prepare(); // Повторная подготовка не должна удвоить частоты.
            for (Fixture fixture : FIXTURES) {
                List<FeatureBase> values = family.calculateAllFeaturesInFamily(fixture.query);
                for (int i = 0; i < 3; i++) assertValue(fixture.values[7 + i], values.get(i).value, "TSV " + fixture.query);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void checksConcurrentNouns() throws Exception {
        QueryNounsFamily family = new QueryNounsFamily();
        family.prepare();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                Fixture fixture = FIXTURES[i % FIXTURES.length];
                jobs.add(() -> {
                    assertValue(fixture.values[6], family.calculateFeatureByName(
                            QueryNounsFamily.NOUN_COUNT, fixture.query).value, "parallel nouns");
                    return null;
                });
            }
            for (Future<Void> result : executor.invokeAll(jobs)) result.get();
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertValue(float expected, float actual, String label) {
        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static final class Fixture {
        private final String query;
        private final float[] values;
        private Fixture(String query, float... values) { this.query = query; this.values = values; }
    }
}
