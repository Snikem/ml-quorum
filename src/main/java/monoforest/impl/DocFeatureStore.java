package monoforest.impl;

import java.io.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class DocFeatureStore {

    // Хранилище: DocID -> Vector
    private Map<String, float[]> store;
    private int vectorSize;

    public DocFeatureStore(String binaryFilePath) throws IOException {
        long start = System.currentTimeMillis();
        store = new HashMap<>();

        File file = new File(binaryFilePath);
        if (!file.exists()) {
            throw new FileNotFoundException("Feature file not found: " + binaryFilePath);
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            // Читаем заголовок
            int numDocs = dis.readInt();
            vectorSize = dis.readInt();

            // Если заголовок 0 (прерванная запись), читаем до EOF
            boolean ignoreCount = (numDocs == 0);

            try {
                int count = 0;
                while (ignoreCount || count < numDocs) {
                    String docId = dis.readUTF();
                    float[] feats = new float[vectorSize];
                    for (int j = 0; j < vectorSize; j++) {
                        feats[j] = dis.readFloat();
                    }

                    store.put(docId, feats);
                    count++;
                }
            } catch (EOFException e) {
                // Конец файла достигнут
            }
        }

        long end = System.currentTimeMillis();
        // Используем System.out, так как логгер может быть недоступен в этом классе
        System.out.println("Loaded features for " + store.size() + " docs. Vector size: " + vectorSize);
    }

    public float[] getFeatures(String docId) {
        return store.getOrDefault(docId, new float[0]);
    }

    public Map<String, float[]> getAll() {
        return store;
    }

    public int getVectorSize() {
        return vectorSize;
    }
}