package org.example.filters;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Subdiv2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Filter that keeps only the external contour of the largest object in the image,
// builds a roughly uniform triangulation inside it and fills triangles with
// colors computed from saturation values (HSV) of triangle vertices.
public class ContourFilter implements FilterStrategy {

    @Override
    public Mat apply(Mat source) {
        // Produce binary mask containing only external contours (white on black).
        if (source == null || source.empty()) return source;

        Mat gray = new Mat();
        if (source.channels() > 1) {
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = source.clone();
        }

        Imgproc.medianBlur(gray, gray, 5);
        int se = Math.max(3, Math.min(31, Math.max(gray.cols(), gray.rows()) / 200));
        if (se % 2 == 0) se++;
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(se, se));

        Mat closed = new Mat();
        Imgproc.morphologyEx(gray, closed, Imgproc.MORPH_CLOSE, kernel);

        Mat grad = new Mat();
        Imgproc.morphologyEx(closed, grad, Imgproc.MORPH_GRADIENT, kernel);

        Mat bw = new Mat();
        Imgproc.threshold(grad, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

        Imgproc.dilate(bw, bw, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(bw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE);

        Mat mask = Mat.zeros(gray.size(), CvType.CV_8UC1);

        if (!contours.isEmpty()) {
            Imgproc.drawContours(mask, contours, -1, new Scalar(255), 1);
        }

        return mask;
    }

    @Override
    public String getName() {
        return "Контур: внешний (маска)";
    }
}