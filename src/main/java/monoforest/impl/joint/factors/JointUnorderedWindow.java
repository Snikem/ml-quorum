package monoforest.impl.joint.factors;

import com.google.common.base.Splitter;
import monoforest.impl.AppConfig;
import monoforest.impl.MyTokenizer;
import monoforest.impl.joint.JointFactor;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.Math.round;

public class JointUnorderedWindow extends JointFactor {
    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";

    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private IndexSearcher searcher;
    private IndexReader reader;
    private IndexSearcher bm25Searcher;
    private IndexSearcher tfidfSearcher;
    private Analyzer analyzer;
    private List<LeafReaderContext> cachedLeaves;

    private static final Splitter mSpaceSplit = Splitter.on(' ').omitEmptyStrings().trimResults();
    // Тот же быстрый сет стоп-слов
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
            "if", "in", "into", "is", "it", "no", "not", "of", "on", "or",
            "such", "that", "the", "their", "then", "there", "these",
            "they", "this", "to", "was", "will", "with"
    ));



    @Override
    public float[] calculateScore(String query, String title, String document, String doc_id) {
        MyTokenizer myTokenizer = new MyTokenizer();
        List<String> queryWords = myTokenizer.tokenize(query);
        List<String> docWords = myTokenizer.tokenize(document);


        // Если в запросе меньше двух уникальных значимых слов, совпадений пар быть не может
        Set<String> uniqueQueryTerms = new HashSet<>(queryWords);
        if (uniqueQueryTerms.size() < 2) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float bigramPmiSum = scoreCommonNgrams(queryWords, docWords, 2);
        float trigramPmiSum = scoreCommonNgrams(queryWords, docWords, 3);
        // Считаем пересечения для узкого (4) и широкого (8) окна
        float window4Count = (float) countUnorderedWindow(docWords, uniqueQueryTerms, 4);
        float window8Count = (float) countUnorderedWindow(docWords, uniqueQueryTerms, 8);

        return new float[]{bigramPmiSum, trigramPmiSum, window4Count, window8Count};
    }
    /**
     * Алгоритм скользящего окна. Ищет пары РАЗНЫХ слов из запроса
     * на расстоянии не более windowSize друг от друга.
     */
    private int countUnorderedWindow(List<String> docWords, Set<String> queryTerms, int windowSize) {
        int matchCount = 0;
        int docLength = docWords.size();

        for (int i = 0; i < docLength; i++) {
            String w1 = docWords.get(i);

            // Если текущее слово не из запроса, идем дальше
            if (!queryTerms.contains(w1)) continue;
            // Смотрим вперед на размер окна (не выходя за границы документа)
            int endWindow = Math.min(i + windowSize + 2, docLength);
            for (int j = i + 1; j < endWindow; j++) {
                String w2 = docWords.get(j);

                // Если нашли другое слово из запроса, засчитываем совпадение
                if (queryTerms.contains(w2) && !w1.equals(w2)) {
                    matchCount++;
                }
            }
        }

        return matchCount;
    }


    private float scoreCommonNgrams(List<String> queryWords, List<String> docWords, int n) {
        if (queryWords.size() < n || docWords.size() < n) return 0f;

        // Шаг 1: Собираем уникальные N-граммы из запроса и сразу считаем/достаем их PMI
        Map<String, Float> queryNgramWeights = new HashMap<>();
        for (int i = 0; i <= queryWords.size() - n; i++) {
            List<String> ngramTokens = queryWords.subList(i, i + n);
            String ngramString = join(queryWords, i, n);

            // Ленивое вычисление: достаем из кэша или считаем через Lucene
            float pmi = calculatePmiThroughLucene(ngramTokens);

            queryNgramWeights.put(ngramString, pmi);
        }

        // Шаг 2: Идем по документу и суммируем веса найденных совпадений
        float totalPmiScore = 0f;
        for (int i = 0; i <= docWords.size() - n; i++) {
            String currentDocNgram = join(docWords, i, n);
            if (queryNgramWeights.containsKey(currentDocNgram)) {
                totalPmiScore += queryNgramWeights.get(currentDocNgram);
            }
        }

        return totalPmiScore;
    }

    private String join(List<String> words, int start, int n) {
        if (n == 2) return words.get(start) + " " + words.get(start + 1);
        return words.get(start) + " " + words.get(start + 1) + " " + words.get(start + 2);
    }

    private String fastStem(String word) {
        if (word.length() <= 3) return word;

        if (word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("es")) return word.substring(0, word.length() - 2);
        if (word.endsWith("s")) return word.substring(0, word.length() - 1);
        if (word.endsWith("ing")) return word.substring(0, word.length() - 3);
        if (word.endsWith("ed")) return word.substring(0, word.length() - 2);

        return word;
    }

    @Override
    public ArrayList<float[]> calculateForQueries(ArrayList<String> queries, String title, String document, String doc_id) {
        return null;
    }

    @Override
    public String getName() {
        return "JointUnorderedWindow_4_8";
    }

    @Override
    public int getFeatureQty() {
        return 4;
    }

    @Override
    public String getDescription() {
        return "Возвращает 2 числа: количество пар уникальных слов запроса, встретившихся в документе в пределах окна из 4 и 8 слов (порядок не важен).";
    }

    @Override
    public void prepare() {

        try {
            // Инициализация Lucene (Индексы и Searcher'ы)
            FSDirectory dir = FSDirectory.open(Paths.get(INDEX_PATH));
            reader = DirectoryReader.open(dir);
            searcher = new IndexSearcher(reader);

            this.analyzer = new StandardAnalyzer();

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Lucene index readers", e);
        }
    }

    @Override
    public Query buildQuery(String[] queryStream, int featureIndex, Object... args) {
        return null;
    }

    private float calculatePmiThroughLucene(List<String> words) {

        try {
            float docFreqProduct = 1.0f;

            // Используем BooleanQuery вместо PhraseQuery
            BooleanQuery.Builder booleanBuilder = new BooleanQuery.Builder();

            for (String word : words) {
                Term term = new Term(AppConfig.getTextField(), word);

                // Добавляем условие: документ ДОЛЖЕН содержать это слово (оператор AND)
                booleanBuilder.add(new TermQuery(term), BooleanClause.Occur.MUST);

                long dfTerm = reader.docFreq(term);
                if (dfTerm == 0) return 0f;

                docFreqProduct *= dfTerm;
            }

            BooleanQuery booleanQuery = booleanBuilder.build();
            TotalHitCountCollector collector = new TotalHitCountCollector();
            searcher.search(booleanQuery, collector);

            // Получаем количество документов, где есть ВСЕ слова из N-граммы
            long dfCooccur = collector.getTotalHits();

            if (dfCooccur == 0) return 0f;

            double pmi = Math.log( (dfCooccur * Math.pow(reader.numDocs(), words.size() - 1)) / docFreqProduct );

            return (float) Math.max(0.0, pmi);

        } catch (IOException e) {
            e.printStackTrace();
            return 0f;
        }
    }


}