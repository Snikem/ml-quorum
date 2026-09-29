package monoforest.impl;
import org.apache.lucene.search.Query;

public abstract class BaseFactor {


    public abstract String getName();

    public abstract int getFeatureQty();

    public abstract String getDescription();

    public abstract void prepare();

    public abstract Query buildQuery(String[] queryStream, int featureIndex, Object... args);
}