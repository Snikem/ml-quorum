package monoforest.impl.features.jointFeatures;

import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

public class UnorderedWindowFamily implements FeatureFamily {

    private IndexSearcher searcher;
    private IndexReader reader;
    private Analyzer analyzer;

    // Определяем функциональный интерфейс для вычисления конкретной фичи
    @FunctionalInterface
    private interface FeatureCalculator {
        FeatureBase calculate(CalculationContext context);
    }

    // Мапа (реестр) всех фичей данного семейства
    private final Map<String, FeatureCalculator> featureCalculators = new LinkedHashMap<>();

    @Override
    public String getNameFamily() {
        return "JointUnorderedWindow";
    }

    @Override
    public String getDescription() {
        return "Семейство фичей на основе PMI N-грамм и неупорядоченных скользящих окон (размер 4 и 8).";
    }

    @Override
    public List<String> getAllFeaturesNames() {
        return new ArrayList<>(featureCalculators.keySet());
    }

    @Override
    public void prepare() {
        try {
            FSDirectory dir = FSDirectory.open(Paths.get(AppConfig.getIndexDir()));
            this.reader = DirectoryReader.open(dir);
            this.searcher = new IndexSearcher(reader);
            this.analyzer = new StandardAnalyzer();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Lucene index in FeatureFamily: " + getNameFamily(), e);
        }
    }

    public UnorderedWindowFamily() {
        // Регистрируем простые счетчики
        featureCalculators.put("BigramCount", this::calcBigramCount);
        featureCalculators.put("TrigramCount", this::calcTrigramCount);
        featureCalculators.put("UnorderedWindow4", this::calcWindow4);
        featureCalculators.put("UnorderedWindow8", this::calcWindow8);
    }

    @Override
    public Query buildLuceneQuery(String featureName, String[] queryTokenStream, Object... args) {
        var text_field = AppConfig.getTextField();
        if (queryTokenStream == null || queryTokenStream.length < 2) {
            return new BooleanQuery.Builder().build();
        }

        switch (featureName) {
            case "UnorderedWindow4":
                return buildWindowQuery(queryTokenStream, text_field, 4);
            case "UnorderedWindow8":
                return buildWindowQuery(queryTokenStream, text_field, 8);
            case "BigramCount":
                return buildNgramQuery(queryTokenStream, text_field, 2);
            case "TrigramCount":
                return buildNgramQuery(queryTokenStream, text_field, 3);
            default:
                throw new IllegalArgumentException("Неизвестная фича для построения запроса: " + featureName);
        }
    }

