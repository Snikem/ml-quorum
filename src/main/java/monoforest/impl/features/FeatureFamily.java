package monoforest.impl.features;

import monoforest.impl.DocumentMarco;
import org.apache.lucene.search.*;

import java.util.List;

public interface FeatureFamily {
    String getNameFamily();
    String getDescription();
    List<String> getAllFeaturesNames();
    Query buildLuceneQuery(String featureName, String[] queryTokenStream, Object... args);
    void prepare();
    List<FeatureBase> calculateAllFeaturesInFamily(String[] queryTokenStream, DocumentMarco document);
    FeatureBase calculateFeatureByName(String FeatureName, String[] queryTokenStream, DocumentMarco document);

}
