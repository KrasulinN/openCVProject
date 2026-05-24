package org.example.model;

import org.opencv.core.Mat;

import java.nio.file.Path;

public class SeriesImageItem {
    private final Path path;
    private final String fileName;
    private final String groupKey;
    private final double slicePositionMm;
    private final double[] pixelSpacing; // [row_spacing, col_spacing] в мм
    private Mat image;
    private Mat originalImage;

    public SeriesImageItem(Path path, String groupKey, Mat image) {
        this(path, groupKey, Double.NaN, null, image);
    }

    public SeriesImageItem(Path path, String groupKey, int sliceOrder, Mat image) {
        this(path, groupKey, (double) sliceOrder, null, image);
    }

    public SeriesImageItem(Path path, String groupKey, double slicePositionMm, Mat image) {
        this(path, groupKey, slicePositionMm, null, image);
    }

    public SeriesImageItem(Path path, String groupKey, double slicePositionMm, double[] pixelSpacing, Mat image) {
        this.path = path;
        this.fileName = path != null && path.getFileName() != null
                ? path.getFileName().toString()
                : String.valueOf(path);
        this.groupKey = groupKey;
        this.slicePositionMm = slicePositionMm;
        this.pixelSpacing = pixelSpacing;
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

    /**
     * Возвращает PixelSpacing из DICOM метаданных
     * @return массив [row_spacing, col_spacing] в мм, или null если недоступно
     */
    public double[] getPixelSpacing() {
        return pixelSpacing;
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

    public Mat getOriginalImage() {
        return originalImage;
    }

    public void resetToOriginal() {
        if (originalImage != null && !originalImage.empty()) {
            this.image = originalImage.clone();
        }
    }
}