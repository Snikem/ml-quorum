package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;
import monoforest.impl.features.AvailableIndexTest;


import monoforest.impl.DocumentMarco;
import monoforest.impl.LuceneIndexManager;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import monoforest.impl.features.jointFeatures.ExactMatchFamily;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.List;

public class ExactMatchFamilyIT extends AvailableIndexTest {

    @Test
    public void verifiesIndexMatches() throws Exception {
        // Берем запрос с разными словами, чтобы проверить пропорции (Ratio)
        String testQuery = "high blood pressure treatment symptoms";

        MyTokenizer myTokenizer = new MyTokenizer();
        String[] queryStream = myTokenizer.tokenize(testQuery).toArray(new String[0]);

        LuceneIndexManager indexManager = new LuceneIndexManager();

        try {
            // 1. Инициализируем соединение с индексом
            indexManager.init();

            // 2. Подготавливаем наше новое семейство фичей
            FeatureFamily family = new ExactMatchFamily();
            System.out.println("Количество фичей для теста: " + family.getAllFeaturesNames().size());
            family.prepare();

            System.out.println("Запуск тестов для семейства: " + family.getNameFamily());
            System.out.println("Описание: " + family.getDescription());

            // 3. Динамически перебираем все фичи в этом семействе
            for (String featureName : family.getAllFeaturesNames()) {
                System.out.println("\n=======================================================");
                System.out.println("▶ Тестируем фичу: " + featureName);

                float targetCoefficient = 0.5f;
                Query luceneQuery;

                // Для Ratio ищем совпадение больше 50%
                if (featureName.equals("ExactMatchRatio")) {
                    luceneQuery = family.buildLuceneQuery(featureName, queryStream, 0.5f);
                }
// Для Count ищем совпадения строго больше 2 слов
                else {
                    luceneQuery = family.buildLuceneQuery(featureName, queryStream, 2);
                }

                System.out.println("Сгенерированный Lucene запрос: " + luceneQuery.toString());

                // Делаем поиск
                List<DocumentMarco> foundDocs = indexManager.searchDocuments(luceneQuery, 10000);
                System.out.println("Всего найдено документов в Lucene: " + foundDocs.size());

                if (foundDocs.isEmpty()) {
                    throw new AssertionError("Нет документов для проверки фичи " + featureName);
                }

                // 4. Выбираем до 100 документов для детальной проверки
                int numSamples = Math.min(100, foundDocs.size());
                List<DocumentMarco> sampledDocs = new ArrayList<>();
                double step = (double) foundDocs.size() / numSamples;

                for (int i = 0; i < numSamples; i++) {
                    sampledDocs.add(foundDocs.get((int) (i * step)));
                }

                System.out.println("Отобрано для проверки: " + sampledDocs.size() + " документов.");

                int passed = 0;
                int failed = 0;

                // 5. Прогоняем проверку
                for (DocumentMarco doc : sampledDocs) {
                    // Токенизируем текст
                    doc.tokenizeBody(myTokenizer);

                    // Считаем фичу
                    FeatureBase result = family.calculateFeatureByName(featureName, queryStream, doc);

                    boolean isPassed = false;
                    String errorMessage = "";

                    // Специфичная логика проверки для каждой фичи
                    if (featureName.equals("ExactMatchRatio")) {
                        // Ratio должно быть строго больше 0 (раз документ найден) и <= 1.0 (максимум 100%)
                        if (result.value > targetCoefficient) {
                            isPassed = true;
                        } else {
                            errorMessage = "Ожидалось от (0.0 до 1.0], но получено: " + result.value;
                        }
                    } else if (featureName.equals("ExactMatchCount")) {
                        // Count должен быть целым числом больше 0
                        if (result.value > 2.0f) {
                            isPassed = true;
                        } else {
                            errorMessage = "Ожидалось > 0, но получено: " + result.value;
                        }
                    }

                    if (isPassed) {
                        passed++;
                        // Раскомментируй, если хочешь видеть скор каждого документа:
                        // System.out.println("✅ ID: " + doc.getDoc_id() + " | Скор: " + result.value);
                    } else {
                        failed++;
                        System.out.println("\n--- НАЙДЕНО РАСХОЖДЕНИЕ ---");
                        System.out.println("ID: " + doc.getDoc_id());
                        System.out.println(errorMessage);
                        System.out.println("---------------------------");
                    }
                }

                // 6. Итоги
                System.out.println("✅ УСПЕШНО: " + passed);
                if (failed > 0) {
                    throw new AssertionError(featureName + ": расхождений " + failed);
                }
            }

        } finally {
            indexManager.close();
        }
    }
}
