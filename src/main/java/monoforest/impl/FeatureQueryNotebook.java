package monoforest.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import monoforest.impl.features.FeatureFamily;
import monoforest.impl.features.documentFeatures.*;
import monoforest.impl.features.jointFeatures.*;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queries.function.FunctionRangeQuery;
import org.apache.lucene.search.*;
import org.apache.lucene.util.BytesRef;

import java.util.*;

/**
 * Запускай main из IDE и меняй настройки в ячейке 1, как в Python-ноутбуке.
 * Готовый индекс открывается через LuceneIndexManager.init(), путь берется из AppConfig.
 * Все фичи из documentFeatures и jointFeatures перебираются по getAllFeaturesNames().
 *
 * Из корня проекта:
 * mvn -f java/pom.xml compile exec:java
 *   -Dexec.mainClass=monoforest.impl.FeatureQueryNotebook
 *
 * Дополнительно: -Dnotebook.query="grammar terms medicine"
 * Одна фича: -Dnotebook.feature=BM25Score -Dnotebook.threshold=1.5
 * Поля текста и заголовка берутся из AppConfig, как в самих семействах.
 *
 * JSON ниже — диагностическое представление реального Java Query, не Elasticsearch DSL.
 * В searcher передается именно этот Query. Его score не обязан быть значением фичи:
 * FloatPoint/BooleanQuery/PhraseQuery проверяют условие, BM25/TFIDF используют ValueSource.
 */
public class FeatureQueryNotebook {
    // %% 1. Настройки эксперимента: фича > порог (строгое сравнение GT).
    private static final String QUERY_TEXT = System.getProperty("notebook.query", "grammar terms medicine");
    private static final String ONLY_FEATURE = System.getProperty("notebook.feature", "");
    private static final int SAMPLE_SIZE = 5;
    private static final int BM25_TOP_N = 3;
    private static final boolean PRINT_EXPLANATION = true;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Map<String, Double> thresholds() {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        // Для остальных фич порог 0.5. Здесь можно задать порог каждой по имени.
        thresholds.put("doc_word_count", 8.0);
        thresholds.put("doc_size_kbyte", 0.05);
        thresholds.put("doc_non_dictionary_ratio", 0.1);
        thresholds.put("ExactMatchCount", 1.0);
        thresholds.put("ExactMatchRatio", 0.5);
        thresholds.put("TitleExactMatchCount", 1.0);
        thresholds.put("TitleExactMatchRatio", 0.5);
        thresholds.put("BM25Score", 3.0);
        thresholds.put("TFIDFScore", 1.0);
        thresholds.put("UnorderedWindow4", 0.5);
        thresholds.put("UnorderedWindow8", 0.5);
        thresholds.put("BigramCount", 0.5);
        thresholds.put("TrigramCount", 0.5);
        return thresholds;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Double> thresholds = thresholds();
        String override = System.getProperty("notebook.threshold");
        if (override != null) {
            if (ONLY_FEATURE.isEmpty()) throw new IllegalArgumentException("Для порога укажи notebook.feature");
            double threshold = Double.parseDouble(override);
            if (!Double.isFinite(threshold)) throw new IllegalArgumentException("Порог должен быть конечным");
            thresholds.put(ONLY_FEATURE, threshold);
        }

        // %% 2. Индекс и токены запроса. Один reader для поиска и статистики фичей.
        List<DocumentFeatureFamily> documentFamilies = DocumentFeatureFamilies.createDefault();
        for (DocumentFeatureFamily family : documentFamilies) family.prepare();
        LuceneIndexManager indexManager = new LuceneIndexManager();
        try (WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer()) {
            indexManager.init();
            IndexSearcher searcher = indexManager.getSearcher();
            IndexReader reader = searcher.getIndexReader();
            MyTokenizer tokenizer = new MyTokenizer(analyzer);
            try (BM25Family bm25 = new BM25Family(reader, AppConfig.getTextField());
                 TFIDFFamily tfidf = new TFIDFFamily(reader, AppConfig.getTextField());
                 BM25TopScoresFamily topScores = new BM25TopScoresFamily(reader, AppConfig.getTextField(), BM25_TOP_N)) {
                String[] tokens = tokenizer.tokenize(QUERY_TEXT).toArray(new String[0]);
                System.out.println("Индекс: " + AppConfig.getIndexDir());
                System.out.println("Запрос: " + QUERY_TEXT + "\nТокены: " + Arrays.toString(tokens));

                // %% 3. Все документные и запросно-документные семейства.
                List<FeatureFamily> families = new ArrayList<>(documentFamilies);
                families.addAll(Arrays.asList(bm25, tfidf, new ExactMatchFamily(),
                        new TitleExactMatchFamily(), new UnorderedWindowFamily(), topScores));
                // UnorderedWindowFamily.prepare() открывает лишний индекс без close();
                // используемые здесь счетчики и buildLuceneQuery не требуют prepare().
                int inspected = 0;
                int errors = 0;
                for (FeatureFamily family : families) {
                    for (String feature : family.getAllFeaturesNames()) {
                        if (!ONLY_FEATURE.isEmpty() && !ONLY_FEATURE.equals(feature)) continue;
                        inspected++;
                        double threshold = thresholds.getOrDefault(feature, 0.5);
                        try {
                            inspectFeature(searcher, tokenizer, family, feature, tokens, threshold);
                        } catch (Exception e) {
                            errors++;
                            System.out.println("ОШИБКА " + feature + ": " + e);
                        }
                    }
                }
                if (inspected == 0) throw new IllegalArgumentException("Неизвестная фича: " + ONLY_FEATURE);
                System.out.printf(Locale.ROOT, "\nОбработано фичей: %d; ошибок: %d%n", inspected, errors);
                if (errors > 0) throw new IllegalStateException("Не все фичи выполнены; см. ошибки выше");
            }
        } finally {
            indexManager.close();
        }
    }