    /**
     * Создает Lucene-запрос, который ищет хотя бы одну точную N-грамму из запроса.
     */
    private Query buildNgramQuery(String[] tokens, String fieldName, int n) {
        if (tokens.length < n) {
            return new BooleanQuery.Builder().build();
        }

        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        // Проходим по запросу скользящим окном размера N
        for (int i = 0; i <= tokens.length - n; i++) {
            PhraseQuery.Builder pqBuilder = new PhraseQuery.Builder();
            for (int j = 0; j < n; j++) {
                pqBuilder.add(new Term(fieldName, tokens[i + j]));
            }
            // slop = 0 означает, что слова должны идти строго подряд
            pqBuilder.setSlop(0);
            booleanQueryBuilder.add(pqBuilder.build(), BooleanClause.Occur.SHOULD);
        }

        // Документ должен содержать хотя бы одну такую N-грамму
        booleanQueryBuilder.setMinimumNumberShouldMatch(1);
        return booleanQueryBuilder.build();
    }

    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] queryTokenStream, DocumentMarco document) {
        CalculationContext context = new CalculationContext(queryTokenStream, document.getTokensBody());
        List<FeatureBase> results = new ArrayList<>(featureCalculators.size());
        // Все калькуляторы используют общие промежуточные результаты этого вызова.
        for (Map.Entry<String, FeatureCalculator> entry : featureCalculators.entrySet()) {
            results.add(entry.getValue().calculate(context));
        }
        return results;
    }

    @Override
    public FeatureBase calculateFeatureByName(String featureName, String[] queryTokenStream, DocumentMarco document) {
        FeatureCalculator calculator = featureCalculators.get(featureName);
        if (calculator == null) {
            throw new IllegalArgumentException("Фича с именем '" + featureName + "' не найдена в семействе " + getNameFamily());
        }
        return calculator.calculate(new CalculationContext(queryTokenStream, document.getTokensBody()));
    }

    // ===================================================================================
    // Исполняющие функции (привязаны к Мапе)
    // ===================================================================================

    // ===================================================================================
    // Исполняющие функции (простые N-граммы)
    // ===================================================================================

    private FeatureBase calcBigramCount(CalculationContext context) {
        context.calculateNgrams();
        return new FeatureBase("BigramCount", context.bigramCount);
    }

    private FeatureBase calcTrigramCount(CalculationContext context) {
        context.calculateNgrams();
        return new FeatureBase("TrigramCount", context.trigramCount);
    }

    private FeatureBase calcWindow4(CalculationContext context) {
        context.calculateWindows();
        return new FeatureBase("UnorderedWindow4", context.window4Count);
    }

    private FeatureBase calcWindow8(CalculationContext context) {
        context.calculateWindows();
        return new FeatureBase("UnorderedWindow8", context.window8Count);
    }

    /**
     * Создается для одной пары запрос-документ. Каждая группа статистик считается
     * только при первом обращении и затем используется производными признаками.
     */
    private static final class CalculationContext {
        private final String[] queryTokens;
        private final List<String> documentTokens;
        private boolean ngramsCalculated;
        private boolean windowsCalculated;
        private int bigramCount;
        private int trigramCount;
        private int window4Count;
        private int window8Count;

        private CalculationContext(String[] queryTokens, List<String> documentTokens) {
            this.queryTokens = queryTokens;
            this.documentTokens = documentTokens;
        }

        private void calculateNgrams() {
            if (ngramsCalculated) return;
            ngramsCalculated = true;
            if (documentTokens == null || queryTokens.length < 2 || documentTokens.size() < 2) return;

            Set<String> bigrams = new HashSet<>();
            Set<String> trigrams = new HashSet<>();
            for (int i = 0; i < queryTokens.length - 1; i++) {
                String bigram = queryTokens[i] + " " + queryTokens[i + 1];
                bigrams.add(bigram);
                if (i + 2 < queryTokens.length) {
                    trigrams.add(bigram + " " + queryTokens[i + 2]);
                }
            }

            // Общий проход и общий префикс для биграммы и триграммы.
            for (int i = 0; i < documentTokens.size() - 1; i++) {
                String bigram = documentTokens.get(i) + " " + documentTokens.get(i + 1);
                if (bigrams.contains(bigram)) {
                    bigramCount++;
                }
                if (!trigrams.isEmpty() && i + 2 < documentTokens.size()
                        && trigrams.contains(bigram + " " + documentTokens.get(i + 2))) {
                    trigramCount++;
                }
            }
        }

        private void calculateWindows() {
            if (windowsCalculated) return;
            windowsCalculated = true;
            if (documentTokens == null || queryTokens.length < 2) return;

            Set<String> queryTerms = new HashSet<>(Arrays.asList(queryTokens));
            if (queryTerms.size() < 2) return;

            // Проверяем принадлежность каждого токена запросу один раз.
            boolean[] matchesQuery = new boolean[documentTokens.size()];
            for (int i = 0; i < documentTokens.size(); i++) {
                matchesQuery[i] = queryTerms.contains(documentTokens.get(i));
            }

            for (int i = 0; i < documentTokens.size(); i++) {
                if (!matchesQuery[i]) continue;
                String first = documentTokens.get(i);
                // Сохраняем прежние границы: расстояние <= windowSize + 1.
                int endWindow = Math.min(i + 8 + 2, documentTokens.size());
                for (int j = i + 1; j < endWindow; j++) {
                    if (matchesQuery[j] && !first.equals(documentTokens.get(j))) {
                        window8Count++;
                        if (j - i <= 4 + 1) {
                            window4Count++;
                        }
                    }
                }
            }
        }
    }

    // ===================================================================================
    // Логика построения Lucene Запросов
    // ===================================================================================

    private Query buildWindowQuery(String[] tokens, String fieldName, int windowSize) {
        Set<String> uniqueTerms = new HashSet<>(Arrays.asList(tokens));
        List<String> uniqueList = new ArrayList<>(uniqueTerms);

        if (uniqueList.size() < 2) {
            return new BooleanQuery.Builder().build();
        }

        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        for (int i = 0; i < uniqueList.size() - 1; i++) {
            for (int j = i + 1; j < uniqueList.size(); j++) {
                String w1 = uniqueList.get(i);
                String w2 = uniqueList.get(j);

                // Прямой порядок
                PhraseQuery.Builder pqBuilderForward = new PhraseQuery.Builder();
                pqBuilderForward.add(new Term(fieldName, w1));
                pqBuilderForward.add(new Term(fieldName, w2));
                pqBuilderForward.setSlop(windowSize);
                booleanQueryBuilder.add(pqBuilderForward.build(), BooleanClause.Occur.SHOULD);

                // Обратный порядок
                PhraseQuery.Builder pqBuilderBackward = new PhraseQuery.Builder();
                pqBuilderBackward.add(new Term(fieldName, w2));
                pqBuilderBackward.add(new Term(fieldName, w1));
                pqBuilderBackward.setSlop(windowSize);
                booleanQueryBuilder.add(pqBuilderBackward.build(), BooleanClause.Occur.SHOULD);
            }
        }

        booleanQueryBuilder.setMinimumNumberShouldMatch(1);
        return booleanQueryBuilder.build();
    }

}
