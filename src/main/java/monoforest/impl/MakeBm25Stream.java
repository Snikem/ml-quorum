package monoforest.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import monoforest.impl.joint.factors.JointSmarterBigramTrigram;
import monoforest.impl.joint.factors.JointUnorderedWindow;
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
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPInputStream;

public class MakeBm25Stream {

    static {
        RocksDB.loadLibrary();
    }

    private static final String INDEX_PATH = "/Volumes/Ex_Volume/msmarcoProcces/lucene_index";
    private static final String INPUT_DIR = "/Volumes/Ex_Volume/msmarco/qrels_with_queries";
    private static final String OUT_PATH = "/Volumes/Ex_Volume/DatasetStream/bm_25_streamTop100";
    private static final String TEXT_FIELD = "text";
    private static final String ID_FIELD = "DOCNO";

    private final IndexReader reader;
    private final IndexSearcher bm25Searcher;
    private final IndexSearcher tfidfSearcher;
    private final Analyzer analyzer;
    private final QueryParser parser;


    public MakeBm25Stream() throws Exception {
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
        MakeBm25Stream top = new MakeBm25Stream();
        HashMap<String, List<String>> queryInfos = top.getQueries();
        top.findAndSaveCandidates(queryInfos,5000, OUT_PATH);
        top.close();


    }

    private static Splitter mSpaceSplit = Splitter.on(' ').omitEmptyStrings().trimResults();

