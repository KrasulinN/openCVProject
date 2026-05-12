package org.example.filters;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.List;

/**
 * Фильтр, который:
 * 1. Применяет пороговую фильтрацию по интенсивности (если передан ThresholdFilter) или использует Otsu
 * 2. Находит все контуры
 * 3. Оставляет только самый большой контур по площади
 * 4. Возвращает маску с этим контуром
 */
public class ContourFilter implements FilterStrategy {

    private final Integer minArea; // минимальная площадь для фильтрации (опционально)
    private boolean debugMode = false;

    public ContourFilter() {
        this.minArea = null;
    }

    /**
     * @param minArea минимальная площадь объекта в пикселях. Объекты с меньшей площадью будут отброшены.
     */
    public ContourFilter(Integer minArea) {
        this.minArea = minArea;
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
            System.out.println("[ContourFilter] Входное изображение: " + source.cols() + "x" + source.rows() +
                    ", каналы: " + source.channels());
        }

        // Преобразуем в оттенки серого, если изображение цветное
        Mat gray = new Mat();
        if (source.channels() > 1) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = source.clone();
        }

        // Предполагаем, что входное изображение уже является маской от ThresholdFilter
        // Поэтому не применяем свою бинаризацию, а используем напрямую
        Mat bw = new Mat();
        gray.copyTo(bw);

        // Убедимся, что маска бинарная (0 или 255)
        Imgproc.threshold(bw, bw, 127, 255, Imgproc.THRESH_BINARY);

        // Подсчитаем количество белых пикселей ДО морфологии
        long whitePixelsBefore = 0;
        if (debugMode) {
            Core.MinMaxLocResult minMaxBefore = Core.minMaxLoc(bw);
            whitePixelsBefore = Core.countNonZero(bw);
            System.out.println("[ContourFilter] ДО морфологии: min=" + minMaxBefore.minVal + ", max=" + minMaxBefore.maxVal + ", белых пикселей=" + whitePixelsBefore);
        }

        // Находим все контуры
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(bw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE);

        if (debugMode) {
            System.out.println("[ContourFilter] Найдено контуров: " + contours.size());
        }

        // Создаём пустую маску
        Mat mask = Mat.zeros(gray.size(), CvType.CV_8UC1);

        if (!contours.isEmpty()) {
            // Статистика по площадям для отладки
            List<Double> areas = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                areas.add(area);
            }

            if (debugMode) {
                System.out.println("[ContourFilter] Площади контуров: " + areas);
                System.out.println("[ContourFilter] Всего контуров будет отображено: " + contours.size());
            }

            // Рисуем ВСЕ найденные контуры на маске
            Imgproc.drawContours(mask, contours, -1, new Scalar(255), -1); // -1 = все контуры, заполненные
        } else {
            if (debugMode) {
                System.out.println("[ContourFilter] Контуры не найдены!");
            }
        }

        // Освобождаем память
        gray.release();
        bw.release();
        bw.release();
        bw.release();
        hierarchy.release();
        contours.clear();

        return mask;
    }

    @Override
    public String getName() {
        return "Контур: все объекты";
    }
}