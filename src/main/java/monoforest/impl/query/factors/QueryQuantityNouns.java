package monoforest.impl.query.factors;

import monoforest.impl.query.QueryFactor;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.SimpleTokenizer;
import org.apache.lucene.search.Query;

import java.io.InputStream;

public class QueryQuantityNouns extends QueryFactor {

    // Модель потокобезопасна, храним одну копию
    private POSModel model;

    // Таггер НЕ потокобезопасен, используем ThreadLocal, чтобы у каждого потока был свой экземпляр
    private ThreadLocal<POSTaggerME> taggerHolder;

    private SimpleTokenizer tokenizer;
    private boolean isReady = false;

    @Override
    public String getName() {
        return "QueryQuantityNouns";
    }

    @Override
    public int getFeatureQty() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Количество существительных в запросе (требует OpenNLP)";
    }

    @Override
    public void prepare() {
        try {
            // Загружаем модель POS-таггера из ресурсов (ОДИН РАЗ)
            InputStream modelIn = getClass().getResourceAsStream("/en-pos-maxent.bin");

            if (modelIn != null) {
                // POSModel потокобезопасна и потребляет много памяти, создаем её один раз
                this.model = new POSModel(modelIn);

                // Инициализируем фабрику для ThreadLocal.
                // Каждый новый поток будет создавать себе свой POSTaggerME, используя общую model.
                this.taggerHolder = ThreadLocal.withInitial(() -> new POSTaggerME(this.model));

                this.tokenizer = SimpleTokenizer.INSTANCE;
                this.isReady = true;
            } else {
                System.err.println("Warning: POS Model not found in resources (/en-pos-maxent.bin). Feature will return 0.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.isReady = false;
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    @Override
    public float[] calculateScore(String query) {
        if (!isReady || query == null || query.trim().isEmpty()) {
            return new float[]{0f};
        }

        // 1. Токенизация
        String[] tokens = tokenizer.tokenize(query);

        // Защита от пустых токенов (на всякий случай)
        if (tokens == null || tokens.length == 0) {
            return new float[]{0f};
        }

        // 2. POS-теггинг
        // ПОЛУЧАЕМ ТАГГЕР ИЗ ThreadLocal (уникальный для текущего потока)
        POSTaggerME localTagger = taggerHolder.get();

        String[] tags = localTagger.tag(tokens);

        // 3. Подсчет существительных
        int nounCount = 0;
        for (String tag : tags) {
            if (tag.startsWith("NN")) {
                nounCount++;
            }
        }

        return new float[] { (float) nounCount };
    }
}