    public void getStream(float multiplicator, String output) {
        System.out.println("=== Feature Extraction Started ===");
        if( multiplicator > 1 || multiplicator < 0) {
            System.err.println("Error multiplicator");
        }
        // 1. Инициализация менеджера (загрузка словарей, Lucene и т.д.)
        // Это делается один раз перед циклом!
        System.out.println("Initializing FactorManager...");
        FactorManager manager = new FactorManager();
        JointUnorderedWindow f1 = new JointUnorderedWindow();
        f1.prepare();

        JointSmarterBigramTrigram f2 = new JointSmarterBigramTrigram();
        f2.prepare();

        System.out.println("FactorManager ready. Total features: " + manager.getTotalFeatureCount());

        int totalProcessed = 0;
        int errorCount = 0;
        int counter = (int)(60 * multiplicator);
        // Открываем файл для записи результатов
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(output)))) {
            FactorManager fm = new FactorManager();
            // 2. Цикл по 60 файлам (от 00 до 59)
            for (int i = 0; i < counter; i++) {
                // Формируем имя файла: qrels_doc_00_with_docs.tsv
                String fileName = String.format("qrels_final_%02d.tsv", i);
                File inputFile = new File(OUT_PATH, fileName);

                if (!inputFile.exists()) {
                    System.out.println("Skipping missing file: " + fileName);
                    continue;
                }

                System.out.println("Processing file: " + fileName);

                // Читаем файл построчно
                try (BufferedReader br = Files.newBufferedReader(inputFile.toPath(), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        try {
                            // 3. Парсинг строки
                            // Формат: Query \t 0 \t DocID \t JSON
                            String[] parts = line.split("\t");

                            if (parts.length != 5) {
                                System.out.println("PIZDA");
                                continue; // Битая строка
                            }

                            String queryText = parts[0];
                            String docId = parts[2];
                            JSONObject json = getJsonObject(parts[3]);
                            String rank = parts[4];

                            // Извлекаем поля для факторов
                            String title = json.optString("title", "");
                            String body = json.optString("body", "");
                            // Можно склеить headings и body, если нужно
                            String headings = json.optString("headings", "");

                            // 4. Расчет факторов
                           float[] features = fm.extractAll(queryText,title,body,docId);

                            // 5. Запись в выходной файл
                            writer.print("0");
                            writer.print("\t");
                            writer.print(queryText);
                            writer.print("\t");
                            writer.print(docId);
                            writer.print("\t");
                            writer.print(Float.valueOf(rank));
                            for (float f : features) {
                                writer.print("\t");
                                writer.print(f); // Java выведет 1.0, 0.5 и т.д.
                            }
                            writer.println();

                            totalProcessed++;

                            if (totalProcessed % 1000 == 0) {
                                System.out.print("\rProcessed lines: " + totalProcessed);
                            }

                        } catch (Exception e) {
                            // Ошибки парсинга отдельной строки не должны ломать весь процесс
                            errorCount++;
                            // e.printStackTrace(); // Раскомментируйте для отладки
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Error reading file: " + fileName);
                    e.printStackTrace();
                }
            }

        } catch (IOException e) {
            System.err.println("Error creating output file: " + output);
            e.printStackTrace();
        }

        System.out.println("\n=== Done ===");
        System.out.println("Total lines processed: " + totalProcessed);
        System.out.println("Errors/Skipped lines: " + errorCount);
        System.out.println("Output saved to: " + output);
    }

    @NotNull
    private static JSONObject getJsonObject(String jsonRaw) {

        // Очистка JSON от CSV-экранирования
        // Строка приходит в виде: "{""url"": ...}"
        // Нужно убрать кавычки по краям и заменить двойные кавычки внутри
        if (jsonRaw.startsWith("\"") && jsonRaw.endsWith("\"")) {
            jsonRaw = jsonRaw.substring(1, jsonRaw.length() - 1);
        }
        jsonRaw = jsonRaw.replace("\"\"", "\"");

        // Парсим JSON
        JSONObject json = new JSONObject(jsonRaw);
        return json;
    }
    public void findAndSaveCandidates(HashMap<String, List<String>> queries, int maxResults, String outputFilePath) throws IOException {
        Path outDirPath = Paths.get(outputFilePath);
        HashMap<Integer, ArrayList<QueryText>> topRes = new HashMap<>();
        for(int i = 0; i < 60; i++) {
            topRes.put(i, new ArrayList<QueryText>());
        }
        int counter = 0;
        for (Map.Entry<String, List<String>> entry : queries.entrySet()) {

            String query = entry.getKey();
            List<String> relevantIds = entry.getValue();
            if(counter % 1000 == 0) {
                System.out.println("done " + counter + " of " + queries.size() );
            }


            String rawQuery = query.trim();
            String cleanQuery = QueryTextUtils.removeLuceneSpecialOps(QueryTextUtils.removePunct(rawQuery));
            int low_bound = 10;
            if (cleanQuery.isEmpty()) continue;

            // Настройка парсера
            QueryParser parser = new QueryParser(TEXT_FIELD, analyzer);
            parser.setDefaultOperator(QueryParser.OR_OPERATOR);
            int randomNum = 0;
            try {
                Query parsedQuery = parser.parse(cleanQuery);

                // 1. Поиск по BM25 (инвертированный индекс)
                TopDocs hits = bm25Searcher.search(parsedQuery, maxResults);

                for (int i = 0; i < 10;i++){
                    if(low_bound > hits.scoreDocs.length && hits.scoreDocs.length !=0 ) {
                        randomNum = ThreadLocalRandom.current().nextInt(0, hits.scoreDocs.length);
                    } else {
                        randomNum = ThreadLocalRandom.current().nextInt(low_bound, hits.scoreDocs.length);
                    }
                    ScoreDoc sd = hits.scoreDocs[randomNum];

                    Document doc = reader.document(sd.doc);
                    String docNo = doc.get(ID_FIELD);
                    if(relevantIds.contains(docNo)) {
                        continue;
                    }
                    String[] parts_docId = docNo.split("_");
                    String fileIndex = parts_docId[2];
                    int mapKey = Integer.parseInt(fileIndex);
                    topRes.get(mapKey).add(new QueryText(query, docNo, randomNum));
                }

            } catch (Exception e) {
                System.err.println("Ошибка при обработке запроса ID: " + e.toString());
            }

            counter++;

        }







        for(int i = 0; i < 60; i++) {
            String splitIdStr = String.format("%02d", i);
            Path outputFileFinal = outDirPath.resolve("qrels_final_" + splitIdStr + ".tsv");
            System.out.println("SPLIT NUMBER " + i);
            Path docsFile = Paths.get(DOCS_DIR, "msmarco_doc_" + splitIdStr + ".gz");

            Path dbPath = outDirPath.resolve("temp_rocksdb_" + splitIdStr);
            try (Options options = new Options().setCreateIfMissing(true);
                 RocksDB db = RocksDB.open(options, dbPath.toString())) {
                indexDocsToSSD(docsFile, db);

                try (BufferedWriter writer = Files.newBufferedWriter(outputFileFinal, StandardCharsets.UTF_8)) {
                    List<QueryText> list = topRes.get(i);

                    for( QueryText queryText : list) {
                        byte[] docBytes = db.get(queryText.id_doc.getBytes(StandardCharsets.UTF_8));
                        if (docBytes == null) System.out.println("PIZDA#2");
                        JSONObject docJson = new JSONObject(new String(docBytes, StandardCharsets.UTF_8));
                        String tsvLine = String.format("%s\t0\t%s\t%s\t%s", queryText.text, queryText.id_doc, docJson.toString(), String.valueOf(queryText.rank));
                        writer.write(tsvLine);
                        writer.newLine();
                    }



                }

            } catch (RocksDBException e) {

                throw new RuntimeException(e);
            } catch (IOException e) {
                System.out.println(e);
                throw new RuntimeException(e);
            } finally {
                deleteDirectory(dbPath.toFile());
            }
        }


    }

    public HashMap<String, List<String>> getQueries() {
        HashMap<String, List<String>> res = new HashMap<String, List<String>>();

        for (int i = 0; i < 60; i++) {
            String fileName = String.format("qrels_doc_%02d_with_docs.tsv", i);
            File inputFile = new File(INPUT_DIR, fileName);
            try (BufferedReader br = Files.newBufferedReader(inputFile.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {

                        String[] parts = line.split("\t", 5);

                        // Защита от битых строк
                        if (parts.length != 5) {
                            System.out.println("PIZDA");
                            continue;
                        }
                        String query = parts[0];
                        String docid = parts[2];

                        if(res.containsKey(query)) {
                            res.get(query).add(docid);
                        } else {
                            List<String> ls = new ArrayList<>();
                            ls.add(docid);
                            res.put(query, ls);
                        }

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return res;
    }

    // Укажите путь к вашей директории с документами
    private static final String DOCS_DIR = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";
    private static final ObjectMapper mapper = new ObjectMapper();


    static class QueryText {
        String text;
        String id_doc;
        int rank;

        public QueryText(String text, String id_doc, int rank) {
            this.text = text;
            this.id_doc = id_doc;
            this.rank = rank;
        }
    }

    private void indexDocsToSSD(Path docsFile, RocksDB db) throws IOException {
        System.out.println("  [1/2] Запись документов в RocksDB...");
        try (FileInputStream fis = new FileInputStream(docsFile.toFile());
             GZIPInputStream gis = new GZIPInputStream(fis);
             BufferedReader br = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8))) {

            String line;
            long count = 0;

            while ((line = br.readLine()) != null) {
                try {
                    JSONObject docJson = new JSONObject(line);
                    String id = docJson.optString("docid");
                    if (!id.isEmpty()) {
                        db.put(id.getBytes(StandardCharsets.UTF_8), line.getBytes(StandardCharsets.UTF_8));
                        count++;
                    }
                } catch (Exception ignored) {

                }
            }
            System.out.println("    Всего документов в базе: " + count);
        }
    }

    private void deleteDirectory(File file) {
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) deleteDirectory(f);
        }
        file.delete();
    }

}
