package monoforest.impl;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatasetOrchestrator {

    // === НАСТРОЙКИ ПУТЕЙ ===

    // Куда сохранить итоговый файл
    private static final String OUTPUT_FILE = "/Volumes/Ex_Volume/DatasetStream/totalSet/";

    // Путь, где лежат твои qrels_final_XX.tsv
    private static final String OUT_PATH = "./data/output/";

    public static void main(String[] args) throws Exception {


        // Список для хранения имен созданных файлов
        List<String> generatedFiles = new ArrayList<>();

        System.out.println("=== Шаг 1: Генерация файлов признаков ===");

        // Запускаем первый поток
        String file1 = OUTPUT_FILE + "features_part1.tsv";
        //System.out.println("Запуск генерации: " + file1);
        //MakeRandomNegativeStream neg = new MakeRandomNegativeStream();
       // neg.getStream(1f, file1);

        // Запускаем второй поток
        String file2 = OUTPUT_FILE + "features_part2.tsv";
       // System.out.println("Запуск генерации: " + file2);
       // MakePositiveStream pos = new MakePositiveStream();//
        //pos.getStream(1.0f, file2);


        // Запускаем третий поток
        String file3 = OUTPUT_FILE + "features_part3.tsv";
        //System.out.println("Запуск генерации: " + file3);
        MakeBm25Stream bm = new MakeBm25Stream();
        bm.getStream(1.0f, file3);


//        System.out.println("\n=== Шаг 2: Сборка и перемешивание ===");
//        String trainFile = OUTPUT_FILE + "train.tsv";
//        String validFile = OUTPUT_FILE + "valid.tsv";
//
//        try {
//            combineShuffleAndSplit(generatedFiles, trainFile, validFile);
//            System.out.println("\nУспех! Данные разделены:");
//            System.out.println("Train датасет: " + trainFile);
//            System.out.println("Valid датасет: " + validFile);
//        } catch (IOException e) {
//            System.err.println("Произошла ошибка при обработке файлов!");
//            e.printStackTrace();
//        }
    }

    /**
     * Читает файл, берет указанный процент данных и сохраняет в новый временный файл.
     * @param inputFilePath Путь к исходному файлу
     * @param probability Доля данных (от 0.0 до 1.0)
     * @return Путь к новому отфильтрованному файлу
     */
    private static String sampleData(String inputFilePath, float probability) throws IOException {
        Path inputPath = Paths.get(inputFilePath);
        if (!Files.exists(inputPath)) {
            throw new FileNotFoundException("Файл не найден: " + inputFilePath);
        }

        System.out.println("Фильтрация файла (берём " + (probability * 100) + "%): " + inputFilePath);

        List<String> lines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return inputFilePath;

        int targetSize = Math.round(lines.size() * probability);
        // Ограничиваем, чтобы не выйти за пределы (минимум 1 строка, если файл не пуст)
        targetSize = Math.max(1, Math.min(lines.size(), targetSize));

        List<String> sampledLines = lines.subList(0, targetSize);

        String outputFilePath = inputFilePath.replace(".tsv", "_sampled.tsv");
        Files.write(Paths.get(outputFilePath), sampledLines, StandardCharsets.UTF_8);

        System.out.println("Создан сэмплированный файл: " + outputFilePath + " (" + targetSize + " строк)");
        return outputFilePath;
    }

    /**
     * Читает все переданные файлы, объединяет их в памяти,
     * перемешивает и записывает в итоговый файл.
     */
    private static void combineShuffleAndSplit(List<String> inputFiles, String trainOut, String validOut) throws IOException {
        List<String> allLines = new ArrayList<>();

        // 1. Читаем все файлы в память
        for (String fileName : inputFiles) {
            Path path = Paths.get(fileName);
            if (Files.exists(path)) {
                System.out.println("Чтение файла " + fileName + "...");
                allLines.addAll(Files.readAllLines(path, StandardCharsets.UTF_8));
            }
        }

        int totalLines = allLines.size();
        System.out.println("Всего собрано строк: " + totalLines);

        if (totalLines == 0) {
            System.out.println("Нет данных для обработки.");
            return;
        }

        // 2. Перемешиваем
        System.out.println("Перемешивание (shuffling)...");
        Collections.shuffle(allLines);

        // 3. Вычисляем индекс для разделения (98% для train)
        int splitIndex = (int) (totalLines * 0.95);

        // 4. Разрезаем список
        List<String> trainLines = allLines.subList(0, splitIndex);
        List<String> validLines = allLines.subList(splitIndex, totalLines);

        System.out.println("Размер Train (98%): " + trainLines.size());
        System.out.println("Размер Valid (2%):  " + validLines.size());

        // 5. Записываем в файлы
        System.out.println("Запись train файла...");
        Files.write(Paths.get(trainOut), trainLines, StandardCharsets.UTF_8);

        System.out.println("Запись valid файла...");
        Files.write(Paths.get(validOut), validLines, StandardCharsets.UTF_8);
    }
}