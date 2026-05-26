package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Фильтр, который применяет пороговую обработку по интенсивности пикселей.
 * Пиксели со значениями в диапазоне [minThreshold, maxThreshold] остаются с исходной яркостью,
 * остальные становятся чёрными (0).
 * Также может применяться морфология к маске для ее постобработки.
 */
public class ThresholdFilter implements FilterStrategy {
    private static final double LOW_PERCENTILE = 0.01;
    private static final double HIGH_PERCENTILE = 0.99;

    private final int minThreshold;
    private final int maxThreshold;
    private final boolean removeNoise;
    private final boolean fillGaps;
    private final boolean normalizeBrightness;
    private boolean debugMode = false;

    /**
     * @param minThreshold минимальное значение диапазона (0-255)
     * @param maxThreshold максимальное значение диапазона (0-255)
     */
    public ThresholdFilter(int minThreshold, int maxThreshold) {
        this(minThreshold, maxThreshold, false, false);
    }

    public ThresholdFilter(int minThreshold, int maxThreshold, boolean removeNoise, boolean fillGaps) {
        this(minThreshold, maxThreshold, removeNoise, fillGaps, true);
    }

    public ThresholdFilter(int minThreshold, int maxThreshold, boolean removeNoise, boolean fillGaps,
                           boolean normalizeBrightness) {
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
        this.removeNoise = removeNoise;
        this.fillGaps = fillGaps;
        this.normalizeBrightness = normalizeBrightness;
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
        Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
        // Создаём бинарную маску
        Mat normalizedGray = normalizeBrightness ? normalizeBrightness(gray) : gray.clone();

        if (debugMode) {
            Core.MinMaxLocResult before = Core.minMaxLoc(gray);
            Core.MinMaxLocResult after = Core.minMaxLoc(normalizedGray);
            System.out.println("[ThresholdFilter] Brightness normalization "
                    + (normalizeBrightness ? "enabled" : "disabled") + ": min/max "
                    + before.minVal + "/" + before.maxVal + " -> "
                    + after.minVal + "/" + after.maxVal);
        }

        Mat mask = Mat.zeros(normalizedGray.size(), normalizedGray.type());

        // Применяем пороговую фильтрацию: пиксели в диапазоне [min, max] -> 255, остальные -> 0
        Mat lowerMask = new Mat();
        Mat upperMask = new Mat();
        Imgproc.threshold(normalizedGray, lowerMask, minThreshold, 255, Imgproc.THRESH_BINARY);
        Imgproc.threshold(normalizedGray, upperMask, maxThreshold, 255, Imgproc.THRESH_BINARY_INV);

        Core.bitwise_and(lowerMask, upperMask, mask);

        if (fillGaps || removeNoise) {

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Mat kernel_Noise = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            if (removeNoise) {

                Mat cleanMask = new Mat();
                Imgproc.morphologyEx(mask, cleanMask, Imgproc.MORPH_OPEN, kernel_Noise);

                mask.release();
                mask = cleanMask;

                if (debugMode) {
                    System.out.println("[ThresholdFilter] Морфология применена к маске: OPEN, ядро 3x3");
                }
            }

            if (fillGaps) {
                Mat closeMask = new Mat();
                Imgproc.morphologyEx(mask, closeMask, Imgproc.MORPH_CLOSE, kernel);
                mask.release();
                mask = closeMask;

                if (debugMode) {
                    System.out.println("[ThresholdFilter] Морфология применена к маске: CLOSE, ядро 3x3");
                }
            }


            kernel.release();
        }

        Mat result = new Mat();
        source.copyTo(result, mask);

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

        // Освобождаем память
        lowerMask.release();
        upperMask.release();
        normalizedGray.release();
        gray.release();
        mask.release();

        return result;
    }

    private Mat normalizeBrightness(Mat gray) {
        if (gray == null || gray.empty()) {
            return gray;
        }

        Mat source8Bit;
        if (gray.type() == CvType.CV_8UC1) {
            source8Bit = gray;
        } else {
            source8Bit = new Mat();
            Core.normalize(gray, source8Bit, 0, 255, Core.NORM_MINMAX, CvType.CV_8UC1);
        }

        int pixelCount = (int) source8Bit.total();
        byte[] input = new byte[pixelCount];
        source8Bit.get(0, 0, input);

        int[] histogram = new int[256];
        for (byte value : input) {
            histogram[value & 0xFF]++;
        }

        int low = percentileValue(histogram, pixelCount, LOW_PERCENTILE);
        int high = percentileValue(histogram, pixelCount, HIGH_PERCENTILE);
        if (high <= low) {
            return source8Bit == gray ? gray.clone() : source8Bit;
        }

        double scale = 255.0 / (high - low);
        byte[] output = new byte[pixelCount];
        for (int i = 0; i < input.length; i++) {
            int value = input[i] & 0xFF;
            output[i] = (byte) clampToByte((value - low) * scale);
        }

        Mat normalized = new Mat(source8Bit.rows(), source8Bit.cols(), CvType.CV_8UC1);
        normalized.put(0, 0, output);

        if (source8Bit != gray) {
            source8Bit.release();
        }
        return normalized;
    }

    private int percentileValue(int[] histogram, int pixelCount, double percentile) {
        int target = Math.max(0, Math.min(pixelCount - 1, (int) Math.round((pixelCount - 1) * percentile)));
        int cumulative = 0;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative > target) {
                return value;
            }
        }
        return histogram.length - 1;
    }

    private int clampToByte(double value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return (int) Math.round(value);
    }

    @Override
    public String getName() {
        if (!removeNoise && !fillGaps) {
            return "Порог: [" + minThreshold + ", " + maxThreshold + "]";
        }

        if (removeNoise && fillGaps) {
            return "Порог: [" + minThreshold + ", " + maxThreshold + "] + Закрытие + Открытие";
        }

        String morphologyName = fillGaps ? "Закрытие" : "Открытие";
        return "Порог: [" + minThreshold + ", " + maxThreshold + "] + " + morphologyName;
    }

    public int getMinThreshold() {
        return minThreshold;
    }

    public int getMaxThreshold() {
        return maxThreshold;
    }

    public boolean isRemoveNoiseEnabled() {
        return removeNoise;
    }

    public boolean isFillGapsEnabled() {
        return fillGaps;
    }
}
