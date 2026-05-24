package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * Фильтр, который применяет морфологические операции (закрытие и открытие) к изображению.
 */
public class MorphologyFilter implements FilterStrategy {

    private final int kernelSize;
    private final int morphologyType;
    private boolean debugMode = false;

    /**
     * @param kernelSize размер ядра (3, 5, 7 и т.д.)
     * @param morphologyType тип операции: Imgproc.MORPH_CLOSE или Imgproc.MORPH_OPEN
     */
    public MorphologyFilter(int kernelSize, int morphologyType) {
        if (kernelSize < 1 || kernelSize % 2 == 0) {
            throw new IllegalArgumentException("Размер ядра должен быть нечётным положительным числом");
        }
        this.kernelSize = kernelSize;
        this.morphologyType = morphologyType;
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
            System.out.println("[MorphologyFilter] Входное изображение: " + source.cols() + "x" + source.rows() +
                    ", каналы: " + source.channels() + ", тип: " + source.type());
            System.out.println("[MorphologyFilter] Операция: " + (morphologyType == Imgproc.MORPH_CLOSE ? "CLOSE" : "OPEN") +
                    ", ядро: " + kernelSize + "x" + kernelSize);
        }

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(kernelSize, kernelSize));
        Mat result = new Mat();
        Imgproc.morphologyEx(source, result, morphologyType, kernel);

        if (debugMode) {
            System.out.println("[MorphologyFilter] Результат: " + result.cols() + "x" + result.rows());
        }

        kernel.release();

        return result;
    }

    @Override
    public String getName() {
        String opName = morphologyType == Imgproc.MORPH_CLOSE ? "Закрытие" : "Открытие";
        return opName + " (ядро " + kernelSize + "x" + kernelSize + ")";
    }

    public int getKernelSize() {
        return kernelSize;
    }

    public int getMorphologyType() {
        return morphologyType;
    }
}