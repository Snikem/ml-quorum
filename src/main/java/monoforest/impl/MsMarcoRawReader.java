package monoforest.impl;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

public class MsMarcoRawReader implements AutoCloseable {

    // === ВНУТРЕННИЙ КЛАСС (Элемент данных) ===
    public static class DocEntry {
        public final String id;
        public final String title;
        public final String fullText; // headings + "\n" + body

        public DocEntry(String id, String title, String fullText) {
            this.id = id;
            this.title = title;
            this.fullText = fullText;
        }

        @Override
        public String toString() {
            return String.format("[ID: %s] Title: %s... (Text len: %d)",
                    id, title.length() > 20 ? title.substring(0, 20) : title, fullText.length());
        }
    }

    // === ПОЛЯ КЛАССА ===
    private final File inputDir;
    private int currentFileIndex = 0;
    private final int MAX_FILE_INDEX = 59; // Файлы от 00 до 59
    private BufferedReader currentReader = null;

    /**
     * Конструктор. Проверяет наличие папки, но файлы пока не открывает.
     */
    public MsMarcoRawReader(String dirPath) throws FileNotFoundException {
        this.inputDir = new File(dirPath);
        if (!this.inputDir.exists() || !this.inputDir.isDirectory()) {
            throw new FileNotFoundException("Directory not found or is not a directory: " + dirPath);
        }
    }

    /**
     * Главный метод. Возвращает следующий документ из бесконечной ленты файлов.
     * @return DocEntry или null, если все файлы закончились.
     */
    public DocEntry getNext() throws IOException {
        while (true) {
            // 1. Если ридер закрыт или еще не создан — открываем следующий файл
            if (currentReader == null) {
                if (currentFileIndex > MAX_FILE_INDEX) {
                    return null; // Уперлись в лимит (59), конец работы
                }

                boolean opened = openNextFile();
                if (!opened) {
                    // Если файл не открылся (например, пропущен), увеличиваем индекс и пробуем снова
                    currentFileIndex++;
                    continue;
                }
            }

            // 2. Читаем строку из текущего файла
            String line = currentReader.readLine();

            if (line != null) {
                // Данные есть, парсим
                try {
                    return parseJsonLine(line);
                } catch (Exception e) {
                    // Ошибка в JSON конкретной строки — игнорируем, читаем дальше
                    // System.err.println("JSON Parse error in file index " + currentFileIndex);
                    continue;
                }
            } else {
                // Текущий файл закончился (EOF)
                closeCurrentReader();
                currentFileIndex++; // Готовимся к следующему файлу на следующей итерации while
            }
        }
    }

    /**
     * Логика открытия файла msmarco_doc_XX.gz
     */
    private boolean openNextFile() throws IOException {
        String fileName = String.format("msmarco_doc_%02d.gz", currentFileIndex);
        File file = new File(inputDir, fileName);

        if (!file.exists()) {
            // Можно раскомментировать, если хотите видеть пропуски
            // System.out.println("File skipped (not found): " + fileName);
            return false;
        }

        System.out.println("Opening file: " + fileName);

        // Chain: File -> FileInputStream -> Buffered -> GZIP -> Reader -> BufferedReader
        InputStream fileStream = Files.newInputStream(file.toPath());
        InputStream gzipStream = new GZIPInputStream(fileStream);
        Reader decoder = new InputStreamReader(gzipStream, StandardCharsets.UTF_8);
        this.currentReader = new BufferedReader(decoder);

        return true;
    }

    private void closeCurrentReader() throws IOException {
        if (currentReader != null) {
            currentReader.close();
            currentReader = null;
        }
    }

    /**
     * Парсинг JSON строки в объект DocEntry
     */
    private DocEntry parseJsonLine(String line) {
        JSONObject json = new JSONObject(line);

        String docId = json.getString("docid");
        String title = json.optString("title", "");
        String headings = json.optString("headings", "");
        String body = json.optString("body", "");

        // Склейка текста для факторов
        String fullBodyText = headings + "\n" + body;

        return new DocEntry(docId, title, fullBodyText);
    }

    @Override
    public void close() throws IOException {
        closeCurrentReader();
    }

    // === MAIN ДЛЯ ТЕСТИРОВАНИЯ ===
    public static void main(String[] args) {
        // Укажите ваш путь
        String inputPath = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";

        System.out.println("Starting reader test...");

        try (MsMarcoRawReader reader = new MsMarcoRawReader(inputPath)) {

            DocEntry doc;
            int count = 0;

            // Простой цикл, как вы хотели
            while ((doc = reader.getNext()) != null) {
                count++;

                // Для теста выводим каждый 10-тысячный
                if (count % 100000 == 0) {
                    System.out.println("Processed " + count + doc.title+ " docs. Last: " + doc.id + "asdasd" + doc.fullText );
                }

                // Здесь вызов вашего менеджера факторов:
                // float[] feats = manager.extractDocFactors(doc.title, doc.fullText, doc.id);
            }

            System.out.println("Done! Total docs: " + count);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}