    // %% 4. Одна ячейка: имя + порог -> настоящий Lucene Query -> строка и JSON.
    public static void inspectFeature(IndexSearcher searcher, MyTokenizer tokenizer, FeatureFamily family,
                                      String feature, String[] tokens, double threshold) throws Exception {
        System.out.println("\n========== " + feature + " > " + threshold + " ==========");
        System.out.println(family.getNameFamily() + ": " + family.getDescription());
        if (family instanceof UnorderedWindowFamily) {
            System.out.println("ОГРАНИЧЕНИЕ: текущий builder игнорирует порог и ищет хотя бы одно "
                    + "совпадение. Для счетчиков это соответствует > t только при 0 <= t < 1.");
        }
        if (family instanceof ExactMatchFamily || family instanceof TitleExactMatchFamily) {
            System.out.println("ОГРАНИЧЕНИЕ: builder ограничивает minimumShouldMatch диапазоном "
                    + "[1, число слов]; крайние пороги могут дать расхождение с численным условием.");
        }
        if (family instanceof BM25TopScoresFamily) {
            System.out.println("Это фича запроса: результат условия одинаков для всех документов.");
        }
        Query query = family.buildLuceneQuery(feature, tokens, threshold);
        System.out.println("Lucene: " + query);
        ObjectNode experiment = JSON.createObjectNode();
        experiment.put("feature", feature);
        experiment.put("comparison", "GT");
        experiment.put("threshold", threshold);
        experiment.set("query", queryAsJson(query));
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(experiment));

