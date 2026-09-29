package monoforest.impl;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;

public class DocFeatureDumper {

    // === НАСТРОЙКИ ===
    // Путь к папке, где лежат файлы msmarco_doc_00.gz ... msmarco_doc_59.gz
    private static final String INPUT_DIR = "/Volumes/Ex_Volume/msmarco/msmarco_v2_doc/";

    // ДИРЕКТОРИЯ, куда сохраним 60 бинарных файлов
    private static final String OUTPUT_DIR = "/Volumes/Ex_Volume/msmarcoProcces/doc_features_split/";

    public static void main(String[] args) {
        System.out.println("=== Starting Feature Dumper (60 Split Files) ===");

        // Убеждаемся, что выходная директория существует
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create output directory: " + OUTPUT_DIR);
            e.printStackTrace();
            return;
        }

        // 1. Инициализация менеджера факторов
        System.out.println("Initializing FactorManager...");
        FactorManager manager = new FactorManager();
        int docFeatSize = manager.getDocFactorCount();
        System.out.println("Feature vector size: " + docFeatSize);

        long globalStartTime = System.currentTimeMillis();
        int globalDocsProcessed = 0;

        // 2. Цикл по 60 файлам
        for (int i = 0; i < 60; i++) {
            String inFileName = String.format("msmarco_doc_%02d.gz", i);
            String outFileName = String.format("doc_features_%02d.bin", i);

            File inFile = new File(INPUT_DIR, inFileName);
            File outFile = new File(OUTPUT_DIR, outFileName);

            if (!inFile.exists()) {
                System.err.println("Warning: File not found: " + inFile.getAbsolutePath());
                continue;
            }

            System.out.println("\nProcessing file (" + (i + 1) + "/60): " + inFileName);

            int localDocsProcessed = 0;
            long fileStartTime = System.currentTimeMillis();

            // Открываем поток записи ДЛЯ ТЕКУЩЕГО ФАЙЛА
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outFile.toPath())))) {

                // Пишем заголовок (Header) для текущего бинарника
                dos.writeInt(0); // Плейсхолдер для количества документов в ЭТОМ файле
                dos.writeInt(docFeatSize); // Размер вектора признаков

                // Читаем GZIP построчно
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new GZIPInputStream(Files.newInputStream(inFile.toPath())), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = br.readLine()) != null) {
                        try {
                            // Парсинг JSON
                            JSONObject json = new JSONObject(line);

                            String docId = json.getString("docid");
                            String title = json.optString("title", "");
                            String body = json.optString("body", "");

                            // Расчет факторов
                            float[] feats = manager.extractDocFactors(title, body, docId);

                            // Запись в бинарник
                            dos.writeUTF(docId);
                            for (float f : feats) {
                                dos.writeFloat(f);
                            }

                            localDocsProcessed++;
                            globalDocsProcessed++;

                            if (localDocsProcessed % 10000 == 0) {
                                System.out.print("\rDocs in current file: " + localDocsProcessed + " | Total: " + globalDocsProcessed);
                            }

                        } catch (Exception e) {
                            // Игнорируем битые строки
                        }
                    }
                } catch (IOException e) {
                    System.err.println("\nError reading file: " + inFileName);
                    e.printStackTrace();
                }

                dos.flush();

            } catch (IOException e) {
                System.err.println("\nError writing to file: " + outFileName);
                e.printStackTrace();
            }

            // 3. ОБНОВЛЕНИЕ ЗАГОЛОВКА ТЕКУЩЕГО ФАЙЛА
            System.out.println("\nPatching header for " + outFileName + " with count: " + localDocsProcessed);
            try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
                raf.seek(0); // Встаем в самое начало файла
                raf.writeInt(localDocsProcessed); // Перезаписываем плейсхолдер
            } catch (IOException e) {
                System.err.println("Error patching header for " + outFileName);
                e.printStackTrace();
            }

            long fileEndTime = System.currentTimeMillis();
            System.out.println("Finished " + inFileName + " in " + (fileEndTime - fileStartTime) / 1000 + " sec.");
        }

        long globalEndTime = System.currentTimeMillis();
        System.out.println("\n==================================================");
        System.out.println("✅ All 60 files processed successfully!");
        System.out.println("Total docs processed across all files: " + globalDocsProcessed);
        System.out.println("Total time: " + (globalEndTime - globalStartTime) / 1000 + " sec.");
        System.out.println("Output directory: " + OUTPUT_DIR);
    }
}