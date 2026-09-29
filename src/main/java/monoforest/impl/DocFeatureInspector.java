package monoforest.impl;

import java.io.*;
import java.util.Arrays;

public class DocFeatureInspector {

    // Путь к вашему бинарному файлу
    private static final String BINARY_FILE = "/Volumes/Ex_Volume/msmarcoProcces/doc_features.bin";

    public static void main(String[] args) {
        System.out.println("=== Inspecting Binary Feature File ===");
        System.out.println("File: " + BINARY_FILE);

        File f = new File(BINARY_FILE);
        if (!f.exists()) {
            System.err.println("File does not exist!");
            return;
        }
        System.out.printf("File size: %.2f MB%n", f.length() / (1024.0 * 1024.0));

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {

            // 1. Читаем заголовок
            int headerNumDocs = dis.readInt();
            int vectorSize = dis.readInt();

            System.out.println("\n--- HEADER INFO ---");
            System.out.println("Docs count (in header): " + headerNumDocs);
            System.out.println("Vector size: " + vectorSize);

            if (headerNumDocs == 0) {
                System.out.println("WARN: Header count is 0. This is expected if the process was interrupted.");
                System.out.println("Reading stream until EOF to count actual records...");
            }

            // 2. Читаем тело файла до упора
            int actualCount = 0;

            try {
                while (true) {
                    // Пытаемся прочитать ID. Если файл кончился тут - сработает EOFException
                    String docId = dis.readUTF();

                    // Читаем вектор
                    float[] feats = new float[vectorSize];
                    for (int i = 0; i < vectorSize; i++) {
                        feats[i] = dis.readFloat();
                    }

                    actualCount++;

                    // Выводим первые 5 записей для проверки
                    if (actualCount <= 5) {
                        printRecord(actualCount, docId, feats);
                    }
                    // Далее выводим каждую 10-тысячную, чтобы видеть прогресс
                    else if (actualCount % 10000 == 0) {
                        System.out.println("... read " + actualCount + " records (Last ID: " + docId + ")");
                    }
                }
            } catch (EOFException e) {
                // Это НОРМАЛЬНЫЙ выход из цикла, когда файл кончился
                System.out.println("\n--- END OF FILE REACHED ---");
            } catch (IOException e) {
                System.err.println("\nError reading record #" + (actualCount + 1));
                System.err.println("This probably means the file was cut off in the middle of a record.");
                // e.printStackTrace();
            }

            System.out.println("\n=== SUMMARY ===");
            System.out.println("Total complete records found: " + actualCount);

            // Расчет: (ID_len + 2 bytes len) + (vec_size * 4 bytes)
            System.out.println("Data looks valid.");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void printRecord(int num, String docId, float[] feats) {
        System.out.println("Record #" + num);
        System.out.println("  DocID: " + docId);
        System.out.println("  Feats: " + Arrays.toString(feats));
        System.out.println("------------------------------------------------");
    }
}