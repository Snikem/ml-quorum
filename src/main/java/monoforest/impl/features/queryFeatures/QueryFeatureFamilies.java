package monoforest.impl.features.queryFeatures;

import java.util.Arrays;
import java.util.List;

/** Список семейств; каждое семейство выполняет только свои общие расчеты. */
public final class QueryFeatureFamilies {
    private QueryFeatureFamilies() { }

    public static List<QueryFeatureFamily> createDefault() {
        return Arrays.asList(new QueryWordsFamily(), new QueryCharactersFamily(),
                new QueryNumbersFamily(), new QueryNounsFamily(), new QueryFrequencyFamily());
    }
}
