package monoforest.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileSplitter {

    public static void main(String[] args) {
        FileSplitter s = new FileSplitter();
        s.splitByDocId("/Volumes/Ex_Volume/msmarcoProcces/dd.json", "/Volumes/Ex_Volume/msmarcoProcces/bms");
    }

    /**
     * Разбивает файл с результатами на 60 частей на основе ID документа.
     * * @param inputFilePath  Путь к исходному большому файлу (например, "results.jsonl")
     * @param outputDirPath  Папка, куда будут сохранены 60 файлов
     */
    public void splitByDocId(String inputFilePath, String outputDirPath) {
        Path inputPath = Paths.get(inputFilePath);
        Path outDir = Paths.get(outputDirPath);

        // Массив для хранения 60 открытых потоков записи
        BufferedWriter[] writers = new BufferedWriter[60];

        try {
            // Создаем папку для вывода, если её нет
            if (!Files.exists(outDir)) {
                Files.createDirectories(outDir);
            }

            // 1. Открываем 60 файлов на запись заранее
            for (int i = 0; i < 60; i++) {
                // Формируем имена вида: split_00.jsonl, split_01.jsonl ... split_59.jsonl
                String fileName = String.format("split_%02d.jsonl", i);
                Path outFilePath = outDir.resolve(fileName);
                writers[i] = Files.newBufferedWriter(outFilePath, StandardCharsets.UTF_8);
            }

            System.out.println("Начинаем разбиение файла: " + inputFilePath);
            long lineCount = 0;
            long errorCount = 0;

            // 2. Читаем исходный файл построчно
            try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
                String line;
                String targetPrefix = "msmarco_doc_";
                int prefixLength = targetPrefix.length();

                while ((line = reader.readLine()) != null) {
                    lineCount++;

                    // Ищем индекс начала "msmarco_doc_"
                    int prefixIdx = line.indexOf(targetPrefix);

                    if (prefixIdx != -1) {
                        try {
                            // Вырезаем ровно 2 символа, идущие сразу после "msmarco_doc_"
                            int numStart = prefixIdx + prefixLength;
                            String chunkStr = line.substring(numStart, numStart + 2);

                            // Превращаем "00", "01" ... "59" в число (индекс массива)
                            int chunkIndex = Integer.parseInt(chunkStr);

                            // Проверяем, что число действительно в диапазоне от 0 до 59
                            if (chunkIndex >= 0 && chunkIndex < 60) {
                                writers[chunkIndex].write(line);
                                writers[chunkIndex].write("\n");
                            } else {
                                errorCount++;
                            }
                        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                            // Если после msmarco_doc_ оказался не номер (или строка обрывается)
                            errorCount++;
                        }
                    } else {
                        // Если в строке вообще нет "msmarco_doc_"

                        errorCount++;
                        System.out.println(line);
                    }

                    // Для понимания прогресса (печатаем лог каждые 500 000 строк)
                    if (lineCount % 500_000 == 0) {
                        System.out.println("Обработано строк: " + lineCount);
                    }
                }
            }

            System.out.println("Готово! Всего строк обработано: " + lineCount);
            if (errorCount > 0) {
                System.out.println("Внимание: пропущено строк с неизвестным форматом ID: " + errorCount);
            }

        } catch (IOException e) {
            System.err.println("Критическая ошибка ввода-вывода!");
            e.printStackTrace();
        } finally {
            // 3. Обязательно закрываем ВСЕ 60 файлов, даже если произошла ошибка
            for (int i = 0; i < 60; i++) {
                if (writers[i] != null) {
                    try {
                        writers[i].close();
                    } catch (IOException e) {
                        System.err.println("Не удалось закрыть writer для индекса " + i);
                    }
                }
            }
        }
    }
}