        // %% 5. Исполняем тот же Query. Берем и hits, и независимую выборку документов,
        // чтобы увидеть также ложные отрицания. На большом индексе это только выборка.
        TopDocs hits = searcher.search(query, SAMPLE_SIZE);
        System.out.println("Найдено: " + hits.totalHits.value + " (" + hits.totalHits.relation + ")");
        Set<Integer> sample = new LinkedHashSet<>();
        for (ScoreDoc hit : hits.scoreDocs) sample.add(hit.doc);
        for (ScoreDoc hit : searcher.search(new MatchAllDocsQuery(), SAMPLE_SIZE).scoreDocs) sample.add(hit.doc);
        int mismatches = 0;
        for (int docId : sample) {
            Document stored = searcher.doc(docId);
            DocumentMarco document = readDocument(stored, tokenizer);
            float value = family.calculateFeatureByName(feature, tokens, document).value;
            Explanation explanation = searcher.explain(query, docId);
            boolean expected = value > threshold;
            boolean actual = explanation.isMatch();
            if (expected != actual) mismatches++;
            System.out.printf(Locale.ROOT,
                    "id=%s luceneDoc=%d value=%s > %s: %s; Lucene matches=%s [%s]%n",
                    stored.get("id"), docId, value, threshold, expected, actual,
                    expected == actual ? "OK" : "РАСХОЖДЕНИЕ");
            if (family instanceof DocumentFeatureFamily) {
                IndexableField field = stored.getField(feature);
                System.out.println("  Значение в индексе: " + (field == null || field.numericValue() == null
                        ? "ОТСУТСТВУЕТ: проверь индексацию FloatPoint + StoredField"
                        : field.numericValue()));
            }
            System.out.println("  title: " + document.getTitle());
            System.out.println("  body: " + document.getBody().replace('\n', ' ').substring(
                    0, Math.min(160, document.getBody().length())));
            if (PRINT_EXPLANATION) System.out.println("  explain (score запроса, не обязательно фича):\n" + explanation);
        }
        System.out.println("Проверено документов: " + sample.size() + "; расхождений: " + mismatches);
    }

    // %% 6. Диагностическое JSON-дерево без reflection и сериализации внутренностей reader.
    public static ObjectNode queryAsJson(Query query) {
        ObjectNode node = JSON.createObjectNode();
        node.put("class", query.getClass().getName());
        node.put("lucene", query.toString());
        if (query instanceof BooleanQuery) {
            BooleanQuery bool = (BooleanQuery) query;
            node.put("minimumShouldMatch", bool.getMinimumNumberShouldMatch());
            ArrayNode clauses = node.putArray("clauses");
            for (BooleanClause clause : bool.clauses()) {
                ObjectNode child = clauses.addObject();
                child.put("occur", clause.getOccur().name());
                child.set("query", queryAsJson(clause.getQuery()));
            }
        } else if (query instanceof TermQuery) {
            Term term = ((TermQuery) query).getTerm();
            node.put("field", term.field());
            node.put("term", term.text());
        } else if (query instanceof PhraseQuery) {
            PhraseQuery phrase = (PhraseQuery) query;
            node.put("slop", phrase.getSlop());
            ArrayNode terms = node.putArray("terms");
            for (int i = 0; i < phrase.getTerms().length; i++) {
                Term term = phrase.getTerms()[i];
                terms.addObject().put("field", term.field()).put("term", term.text())
                        .put("position", phrase.getPositions()[i]);
            }
        } else if (query instanceof FunctionRangeQuery) {
            FunctionRangeQuery range = (FunctionRangeQuery) query;
            node.put("valueSource", range.getValueSource().description());
            node.put("lower", range.getLowerVal());
            node.put("upper", range.getUpperVal());
            node.put("includeLower", range.isIncludeLower());
            node.put("includeUpper", range.isIncludeUpper());
        } else if (query instanceof PointRangeQuery) {
            PointRangeQuery range = (PointRangeQuery) query;
            node.put("field", range.getField());
            // В документных семействах этого ноутбука все диапазоны созданы FloatPoint.
            node.put("encoding", "FloatPoint");
            node.put("lower", FloatPoint.decodeDimension(range.getLowerPoint(), 0));
            node.put("upper", FloatPoint.decodeDimension(range.getUpperPoint(), 0));
            node.put("includeLower", true);
            node.put("includeUpper", true);
        } else if (query instanceof TermInSetQuery) {
            ArrayNode terms = node.putArray("terms");
            PrefixCodedTerms.TermIterator iterator = ((TermInSetQuery) query).getTermData().iterator();
            BytesRef term;
            while ((term = iterator.next()) != null) {
                terms.addObject().put("field", iterator.field()).put("term", term.utf8ToString());
            }
        } else if (query instanceof BoostQuery) {
            node.put("boost", ((BoostQuery) query).getBoost());
            node.set("query", queryAsJson(((BoostQuery) query).getQuery()));
        } else if (query instanceof ConstantScoreQuery) {
            node.set("query", queryAsJson(((ConstantScoreQuery) query).getQuery()));
        }
        // Для прочих типов сохраняются class + toString(), без выдуманной структуры.
        return node;
    }

    private static DocumentMarco readDocument(Document stored, MyTokenizer tokenizer) {
        String body = stored.get(AppConfig.getTextField());
        String title = stored.get(AppConfig.getTitleField());
        if (body == null || title == null) {
            throw new IllegalStateException("Для пересчета нужны сохраненные body и title: id=" + stored.get("id"));
        }
        DocumentMarco document = new DocumentMarco();
        document.setDoc_id(stored.get("id"));
        document.setBody(body);
        document.setTokensBody(tokenizer.tokenize(body));
        // tokenizeTitle пропускает пустую строку: пробел позволяет получить пустой список.
        document.setTitle(title.isEmpty() ? " " : title);
        document.tokenizeTitle(tokenizer);
        document.setTitle(title);
        return document;
    }

}
