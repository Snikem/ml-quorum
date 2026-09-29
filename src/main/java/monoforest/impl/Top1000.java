package monoforest.impl;

import com.google.common.base.Splitter;
import monoforest.util.QueryTextUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Top1000 {
    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";
    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private final IndexReader reader;
    private final IndexSearcher bm25Searcher;
    private final IndexSearcher tfidfSearcher;
    private final Analyzer analyzer;
    private final QueryParser parser;


    public Top1000() throws Exception {
        FSDirectory dir = FSDirectory.open(Paths.get(INDEX_PATH));
        this.reader = DirectoryReader.open(dir);

        // Поисковик для BM25
        this.bm25Searcher = new IndexSearcher(reader);
        this.bm25Searcher.setSimilarity(new BM25Similarity(1.2f, 0.75f));

        // Поисковик для TF-IDF
        this.tfidfSearcher = new IndexSearcher(reader);
        this.tfidfSearcher.setSimilarity(new ClassicSimilarity());

        this.analyzer = new StandardAnalyzer();
        this.parser = new QueryParser(TEXT_FIELD, analyzer);
    }

    // Не забываем закрыть reader при завершении работы приложения
    public void close() throws Exception {
        if (reader != null) reader.close();
    }

    public static void main(String[] args) throws Exception {
        Top1000 top = new Top1000();
        List<QueryInfo> queries = loadQueriesFromJson(QUERIES_FILE);

        top.findAndSaveCandidates(queries, 5000, "/Volumes/Ex_Volume/msmarcoProcces/dd.json");


    }

    private static Splitter mSpaceSplit = Splitter.on(' ').omitEmptyStrings().trimResults();

    public void findAndSaveCandidates(List<QueryInfo> queries, int maxResults, String outputFilePath) {

        // Используем try-with-resources для автоматического закрытия файла
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputFilePath), StandardCharsets.UTF_8)) {

            // Заголовок файла (опционально)
            writer.write("query_id\tdoc_no\tbm25\ttfidf\n");
            int index = 0;
            for (QueryInfo queryInfo : queries) {
                String rawQuery = queryInfo.text.trim();
                String cleanQuery = QueryTextUtils.removeLuceneSpecialOps(QueryTextUtils.removePunct(rawQuery));

                if (cleanQuery.isEmpty()) continue;

                // Настройка парсера
                QueryParser parser = new QueryParser(TEXT_FIELD, analyzer);
                parser.setDefaultOperator(QueryParser.OR_OPERATOR);

                try {
                    Query parsedQuery = parser.parse(cleanQuery);

                    // 1. Поиск по BM25 (инвертированный индекс)
                    TopDocs hits = bm25Searcher.search(parsedQuery, maxResults);
                    int rank = 1;
                    for (ScoreDoc sd : hits.scoreDocs) {
                        float bm25Score = sd.score;
                        // 2. Расчет TF-IDF только для прошедших порог
                        Explanation tfIdfExpl = tfidfSearcher.explain(parsedQuery, sd.doc);
                        float tfidfScore = tfIdfExpl.getValue().floatValue();
                        // Достаем DOCNO (только если оба порога пройдены!)
                        Document doc = reader.document(sd.doc);
                        String docNo = doc.get(ID_FIELD);

                        // 3. Мгновенная запись в файл (не копим в памяти)
                        JSONObject jsonRecord = new JSONObject();
                        jsonRecord.put("query_id", queryInfo.id);
                        jsonRecord.put("doc_no", docNo);
                        jsonRecord.put("bm25", bm25Score);
                        jsonRecord.put("tfidf", tfidfScore);
                        jsonRecord.put("rank", rank);
                        rank++;

                        // Записываем JSON в виде одной строки (JSON Lines) + перенос строки
                        writer.write(jsonRecord.toString() + "\n");

                    }


                    // Периодически сбрасываем буфер на диск
                    writer.flush();

                } catch (Exception e) {
                    System.err.println("Ошибка при обработке запроса ID: " + queryInfo.id);
                }
                index++;
                System.out.println(index);
            }
            System.out.println("Результаты успешно сохранены в: " + outputFilePath);

        } catch (IOException e) {
            System.err.println("Ошибка записи в файл!");
            e.printStackTrace();
        }
    }

    private static final String QUERIES_FILE = "/Volumes/Ex_Volume/msmarcoProcces/input_data/dev/QuestionFields.jsonl";

    static class QueryInfo {
        String id;
        String text;

        public QueryInfo(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private static List<QueryInfo> loadQueriesFromJson(String filePath) {
        List<QueryInfo> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject json = new JSONObject(line);
                    String id = json.optString("DOCNO");
                    String text = json.optString("text_raw");
                    list.add(new QueryInfo(id, text));
                } catch (Exception e) {}
            }
        } catch (IOException e) { e.printStackTrace(); }
        return list;
    }
}
