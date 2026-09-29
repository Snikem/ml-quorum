package monoforest.impl.features.jointFeatures.tests;

import org.junit.Test;
import monoforest.impl.features.AvailableIndexTest;

import monoforest.impl.DocumentMarco;
import monoforest.impl.LuceneIndexManager;
import monoforest.impl.MyTokenizer;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import monoforest.impl.features.jointFeatures.TitleExactMatchFamily;
import org.apache.lucene.search.Query;

import java.util.ArrayList;
import java.util.List;

public class TitleExactMatchFamilyIT extends AvailableIndexTest {

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
            FeatureFamily family = new TitleExactMatchFamily();
            System.out.println("Количество фичей для теста: " + family.getAllFeaturesNames().size());
            family.prepare();

            System.out.println("Запуск тестов для семейства: " + family.getNameFamily());
            System.out.println("Описание: " + family.getDescription());

            // 3. Динамически перебираем все фичи в этом семействе
            for (String featureName : family.getAllFeaturesNames()) {
                System.out.println("\n=======================================================");
                System.out.println("▶ Тестируем фичу: " + featureName);

                float targetCoefficient = 0.1f;
                Query luceneQuery;

                // Для Ratio ищем совпадение больше 50%
                if (featureName.equals("TitleExactMatchRatio")) {
                    luceneQuery = family.buildLuceneQuery(featureName, queryStream, targetCoefficient);
                }
                // Для Count ищем совпадения строго больше 2 слов
                else {
                    luceneQuery = family.buildLuceneQuery(featureName, queryStream, 1);
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
                    // ВАЖНО: Токенизируем ЗАГОЛОВОК, а не весь текст
                    // (предполагается, что такой метод есть в DocumentMarco)
                    doc.tokenizeTitle(myTokenizer);

                    // Считаем фичу
                    FeatureBase result = family.calculateFeatureByName(featureName, queryStream, doc);

                    boolean isPassed = false;
                    String errorMessage = "";

                    // Специфичная логика проверки для каждой фичи
                    if (featureName.equals("TitleExactMatchRatio")) {
                        // Ratio должно быть строго больше targetCoefficient (0.5)
                        if (result.value > targetCoefficient) {
                            isPassed = true;
                        } else {
                            errorMessage = "Ожидалось > " + targetCoefficient + ", но получено: " + result.value;
                        }
                    } else if (featureName.equals("TitleExactMatchCount")) {
                        // Count должен быть строго больше 2
                        if (result.value > 1.0f) {
                            isPassed = true;
                        } else {
                            errorMessage = "Ожидалось > 2.0, но получено: " + result.value;
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
