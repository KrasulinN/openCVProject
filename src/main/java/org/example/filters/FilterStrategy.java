package org.example.filters;

import org.opencv.core.Mat;

public interface FilterStrategy {
    Mat apply(Mat source);
    String getName();
}
