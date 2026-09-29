package monoforest.impl.query;

import monoforest.impl.BaseFactor;

public abstract class QueryFactor extends BaseFactor {

    public abstract float[] calculateScore(String query);

}