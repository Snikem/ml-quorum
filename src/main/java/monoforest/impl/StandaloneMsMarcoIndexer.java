package monoforest.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamily;
import monoforest.impl.features.documentFeatures.DocumentFeatureFamilies;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class StandaloneMsMarcoIndexer {

    // Папка, где лежат твои msmarco_doc_00.gz, msmarco_doc_01.gz и т.д.
    private static final String INPUT_DIR = System.getProperty("monoforest.input", "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/");

    // Папка, куда будет сохранен новый индекс (с поддержкой позиций)
    private static final String INDEX_DIR = System.getProperty("monoforest.index", "/Volumes/Ex_Volume/msmarcoProcces/lucene_index_positions");

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("Начинаем процесс индексации MS MARCO...");

        try {
            List<DocumentFeatureFamily> documentFamilies = DocumentFeatureFamilies.createDefault();
            // Загрузить словарь до открытия индекса на перезапись.
            for (DocumentFeatureFamily family : documentFamilies) family.prepare();

            // 1. Настраиваем тип поля для текста: позиции ВКЛЮЧЕНЫ, текст СОХРАНЯЕТСЯ
            FieldType textType = new FieldType();
            textType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            textType.setTokenized(true);
            textType.setStored(true); // ОБЯЗАТЕЛЬНО, чтобы потом вытащить текст для calculateScore
            textType.freeze();

            // 2. Открываем директорию для записи индекса
            Directory dir = FSDirectory.open(Paths.get(INDEX_DIR));
            Analyzer analyzer = new WhitespaceAnalyzer(); // Как в оригинале FlexNeuART
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE); // Перезапишет, если индекс уже был
            config.setRAMBufferSizeMB(1024); // Выделяем 1 ГБ оперативки под буфер для скорости

            IndexWriter writer = new IndexWriter(dir, config);

            // 3. Ищем все .gz файлы в директории
            File inputFolder = new File(INPUT_DIR);
            File[] gzFiles = inputFolder.listFiles((d, name) -> name.endsWith(".gz") && !name.startsWith("._"));

            if (gzFiles == null || gzFiles.length == 0) {
                System.err.println("В папке " + INPUT_DIR + " не найдено файлов .gz!");
                return;
            }

            int totalIndexed = 0;

            // 4. Проходим по каждому архиву
            for (File file : gzFiles) {
                System.out.println("Обработка файла: " + file.getName());

                // Читаем напрямую из GZIP
                try (InputStream fileStream = new FileInputStream(file);
                     InputStream gzipStream = new GZIPInputStream(fileStream);
                     Reader decoder = new InputStreamReader(gzipStream, "UTF-8");
                     BufferedReader buffered = new BufferedReader(decoder)) {

                    String line;
                    while ((line = buffered.readLine()) != null) {
                        try {
                            // Парсим JSON строку
                            JsonNode json = mapper.readTree(line);

                            // Формат MS MARCO v2 DOC: обычно поля docid, url, title, body
                            // Берем поля аккуратно, чтобы не получить null
                            String docId = json.get("docid").asText();
                            String title = json.get("title").asText();
                            String body =  json.get("body").asText();

                            if (docId.isEmpty() || body.isEmpty()) {
                                continue; // Пропускаем битые строки без ID или текста
                            }

                            // Убедись, что импортирован правильный класс:
// import org.apache.lucene.document.Document;

// 5. Создаем нативный документ Lucene
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
                            System.err.println("Ошибка парсинга строки: " + line);
                        }
                    }
                }
            }

            // 6. Завершаем работу
            System.out.println("Все файлы прочитаны. Сохраняем (commit) индекс на диск...");
            writer.commit();
            writer.close();
            dir.close();

            System.out.println("Индексация успешно завершена! Всего документов: " + totalIndexed);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
