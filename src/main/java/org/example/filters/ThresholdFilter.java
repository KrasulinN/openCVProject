package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/**
 * Фильтр, который применяет пороговую обработку по интенсивности пикселей.
 * Пиксели со значениями в диапазоне [minThreshold, maxThreshold] становятся белыми (255),
 * остальные - чёрными (0).
 */
public class ThresholdFilter implements FilterStrategy {

    private final int minThreshold;
    private final int maxThreshold;
    private boolean debugMode = false;

    /**
     * @param minThreshold минимальное значение диапазона (0-255)
     * @param maxThreshold максимальное значение диапазона (0-255)
     */
    public ThresholdFilter(int minThreshold, int maxThreshold) {
        if (minThreshold < 0 || minThreshold > 255) {
            throw new IllegalArgumentException("minThreshold должен быть в диапазоне 0-255");
        }
        if (maxThreshold < 0 || maxThreshold > 255) {
            throw new IllegalArgumentException("maxThreshold должен быть в диапазоне 0-255");
        }
        if (minThreshold > maxThreshold) {
            throw new IllegalArgumentException("minThreshold не может быть больше maxThreshold");
        }
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
    }

    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    @Override
    public Mat apply(Mat source) {
        if (source == null || source.empty()) {
            return source;
        }

        if (debugMode) {
            System.out.println("[ThresholdFilter] Входное изображение: " + source.cols() + "x" + source.rows() +
                    ", каналы: " + source.channels() + ", тип: " + source.type());
        }

        // Преобразуем в оттенки серого, если изображение цветное
        Mat gray = new Mat();
        if (source.channels() > 1) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = source.clone();
        }

        // Создаём бинарную маску
        Mat result = Mat.zeros(gray.size(), gray.type());

        // Применяем пороговую фильтрацию: пиксели в диапазоне [min, max] -> 255, остальные -> 0
        // Используем комбинацию двух порогов
        Mat lowerMask = new Mat();

        Mat upperMask = new Mat();

        Imgproc.threshold(gray, lowerMask, minThreshold, 255, Imgproc.THRESH_BINARY);
       // Imgproc.thr
        Imgproc.threshold(gray, upperMask, 0, maxThreshold, Imgproc.THRESH_BINARY_INV);

        Core.bitwise_and(lowerMask, upperMask, result);

        if (debugMode) {
            // Статистика по маске
            Scalar meanVal = Core.mean(result);
            double whitePixels = meanVal.val[0];
            double totalPixels = result.total();
            double percent = (whitePixels / 255.0) * 100.0;

            System.out.println("[ThresholdFilter] Порог: [" + minThreshold + ", " + maxThreshold + "]");
            System.out.println("[ThresholdFilter] Белых пикселей: " + (long)(whitePixels / 255.0 * totalPixels) +
                    " из " + (long)totalPixels + " (" + String.format("%.2f", percent) + "%)");
            System.out.println("[ThresholdFilter] Среднее значение маски: " + meanVal.val[0]);
        }

        lowerMask.release();
        upperMask.release();
        gray.release();

        return result;
    }

    @Override
    public String getName() {
        return "Порог: [" + minThreshold + ", " + maxThreshold + "]";
    }

    public int getMinThreshold() {
        return minThreshold;
    }

    public int getMaxThreshold() {
        return maxThreshold;
    }
}