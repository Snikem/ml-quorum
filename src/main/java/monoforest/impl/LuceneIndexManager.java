package monoforest.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamily;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamilies;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class LuceneIndexManager {

    private Directory dir;
    private IndexReader reader;
    private IndexSearcher searcher;
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Инициализация: открывает индекс, используя путь из .env файла.
     */
    public void init() throws IOException {
        String indexPath = AppConfig.getIndexDir();
        this.dir = FSDirectory.open(Paths.get(indexPath));
        this.reader = DirectoryReader.open(dir);
        this.searcher = new IndexSearcher(reader);
    }

    public List<DocumentMarco> searchDocuments(Query query, int maxHits) throws IOException {
        checkInitialized();
        List<DocumentMarco> results = new ArrayList<>();
        TopDocs topDocs = searcher.search(query, maxHits);

        for (ScoreDoc hit : topDocs.scoreDocs) {
            Document luceneDoc = searcher.doc(hit.doc);
            results.add(mapToDocumentMarco(luceneDoc));
        }

        return results;
    }

    /** Общий searcher для диагностики запросов; reader закрывается через close() менеджера. */
    public IndexSearcher getSearcher() {
        checkInitialized();
        return searcher;
    }

    public DocumentMarco getDocumentById(String docId) throws IOException {
        checkInitialized();
        Query query = new TermQuery(new Term("id", docId));
        TopDocs topDocs = searcher.search(query, 1);

        if (topDocs.totalHits.value > 0) {
            Document luceneDoc = searcher.doc(topDocs.scoreDocs[0].doc);
            return mapToDocumentMarco(luceneDoc);
        }
        return null;
    }

    public void close() throws IOException {
        if (reader != null) reader.close();
        if (dir != null) dir.close();
    }

    private DocumentMarco mapToDocumentMarco(Document luceneDoc) {
        String id = luceneDoc.get("id");
        String title = luceneDoc.get("title");
        String body = luceneDoc.get("text");

        return new DocumentMarco(id, title, body);
    }

    private void checkInitialized() {
        if (searcher == null) {
            throw new IllegalStateException("Индекс не открыт. Сначала вызовите метод init().");
        }
    }

    /**
     * Создает новый Lucene индекс. Пути берутся автоматически из .env файла.
     *
     * @param ramBufferMB Размер RAM-буфера (в МБ)
     */
    public static void createIndex(int ramBufferMB) {
        String inputDir = AppConfig.getInputDir();
        String indexDir = AppConfig.getIndexDir();

        System.out.println("Начинаем процесс создания индекса в: " + indexDir);

        try {
            List<DocumentFeatureFamily> documentFamilies = DocumentFeatureFamilies.createDefault();
            // Загрузить словарь до открытия индекса на перезапись.
            for (DocumentFeatureFamily family : documentFamilies) family.prepare();

            FieldType textType = new FieldType();
            textType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            textType.setTokenized(true);
            textType.setStored(true);
            textType.freeze();

            Directory dir = FSDirectory.open(Paths.get(indexDir));
            Analyzer analyzer = new WhitespaceAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            config.setRAMBufferSizeMB(ramBufferMB);

            IndexWriter writer = new IndexWriter(dir, config);

            File inputFolder = new File(inputDir);
            File[] gzFiles = inputFolder.listFiles((d, name) -> name.endsWith(".gz") && !name.startsWith("._"));

            if (gzFiles == null || gzFiles.length == 0) {
                System.err.println("В папке " + inputDir + " не найдено файлов .gz!");
                writer.close();
                dir.close();
                return;
            }

            int totalIndexed = 0;

            for (File file : gzFiles) {
                System.out.println("Обработка файла: " + file.getName());

                try (InputStream fileStream = new FileInputStream(file);
                     InputStream gzipStream = new GZIPInputStream(fileStream);
                     Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
                     BufferedReader buffered = new BufferedReader(decoder)) {

                    String line;
                    while ((line = buffered.readLine()) != null) {
                        try {
                            JsonNode json = mapper.readTree(line);
                            if(json.hasNonNull("docid") || json.hasNonNull("title") || json.hasNonNull("body")) {
                                System.out.println("PIZEC v manager lucene");
                            }

                            String docId = json.get("docid").asText();
                            String title = json.get("title").asText();
                            String body =  json.get("body").asText();

                            if (docId.isEmpty() || body.isEmpty()) continue;

                            Document doc = new Document();
                            doc.add(new StringField("id", docId, Field.Store.YES));
                            doc.add(new StringField("title", title, Field.Store.YES));
                            doc.add(new Field("text", body, textType));
                            for (DocumentFeatureFamily family : documentFamilies) {
                                family.addToLuceneDocument(doc, title, body);
                            }

                            writer.addDocument(doc);
                            totalIndexed++;

                            if (totalIndexed % 100000 == 0) {
                                System.out.println("Проиндексировано документов: " + totalIndexed);
                            }
                        } catch (Exception e) {
                            // Игнорируем битые строки
                        }
                    }
                }
            }

            writer.commit();
            writer.close();
            dir.close();

            System.out.println("Индексация успешно завершена! Всего документов: " + totalIndexed);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
