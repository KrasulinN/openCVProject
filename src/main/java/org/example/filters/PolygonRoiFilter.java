package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.util.List;

/**
 * Фильтр, который обнуляет все пиксели вне заданного многоугольника.
 * Пиксели внутри многоугольника остаются неизменными.
 */
public class PolygonRoiFilter implements FilterStrategy {

    private final List<Point> polygonPoints;
    private boolean debugMode = false;

    /**
     * @param polygonPoints список вершин многоугольника в порядке обхода
     */
    public PolygonRoiFilter(List<Point> polygonPoints) {
        if (polygonPoints == null || polygonPoints.size() < 3) {
            throw new IllegalArgumentException("Многоугольник должен иметь минимум 3 вершины");
        }
        this.polygonPoints = polygonPoints;
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
            System.out.println("[PolygonRoiFilter] Входное изображение: " + source.cols() + "x" + source.rows() +
                    ", каналы: " + source.channels() + ", тип: " + source.type());
            System.out.println("[PolygonRoiFilter] Вершин многоугольника: " + polygonPoints.size());
        }

        // Создаём маску того же размера, что и исходное изображение
        Mat mask = Mat.zeros(source.size(), source.type());

        // Преобразуем список точек в MatOfPoint для OpenCV
        MatOfPoint contour = new MatOfPoint();
        contour.fromArray(polygonPoints.toArray(new Point[0]));

        // Рисуем заполненный многоугольник на маске (белый цвет = 255)
        Imgproc.drawContours(mask, java.util.Collections.singletonList(contour), -1,
                new org.opencv.core.Scalar(255), Core.FILLED);

        if (debugMode) {
            System.out.println("[PolygonRoiFilter] Маска создана, размер: " + mask.cols() + "x" + mask.rows());
        }

        // Применяем маску к исходному изображению
        Mat result = new Mat();
        Core.bitwise_and(source, mask, result);

        if (debugMode) {
            System.out.println("[PolygonRoiFilter] Маска применена, результат: " + result.cols() + "x" + result.rows());
        }

        contour.release();
        mask.release();

        return result;
    }

    @Override
    public String getName() {
        return "ROI-маска (" + polygonPoints.size() + " вершин)";
    }

    public List<Point> getPolygonPoints() {
        return polygonPoints;
    }
}