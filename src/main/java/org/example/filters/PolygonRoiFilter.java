package org.example.filters;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.List;

public class PolygonRoiFilter implements FilterStrategy {

    public enum RoiMode {
        KEEP_INSIDE,
        KEEP_OUTSIDE
    }

    private final List<Point> polygonPoints;
    private final RoiMode mode;

    private boolean debugMode = false;

    public PolygonRoiFilter(List<Point> polygonPoints, RoiMode mode) {
        if (polygonPoints == null || polygonPoints.size() < 3) {
            throw new IllegalArgumentException("Многоугольник должен иметь минимум 3 вершины");
        }

        this.polygonPoints = polygonPoints;
        this.mode = mode;
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
            System.out.println("[PolygonRoiFilter] mode = " + mode);
        }

        Mat mask = Mat.zeros(source.size(), source.type());

        MatOfPoint contour = new MatOfPoint();
        contour.fromArray(polygonPoints.toArray(new Point[0]));

        Imgproc.drawContours(
                mask,
                Collections.singletonList(contour),
                -1,
                new Scalar(255),
                Core.FILLED
        );

        if (mode == RoiMode.KEEP_OUTSIDE) {
            Core.bitwise_not(mask, mask);
        }

        Mat result = new Mat();
        Core.bitwise_and(source, mask, result);

        contour.release();
        mask.release();

        return result;
    }

    @Override
    public String getName() {
        return "ROI-маска (" + mode + ")";
    }

    public List<Point> getPolygonPoints() {
        return polygonPoints;
    }
}