package org.example.model;

import org.opencv.core.Mat;

import java.nio.file.Path;

public class SeriesImageItem {
    private final Path path;
    private final String fileName;
    private final String groupKey;
    private final double slicePositionMm;
    private Mat image;
    private Mat originalImage;

    public SeriesImageItem(Path path, String groupKey, Mat image) {
        this(path, groupKey, Double.NaN, image);
    }

    public SeriesImageItem(Path path, String groupKey, int sliceOrder, Mat image) {
        this(path, groupKey, (double) sliceOrder, image);
    }

    public SeriesImageItem(Path path, String groupKey, double slicePositionMm, Mat image) {
        this.path = path;
        this.fileName = path != null && path.getFileName() != null
                ? path.getFileName().toString()
                : String.valueOf(path);
        this.groupKey = groupKey;
        this.slicePositionMm = slicePositionMm;
        setImage(image);
    }

    public Path getPath() {
        return path;
    }

    public String getFileName() {
        return fileName;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public double getSlicePositionMm() {
        return slicePositionMm;
    }

    @Deprecated
    public int getSliceOrder() {
        if (!Double.isFinite(slicePositionMm)) {
            return Integer.MAX_VALUE;
        }
        if (slicePositionMm >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (slicePositionMm <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.round(slicePositionMm);
    }

    public Mat getImage() {
        return image;
    }

    public void setImage(Mat image) {
        this.image = image == null ? null : image.clone();
        if (this.originalImage == null) {
            this.originalImage = image == null ? null : image.clone();
        }
    }

    public boolean hasOriginalImage() {
        return originalImage != null && !originalImage.empty();
    }

    public void resetToOriginal() {
        if (originalImage != null && !originalImage.empty()) {
            this.image = originalImage.clone();
        }
    }
}
