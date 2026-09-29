package monoforest.impl.joint;

import monoforest.impl.BaseFactor;

import java.io.IOException;
import java.util.ArrayList;

public abstract class JointFactor extends BaseFactor {

    public abstract float[] calculateScore(String query, String title, String document, String doc_id);
    public abstract ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id);

}