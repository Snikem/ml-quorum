package monoforest.impl.features.documentFeatures.tests;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.features.AvailableIndexTest;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.documentFeatures.*;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamily.Comparison;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.*;

import static org.junit.Assert.*;

/** Проверка сохраненных документных значений и числовых полей существующего индекса. */
public class DocumentFeaturesIT extends AvailableIndexTest {
    @Test
    public void verifiesStoredValuesAndRangeQueries() throws Exception {
        int maxDocs = Integer.getInteger("monoforest.testDocumentSamples", 20);
        assertTrue("Размер выборки должен быть > 0", maxDocs > 0);
        try (Directory directory = FSDirectory.open(Paths.get(AppConfig.getIndexDir()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            ScoreDoc[] hits = searcher.search(new MatchAllDocsQuery(), maxDocs).scoreDocs;
            assertTrue("В индексе нет документов для проверки", hits.length > 0);
            for (DocumentFeatureFamily family : DocumentFeatureFamilies.createDefault()) {
                // Сначала проверяем наличие полей. Их отсутствие до переиндексации — ошибка теста.
                List<List<FeatureBase>> storedValues = new ArrayList<>();
                for (ScoreDoc hit : hits) storedValues.add(family.readFromLuceneDocument(searcher.doc(hit.doc)));
                family.prepare();
                for (int i = 0; i < hits.length; i++) {
                    Document stored = searcher.doc(hits[i].doc);
                    String body = stored.get(AppConfig.getTextField());
                    String title = stored.get(AppConfig.getTitleField());
                    assertNotNull("Нужно сохраненное поле body", body);
                    assertNotNull("Нужно сохраненное поле title", title);
                    DocumentMarco document = new DocumentMarco();
                    document.setTitle(title);
                    document.setBody(body);
                    List<FeatureBase> calculated = family.calculateAllFeaturesInFamily(null, document);
                    for (int j = 0; j < calculated.size(); j++) {
                        assertEquals(family.getAllFeaturesNames().get(j) + ", Lucene doc=" + hits[i].doc,
                                Float.floatToIntBits(calculated.get(j).value), Float.floatToIntBits(storedValues.get(i).get(j).value));
                    }
                }
                for (int j = 0; j < family.getAllFeaturesNames().size(); j++) {
                    String field = family.getAllFeaturesNames().get(j);
                    double threshold = storedValues.get(0).get(j).value;
                    for (Comparison comparison : Comparison.values()) {
                        Query query = family.buildLuceneQuery(field, null, threshold, comparison);
                        for (int i = 0; i < hits.length; i++) {
                            float value = storedValues.get(i).get(j).value;
                            boolean expected;
                            switch (comparison) {
                                case GT: expected = value > threshold; break;
                                case GE: expected = value >= threshold; break;
                                case LT: expected = value < threshold; break;
                                case LE: expected = value <= threshold; break;
                                default: throw new AssertionError(comparison);
                            }
                            assertEquals(field + " " + comparison + " " + threshold + ", Lucene doc=" + hits[i].doc,
                                    expected, searcher.explain(query, hits[i].doc).isMatch());
                        }
                    }
                }
            }
        }
    }
}
