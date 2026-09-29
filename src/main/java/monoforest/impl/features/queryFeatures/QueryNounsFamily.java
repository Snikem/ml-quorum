package monoforest.impl.features.queryFeatures;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.SimpleTokenizer;

import java.io.*;
import java.util.Objects;

public class QueryNounsFamily extends QueryFeatureFamily {
    public static final String NOUN_COUNT = "QueryNounCount";
    public static final String MODEL_RESOURCE = "/en-pos-maxent.bin";
    private ThreadLocal<POSTaggerME> taggers;

    public QueryNounsFamily() { super(NOUN_COUNT); }

    public QueryNounsFamily(POSModel model) {
        this();
        initialize(Objects.requireNonNull(model, "model"));
    }

    private void initialize(POSModel model) {
        // Модель общая, но POSTaggerME не потокобезопасен.
        taggers = ThreadLocal.withInitial(() -> new POSTaggerME(model));
    }

    @Override public String getNameFamily() { return "QueryNouns"; }
    @Override public String getDescription() { return "Количество существительных по OpenNLP (NN*)"; }

    @Override
    public void prepare() {
        if (taggers != null) return;
        InputStream stream = getClass().getResourceAsStream(MODEL_RESOURCE);
        if (stream == null) throw new IllegalStateException("Не найдена POS-модель " + MODEL_RESOURCE);
        try (InputStream input = stream) {
            initialize(new POSModel(input));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось загрузить POS-модель", e);
        }
    }

    @Override
    protected float[] calculateValues(String query) {
        if (taggers == null) throw new IllegalStateException("Сначала вызовите prepare()");
        if (query.trim().isEmpty()) return new float[]{0};
        String[] tokens = SimpleTokenizer.INSTANCE.tokenize(query);
        if (tokens.length == 0) return new float[]{0};
        int nouns = 0;
        for (String tag : taggers.get().tag(tokens)) if (tag.startsWith("NN")) nouns++;
        return new float[]{nouns};
    }
}
