//package monoforest.impl;
//
//import java.io.*;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.*;
//import java.util.*;
//import java.util.stream.Collectors;
//import java.util.stream.Stream;
//
//public class FeatureCombiner {
//
//    // === ПУТИ К ДАННЫМ (Замени на свои при необходимости) ===
//    private static final String QUERY_FEATURES_TSV = "/Volumes/Ex_Volume/msmarcoProcces/query_features.tsv";
//    private static final String DOC_FEATURES_DIR = "/Volumes/Ex_Volume/msmarcoProcces/doc_features_split/";
//    private static final String JSON_QUERIES_DIR = "/Volumes/Ex_Volume/msmarcoProcces/queries_output/";
//    private static final String OUTPUT_BIN_DIR = "/Volumes/Ex_Volume/msmarcoProcces/final_binaries/";
//
//    public static void main(String[] args) throws Exception {
//        System.out.println("=== Начинаем объединение признаков ===");
//        long startTime = System.currentTimeMillis();
//
//        // 1. Загрузка векторов запросов
//        Map<String, float[]> queryVectors = loadQueryFeatures();
//
//        // 2. Загрузка векторов документов (ВНИМАНИЕ: Требует много RAM!)
//        Map<String, float[]> docVectors = loadDocFeatures();
//
//        // 3. Создаем выходную директорию
//        Path outputDir = Paths.get(OUTPUT_BIN_DIR);
//        if (!Files.exists(outputDir)) {
//            Files.createDirectories(outputDir);
//        }
//
//        // 4. Обработка JSON файлов и запись новых бинарников
//        processJsonFiles(queryVectors, docVectors, outputDir);
//
//        long endTime = System.currentTimeMillis();
//        System.out.println("\n✅ Успешно завершено за: " + (endTime - startTime) / 1000 + " сек.");
//    }
//
//    // ==========================================
//    // ШАГ 1: Чтение векторов запросов
//    // ==========================================
//    private static Map<String, float[]> loadQueryFeatures() throws Exception {
//        System.out.print("Загрузка векторов запросов... ");
//        Map<String, float[]> map = new HashMap<>();
//
//        Path path = Paths.get(QUERY_FEATURES_TSV);
//        if (!Files.exists(path)) {
//            System.err.println("Файл " + QUERY_FEATURES_TSV + " не найден!");
//            return map;
//        }
//
//        try (BufferedReader br = Files.newBufferedReader(path)) {
//            String line;
//            while ((line = br.readLine()) != null) {
//                String[] parts = line.split("\t");
//                if (parts.length < 2) continue;
//
//                String qId = parts[0];
//                String[] strFeats = parts[1].split(",");
//                float[] feats = new float[strFeats.length];
//                for (int i = 0; i < strFeats.length; i++) {
//                    feats[i] = Float.parseFloat(strFeats[i]);
//                }
//                map.put(qId, feats);
//            }
//        }
//        System.out.println("Готово! Загружено запросов: " + map.size());
//        return map;
//    }
//
//    // ==========================================
//    // ШАГ 2: Чтение 60 бинарных файлов документов
//    // ==========================================
//    private static Map<String, float[]> loadDocFeatures() throws Exception {
//        System.out.println("Загрузка 12 млн векторов документов в память (это займет время и RAM)...");
//        // Задаем начальную емкость (capacity), чтобы избежать лишних реаллокаций памяти
//        Map<String, float[]> map = new HashMap<>(13_000_000);
//
//        for (int i = 0; i < 60; i++) {
//            String fileName = String.format("doc_features_%02d.bin", i);
//            Path filePath = Paths.get(DOC_FEATURES_DIR, fileName);
//
//            if (!Files.exists(filePath)) continue;
//
//            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(filePath)))) {
//                int count = dis.readInt();
//                int featSize = dis.readInt();
//
//                for (int j = 0; j < count; j++) {
//                    String docId = dis.readUTF();
//                    float[] feats = new float[featSize];
//                    for (int k = 0; k < featSize; k++) {
//                        feats[k] = dis.readFloat();
//                    }
//                    map.put(docId, feats);
//                }
//            }
//            if ((i + 1) % 10 == 0) {
//                System.out.println("  Прочитано файлов: " + (i + 1) + "/60...");
//            }
//        }
//        System.out.println("Готово! Загружено документов: " + map.size());
//        return map;
//    }
//    private static void processJsonFiles(Map<String, float[]> queryVectors, Map<String, float[]> docVectors, Path outputDir) throws Exception {
//        System.out.println("Слияние данных и генерация финальных бинарников...");
//        try (Stream<Path> paths = Files.list(Paths.get(JSON_QUERIES_DIR))) {
//            List<Path> jsonFiles = paths
//                    .filter(f -> f.getFileName().toString().endsWith(".json"))
//                    // Игнорируем скрытые файлы macOS, которые могут быть бинарными
//                    .filter(f -> !f.getFileName().toString().startsWith("._"))
//                    .collect(Collectors.toList());
//
//            int processedFiles = 0;
//
//            for (Path jsonFile : jsonFiles) {
//                String fileName = jsonFile.getFileName().toString();
//                String queryId = fileName.replace(".json", "");
//
//                Path outBinFile = outputDir.resolve(queryId + ".bin");
//
//                // Оборачиваем обработку конкретного файла в try-catch для fail-fast с информативным логом
//                try (BufferedReader br = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8);
//                     DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outBinFile)))) {
//
//                    String line;
//                    while ((line = br.readLine()) != null) {
//                        if (line.trim().isEmpty()) continue;
//
//                        float bm25 = fastExtractFloat(line, "\"bm25\":");
//                        float tfidf = fastExtractFloat(line, "\"tfidf\":");
//                        String docId = fastExtractString(line, "\"doc_no\":\"");
//
//                        if (docId == null) {
//                            System.out.println("PIZDA2");
//                            throw new IllegalArgumentException("Не найден doc_no в строке: " + line);
//                        }
//
//                        float[] qVec = queryVectors.getOrDefault(queryId, new float[0]);
//                        float[] dVec = docVectors.getOrDefault(docId, new float[0]);
//                        if (dVec.length != 12 || qVec.length != 10)
//                        {
//                            System.out.println("PIZDA");
//                        }
//
//                        dos.writeUTF(queryId);
//                        dos.writeUTF(docId);
//
//                        int totalFloats = 2 + qVec.length + dVec.length;
//                        dos.writeInt(totalFloats);
//
//                        dos.writeFloat(bm25);
//                        dos.writeFloat(tfidf);
//                        for (float f : qVec) dos.writeFloat(f);
//                        for (float f : dVec) dos.writeFloat(f);
//                    }
//                } catch (Exception e) {
//                    // Fail-fast: останавливаем скрипт и говорим, на каком именно файле сломались
//                    System.err.println("\n[CRITICAL ERROR] Скрипт остановлен из-за ошибки в файле: " + jsonFile.toAbsolutePath());
//                    throw e;
//                }
//
//                processedFiles++;
//                if (processedFiles % 500 == 0) {
//                    System.out.println("  Создано бинарников: " + processedFiles + "/" + jsonFiles.size());
//                }
//            }
//        }
//    }
//
//    // === Быстрые парсеры для строк (вместо org.json) ===
//    private static String fastExtractString(String jsonLine, String key) {
//        int start = jsonLine.indexOf(key);
//        if (start == -1) return null;
//        start += key.length();
//        int end = jsonLine.indexOf("\"", start);
//        if (end == -1) return null;
//        return jsonLine.substring(start, end);
//    }
//
//    private static float fastExtractFloat(String jsonLine, String key) {
//        int start = jsonLine.indexOf(key);
//        if (start == -1) return 0f;
//        start += key.length();
//        int end = start;
//        // Ищем конец числа (оно может содержать цифры, точку или минус)
//        while (end < jsonLine.length() &&
//                (Character.isDigit(jsonLine.charAt(end)) || jsonLine.charAt(end) == '.' || jsonLine.charAt(end) == '-')) {
//            end++;
//        }
//        try {
//            return Float.parseFloat(jsonLine.substring(start, end));
//        } catch (NumberFormatException e) {
//            return 0f;
//        }
//    }
//}
