package monoforest.impl.document;

import monoforest.impl.BaseFactor;

public abstract class DocumentFactor extends BaseFactor {

    public abstract float[] calculateScore(String title, String document, String doc_id);

}