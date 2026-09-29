package monoforest.impl.features.jointFeatures;
import monoforest.impl.AppConfig;
import monoforest.impl.DocumentMarco;
import monoforest.impl.features.FeatureBase;
import monoforest.impl.features.FeatureFamily;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import java.util.*;

public class TitleExactMatchFamily implements FeatureFamily {

    @FunctionalInterface
    private interface FeatureCalculator {
        FeatureBase calculate(MatchStats stats);
    }

    private final Map<String, FeatureCalculator> featureCalculators = new LinkedHashMap<>();

    public TitleExactMatchFamily() {
        // Регистрируем две фичи: долю совпадений и абсолютное количество
        featureCalculators.put("TitleExactMatchRatio", stats -> new FeatureBase("TitleExactMatchRatio", stats.ratio));
        featureCalculators.put("TitleExactMatchCount", stats -> new FeatureBase("TitleExactMatchCount", stats.count));
    }

    @Override
    public String getNameFamily() {
        return "JointTitleMatch";
    }

    @Override
    public String getDescription() {
        return "Семейство фичей, считающее точное совпадение уникальных слов между запросом и ЗАГОЛОВКОМ (title) документа (абсолютное количество и пропорция).";
    }

    @Override
    public List<String> getAllFeaturesNames() {
        return new ArrayList<>(featureCalculators.keySet());
    }

    @Override
    public void prepare() {
        // Подготовка не требуется, вычисления идут в памяти над токенами заголовка
    }

    @Override
    public Query buildLuceneQuery(String featureName, String[] queryTokenStream, Object... args) {
        if (queryTokenStream == null || queryTokenStream.length == 0) {
            return new MatchNoDocsQuery();
        }

        Set<String> uniqueTerms = new HashSet<>(Arrays.asList(queryTokenStream));
        if (uniqueTerms.isEmpty()) {
            return new MatchNoDocsQuery();
        }

        BooleanQuery.Builder booleanQueryBuilder = new BooleanQuery.Builder();

        for (String term : uniqueTerms) {
            booleanQueryBuilder.add(new TermQuery(new Term(AppConfig.getTitleField(), term)), BooleanClause.Occur.SHOULD);
        }

        int minMatches = 1;

        if (featureName.equals("TitleExactMatchRatio")) {
            // Для Ratio аргумент — это доля (от 0.0 до 1.0)
            float coefficient = (args.length > 0 && args[0] instanceof Number) ? ((Number) args[0]).floatValue() : 0.0f;
            minMatches = (int) Math.floor(uniqueTerms.size() * coefficient) + 1;

        } else if (featureName.equals("TitleExactMatchCount")) {
            // Для Count аргумент — это желаемое абсолютное количество
            int targetCount = (args.length > 0 && args[0] instanceof Number) ? ((Number) args[0]).intValue() : 0;
            minMatches = targetCount + 1;
        }

        // Ограничиваем сверху количеством уникальных слов в запросе
        minMatches = Math.min(minMatches, uniqueTerms.size());
        // Ограничиваем снизу 1 совпадением
        minMatches = Math.max(1, minMatches);

        booleanQueryBuilder.setMinimumNumberShouldMatch(minMatches);
        return booleanQueryBuilder.build();
    }

    @Override
    public List<FeatureBase> calculateAllFeaturesInFamily(String[] queryTokenStream, DocumentMarco document) {
        MatchStats stats = new MatchStats(queryTokenStream, document.getTitleTokens());
        List<FeatureBase> results = new ArrayList<>(featureCalculators.size());
        for (Map.Entry<String, FeatureCalculator> entry : featureCalculators.entrySet()) {
            results.add(entry.getValue().calculate(stats));
        }
        return results;
    }

    @Override
    public FeatureBase calculateFeatureByName(String featureName, String[] queryTokenStream, DocumentMarco document) {
        FeatureCalculator calculator = featureCalculators.get(featureName);
        if (calculator == null) {
            throw new IllegalArgumentException("Фича с именем '" + featureName + "' не найдена в семействе " + getNameFamily());
        }
        return calculator.calculate(new MatchStats(queryTokenStream, document.getTitleTokens()));
    }

    // ===================================================================================
    // Исполняющие функции
    // ===================================================================================

    /** Общая статистика для всех производных признаков одного вызова. */
    private static final class MatchStats {
        private final int count;
        private final float ratio;

        private MatchStats(String[] queryTokenStream, List<String> documentTokens) {
            Set<String> queryTerms = new HashSet<>(Arrays.asList(queryTokenStream));
            int queryLength = queryTerms.size();
            if (queryLength == 0) {
                count = 0;
                ratio = 0.0f;
                return;
            }

            queryTerms.retainAll(new HashSet<>(documentTokens));
            count = queryTerms.size();
            ratio = (float) count / queryLength;
        }
    }
}
