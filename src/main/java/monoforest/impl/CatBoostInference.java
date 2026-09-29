package monoforest.impl;

import ai.catboost.CatBoostModel;
import ai.catboost.CatBoostPredictions;
import java.io.File;

public class CatBoostInference implements AutoCloseable {

    private final CatBoostModel model;

    public CatBoostInference(String modelPath) throws Exception {
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            throw new Exception("Model file not found: " + modelPath);
        }

        // Загрузка модели
        this.model = CatBoostModel.loadModel(modelPath);
        System.out.println("CatBoost native model loaded from: " + modelPath);
    }

    /**
     * Возвращает вероятность класса "1" (релевантно).
     */
    public float predictProbability(float[] features) throws Exception {
        // ИСПРАВЛЕНИЕ: Явное приведение null к (String[]), чтобы убрать неоднозначность
        CatBoostPredictions prediction = model.predict(features, (String[]) null);

        // Получаем "сырое" значение (RawFormulaVal)
        double rawValue = prediction.get(0, 0);

        // Превращаем логит в вероятность (Sigmoid)
        return (float) sigmoid(rawValue);
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    @Override
    public void close() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}