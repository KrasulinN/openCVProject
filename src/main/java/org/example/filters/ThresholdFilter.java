package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Фильтр, который применяет пороговую обработку по интенсивности пикселей.
 * Пиксели со значениями в диапазоне [minThreshold, maxThreshold] остаются с исходной яркостью,
 * остальные становятся чёрными (0).
 * Также применяется морфология (закрытие + открытие) к маске для очистки шума.
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
        Mat mask = Mat.zeros(gray.size(), gray.type());

        // Применяем пороговую фильтрацию: пиксели в диапазоне [min, max] -> 255, остальные -> 0
        Mat lowerMask = new Mat();
        Mat upperMask = new Mat();

        Imgproc.threshold(gray, lowerMask, minThreshold, 255, Imgproc.THRESH_BINARY);
        Imgproc.threshold(gray, upperMask, maxThreshold, 255, Imgproc.THRESH_BINARY_INV);

        Mat result = new Mat();
        Core.bitwise_and(lowerMask, upperMask, mask);
        Core.bitwise_and(source, mask, result);

        if (debugMode) {
            // Статистика по маске
            Scalar meanVal = Core.mean(mask);
            double whitePixels = meanVal.val[0];
            double totalPixels = mask.total();
            double percent = (whitePixels / 255.0) * 100.0;

            System.out.println("[ThresholdFilter] Порог: [" + minThreshold + ", " + maxThreshold + "]");
            System.out.println("[ThresholdFilter] Белых пикселей: " + (long)(whitePixels / 255.0 * totalPixels) +
                    " из " + (long)totalPixels + " (" + String.format("%.2f", percent) + "%)");
            System.out.println("[ThresholdFilter] Среднее значение маски: " + meanVal.val[0]);
        }

        /*
        // Применяем морфологию к маске: CLOSE затем OPEN
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));

        Mat closeMask = new Mat();
        Imgproc.morphologyEx(mask, closeMask, Imgproc.MORPH_CLOSE, kernel);

        Mat openMask = new Mat();
        Imgproc.morphologyEx(closeMask, openMask, Imgproc.MORPH_OPEN, kernel);

        if (debugMode) {
            System.out.println("[ThresholdFilter] Морфология применена к маске (CLOSE + OPEN, ядро 3x3)");
        }

        // Накладываем итоговую маску на исходное изображение
        Mat result = new Mat();
        Core.bitwise_and(source, source, result, openMask);

         */

        // Освобождаем память
        lowerMask.release();
        upperMask.release();
        gray.release();
        mask.release();
        //kernel.release();
        //closeMask.release();
        //openMask.release();

        return result;
    }

    @Override
    public String getName() {
        return "Порог: [" + minThreshold + ", " + maxThreshold + "] + Морфология";
    }

    public int getMinThreshold() {
        return minThreshold;
    }

    public int getMaxThreshold() {
        return maxThreshold;
    }
}