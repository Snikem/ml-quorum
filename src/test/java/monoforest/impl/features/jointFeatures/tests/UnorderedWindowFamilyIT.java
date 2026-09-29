package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;
import monoforest.impl.features.AvailableIndexTest;

import monoforest.impl.DocumentMarco;
import monoforest.impl.LuceneIndexManager;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import monoforest.impl.features.jointFeatures.UnorderedWindowFamily;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.List;

public class UnorderedWindowFamilyIT extends AvailableIndexTest {

    @Test
    public void verifiesIndexMatches() throws Exception {
        // Берем частую фразу, чтобы гарантированно найти тексты с >2 повторениями
        String testQuery = "high blood pressure";

        MyTokenizer myTokenizer = new MyTokenizer();
        String[] queryStream = myTokenizer.tokenize(testQuery).toArray(new String[0]);

        LuceneIndexManager indexManager = new LuceneIndexManager();

        try {
            indexManager.init();

            FeatureFamily family = new UnorderedWindowFamily();
            System.out.println("Количество фичей для теста: " + family.getAllFeaturesNames().size());

            System.out.println("Запуск тестов для семейства: " + family.getNameFamily());
            System.out.println("Описание: " + family.getDescription());

            for (String featureName : family.getAllFeaturesNames()) {
                System.out.println("\n=======================================================");
                System.out.println("▶ Тестируем фичу: " + featureName);

                Query luceneQuery = family.buildLuceneQuery(featureName, queryStream);
                System.out.println("Сгенерированный Lucene запрос: " + luceneQuery.toString());

                List<DocumentMarco> foundDocs = indexManager.searchDocuments(luceneQuery, 10000);
                System.out.println("Всего найдено документов в Lucene: " + foundDocs.size());

                if (foundDocs.isEmpty()) {
                    throw new AssertionError("Нет документов для проверки фичи " + featureName);
                }

                int numSamples = Math.min(100, foundDocs.size());
                List<DocumentMarco> sampledDocs = new ArrayList<>();
                double step = (double) foundDocs.size() / numSamples;

                for (int i = 0; i < numSamples; i++) {
                    sampledDocs.add(foundDocs.get((int) (i * step)));
                }

                System.out.println("Отобрано для проверки: " + sampledDocs.size() + " документов.");

                int passed = 0;
                int failed = 0;
                int superMatches = 0; // Счетчик для совпадений > 2

                for (DocumentMarco doc : sampledDocs) {
                    doc.tokenizeBody(myTokenizer);

                    FeatureBase result = family.calculateFeatureByName(featureName, queryStream, doc);

                    // Логика проверки: ищем совпадения больше 2
                    if (result.value > 2.0f) {
                        passed++;
                        superMatches++;
                        // Выводим информацию о документе, где фича сработала больше 2 раз
                        System.out.println("🔥 НАЙДЕНО > 2 СОВПАДЕНИЙ: " + result.value + " раз(а) в документе ID: " + doc.getDoc_id());
                    } else if (result.value > 0.0f) {
                        // Документ найден корректно, но совпадений 1 или 2
                        passed++;
                    } else {
                        failed++;
                        System.out.println("\n--- НАЙДЕНО РАСХОЖДЕНИЕ ---");
                        System.out.println("ID: " + doc.getDoc_id());
                        System.out.println("Ожидалось > 0, но получено: " + result.value);
                        System.out.println("---------------------------");
                    }
                }

                System.out.println("✅ УСПЕШНО (всего): " + passed);
                System.out.println("🚀 ИЗ НИХ БОЛЬШЕ 2 СОВПАДЕНИЙ: " + superMatches);
                if (failed > 0) {
                    throw new AssertionError(featureName + ": расхождений " + failed);
                }
            }

        } finally {
            indexManager.close();
        }
    }
}
