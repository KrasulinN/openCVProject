package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class ThresholdFilter implements FilterStrategy {

    private final double threshold;
    private final double maxValue;
    private final ThresholdType type;
    private final double opacity;

    public ThresholdFilter(double threshold, double maxValue, ThresholdType type, double opacity) {
        this.threshold = threshold;
        this.maxValue = maxValue;
        this.type = type == null ? ThresholdType.BINARY : type;
        this.opacity = Math.max(0.0, Math.min(1.0, opacity));
    }

    @Override
    public Mat apply(Mat source) {
        if (source == null || source.empty()) {
            return source;
        }

        Mat gray = new Mat();
        if (source.channels() > 1) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = source.clone();
        }

        Mat mask = new Mat();
        Imgproc.threshold(gray, mask, threshold, maxValue, type.getOpenCvType());

        Mat maskForOverlay = makeMaskMatchSource(mask, source);
        Mat result = new Mat();
        Core.addWeighted(source, 1.0 - opacity, maskForOverlay, opacity, 0.0, result);
        return result;
    }

    private Mat makeMaskMatchSource(Mat mask, Mat source) {
        if (source.channels() == 1) {
            return mask;
        }

        Mat coloredMask = new Mat();
        if (source.channels() == 4) {
            Imgproc.cvtColor(mask, coloredMask, Imgproc.COLOR_GRAY2BGRA);
        } else {
            Imgproc.cvtColor(mask, coloredMask, Imgproc.COLOR_GRAY2BGR);
        }
        return coloredMask;
    }

    @Override
    public String getName() {
        return "Пороговая фильтрация";
    }

    public enum ThresholdType {
        BINARY("BINARY", Imgproc.THRESH_BINARY),
        BINARY_INV("BINARY_INV", Imgproc.THRESH_BINARY_INV),
        TRUNC("TRUNC", Imgproc.THRESH_TRUNC),
        TOZERO("TOZERO", Imgproc.THRESH_TOZERO),
        TOZERO_INV("TOZERO_INV", Imgproc.THRESH_TOZERO_INV);

        private final String displayName;
        private final int openCvType;

        ThresholdType(String displayName, int openCvType) {
            this.displayName = displayName;
            this.openCvType = openCvType;
        }

        public int getOpenCvType() {
            return openCvType;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
