package monoforest.impl.features.documentFeatures;

import java.util.Arrays;
import java.util.List;

/** Общий список семейств для обоих индексаторов. Собственных расчетов нет. */
public final class DocumentFeatureFamilies {
    private DocumentFeatureFamilies() { }

    public static List<DocumentFeatureFamily> createDefault() {
        return Arrays.asList(new DocumentWordsFamily(), new DocumentCharactersFamily(),
                new DocumentNumbersFamily(), new DocumentParagraphsFamily(),
                new DocumentSizeFamily(), new DocumentUrlsFamily());
    }
}
