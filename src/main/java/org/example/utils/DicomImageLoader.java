package org.example.utils;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class DicomImageLoader {
    private static final String EXPLICIT_VR_LITTLE_ENDIAN = "1.2.840.10008.1.2.1";
    private static final String IMPLICIT_VR_LITTLE_ENDIAN = "1.2.840.10008.1.2";
    private static final String EXPLICIT_VR_BIG_ENDIAN = "1.2.840.10008.1.2.2";

    private DicomImageLoader() {
    }

    public static boolean isDicomFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            return bytes.length >= 132 && hasDicomPrefix(bytes);
        } catch (IOException e) {
            return false;
        }
    }

    public static DicomSeriesInfo readSeriesInfo(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 132 || !hasDicomPrefix(bytes)) {
                return null;
            }

            DicomMetadata metadata = readMetadata(bytes);
            if (metadata.seriesInstanceUid == null || metadata.seriesInstanceUid.trim().isEmpty()) {
                return null;
            }

            String modality = metadata.modality == null || metadata.modality.trim().isEmpty()
                    ? "DICOM"
                    : metadata.modality.trim().toUpperCase(Locale.ROOT);
            String groupKey = modality + " | SERIES:" + metadata.seriesInstanceUid.trim();
            double sortPositionMm = computeSliceSortPosition(metadata);
            double nominalSpacingMm = resolveNominalSpacingMm(metadata);
            return new DicomSeriesInfo(
                    groupKey,
                    metadata.instanceNumber,
                    sortPositionMm,
                    nominalSpacingMm,
                    metadata.imagePositionPatient,
                    metadata.imageOrientationPatient,
                    metadata.pixelSpacing,
                    metadata.sliceThickness,
                    metadata.spacingBetweenSlices
            );
        } catch (IOException e) {
            return null;
        }
    }

    public static Mat load(Path path) throws IOException {
        return load(path, null);
    }

    public static Mat load(Path path, IntensityWindow intensityWindow) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 132 || !hasDicomPrefix(bytes)) {
            throw new IOException("File is not a DICOM image");
        }

        DicomMetadata metadata = readMetadata(bytes);
        validateMetadata(metadata, bytes.length);

        byte[] normalizedPixels = metadata.bitsAllocated == 8
                ? normalize8Bit(bytes, metadata, intensityWindow)
                : normalize16Bit(bytes, metadata, intensityWindow);

        Mat image = new Mat(metadata.rows, metadata.columns, CvType.CV_8UC1);
        image.put(0, 0, normalizedPixels);
        return image;
    }

    public static IntensityWindow computeSeriesPercentileWindow(List<Path> paths,
                                                                double lowPercentile,
                                                                double highPercentile) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        long pixelCount = 0L;

        for (Path path : paths) {
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length < 132 || !hasDicomPrefix(bytes)) {
                    continue;
                }

                DicomMetadata metadata = readMetadata(bytes);
                validateMetadata(metadata, bytes.length);
                IntensityRange range = scanIntensityRange(bytes, metadata);
                if (range.pixelCount <= 0) {
                    continue;
                }

                min = Math.min(min, range.min);
                max = Math.max(max, range.max);
                pixelCount += range.pixelCount;
            } catch (IOException | RuntimeException ex) {
                // Ignore files that cannot participate in the shared DICOM normalization.
            }
        }

        if (pixelCount <= 0 || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return null;
        }

        int[] histogram = new int[65536];
        for (Path path : paths) {
            try {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length < 132 || !hasDicomPrefix(bytes)) {
                    continue;
                }

                DicomMetadata metadata = readMetadata(bytes);
                validateMetadata(metadata, bytes.length);
                addToHistogram(bytes, metadata, min, max, histogram);
            } catch (IOException | RuntimeException ex) {
                // Keep the window based on every readable file in the group.
            }
        }

        double low = percentileFromHistogram(histogram, pixelCount, min, max, lowPercentile);
        double high = percentileFromHistogram(histogram, pixelCount, min, max, highPercentile);
        if (!Double.isFinite(low) || !Double.isFinite(high) || high <= low) {
            return null;
        }

        return new IntensityWindow(low, high);
    }

    private static void validateMetadata(DicomMetadata metadata, int fileLength) throws IOException {
        if (metadata.rows <= 0 || metadata.columns <= 0) {
            throw new IOException("Image dimensions were not found in DICOM");
        }
        if (metadata.pixelDataOffset < 0 || metadata.pixelDataLength <= 0) {
            throw new IOException("Pixel Data tag was not found in DICOM");
        }
        if (metadata.samplesPerPixel != 1) {
            throw new IOException("Only monochrome DICOM images are supported");
        }
        if (metadata.bitsAllocated != 8 && metadata.bitsAllocated != 16) {
            throw new IOException("Only 8-bit and 16-bit DICOM images are supported");
        }
        if (metadata.photometricInterpretation != null
                && !metadata.photometricInterpretation.toUpperCase(Locale.ROOT).startsWith("MONOCHROME")) {
            throw new IOException("Only MONOCHROME DICOM images are supported");
        }
        if (!EXPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid)
                && !IMPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid)) {
            throw new IOException("Only uncompressed Little Endian DICOM is supported");
        }

        int expectedBytes = metadata.rows * metadata.columns * (metadata.bitsAllocated / 8);
        if (metadata.pixelDataLength < expectedBytes || metadata.pixelDataOffset + expectedBytes > fileLength) {
            throw new IOException("Not enough pixel data in DICOM file");
        }
    }

    private static byte[] normalize8Bit(byte[] bytes, DicomMetadata metadata, IntensityWindow intensityWindow) {
        int pixelCount = metadata.rows * metadata.columns;
        byte[] output = new byte[pixelCount];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (int i = 0; i < pixelCount; i++) {
            int value = bytes[metadata.pixelDataOffset + i] & 0xFF;
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            min = Math.min(min, scaled);
            max = Math.max(max, scaled);
        }

        double[] window = resolveWindow(metadata, min, max, intensityWindow);
        double low = window[0];
        double range = Math.max(window[1] - window[0], 1.0);

        for (int i = 0; i < pixelCount; i++) {
            int value = bytes[metadata.pixelDataOffset + i] & 0xFF;
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            output[i] = (byte) clampToByte((scaled - low) * 255.0 / range);
        }

        return output;
    }

    private static byte[] normalize16Bit(byte[] bytes, DicomMetadata metadata, IntensityWindow intensityWindow) {
        int pixelCount = metadata.rows * metadata.columns;
        byte[] output = new byte[pixelCount];
        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(metadata.littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (int i = 0; i < pixelCount; i++) {
            int value = read16BitValue(buffer, metadata.pixelDataOffset + i * 2, metadata.pixelRepresentationSigned);
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            min = Math.min(min, scaled);
            max = Math.max(max, scaled);
        }

        double[] window = resolveWindow(metadata, min, max, intensityWindow);
        double low = window[0];
        double range = Math.max(window[1] - window[0], 1.0);

        for (int i = 0; i < pixelCount; i++) {
            int value = read16BitValue(buffer, metadata.pixelDataOffset + i * 2, metadata.pixelRepresentationSigned);
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            output[i] = (byte) clampToByte((scaled - low) * 255.0 / range);
        }

        return output;
    }

    private static int read16BitValue(ByteBuffer buffer, int offset, boolean signed) {
        short value = buffer.getShort(offset);
        return signed ? value : (value & 0xFFFF);
    }

    private static double[] resolveWindow(DicomMetadata metadata, double min, double max, IntensityWindow intensityWindow) {
        if (intensityWindow != null && intensityWindow.isValid()) {
            return new double[]{intensityWindow.getLow(), intensityWindow.getHigh()};
        }
        if (metadata.windowWidth > 1.0) {
            double low = metadata.windowCenter - metadata.windowWidth / 2.0;
            double high = metadata.windowCenter + metadata.windowWidth / 2.0;
            return new double[]{low, high};
        }
        return new double[]{min, max};
    }

    private static IntensityRange scanIntensityRange(byte[] bytes, DicomMetadata metadata) {
        int pixelCount = metadata.rows * metadata.columns;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        if (metadata.bitsAllocated == 8) {
            for (int i = 0; i < pixelCount; i++) {
                int value = bytes[metadata.pixelDataOffset + i] & 0xFF;
                double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
                min = Math.min(min, scaled);
                max = Math.max(max, scaled);
            }
        } else {
            ByteBuffer buffer = ByteBuffer.wrap(bytes)
                    .order(metadata.littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < pixelCount; i++) {
                int value = read16BitValue(buffer, metadata.pixelDataOffset + i * 2, metadata.pixelRepresentationSigned);
                double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
                min = Math.min(min, scaled);
                max = Math.max(max, scaled);
            }
        }

        return new IntensityRange(min, max, pixelCount);
    }

    private static void addToHistogram(byte[] bytes, DicomMetadata metadata, double min, double max, int[] histogram) {
        int pixelCount = metadata.rows * metadata.columns;
        double scale = (histogram.length - 1) / (max - min);

        if (metadata.bitsAllocated == 8) {
            for (int i = 0; i < pixelCount; i++) {
                int value = bytes[metadata.pixelDataOffset + i] & 0xFF;
                double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
                histogram[histogramIndex(scaled, min, scale, histogram.length)]++;
            }
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(metadata.littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < pixelCount; i++) {
            int value = read16BitValue(buffer, metadata.pixelDataOffset + i * 2, metadata.pixelRepresentationSigned);
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            histogram[histogramIndex(scaled, min, scale, histogram.length)]++;
        }
    }

    private static int histogramIndex(double value, double min, double scale, int length) {
        int index = (int) Math.floor((value - min) * scale);
        if (index < 0) {
            return 0;
        }
        if (index >= length) {
            return length - 1;
        }
        return index;
    }

    private static double percentileFromHistogram(int[] histogram, long pixelCount,
                                                  double min, double max, double percentile) {
        long target = Math.max(0L, Math.min(pixelCount - 1L, Math.round((pixelCount - 1L) * percentile)));
        long cumulative = 0L;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative > target) {
                double fraction = (double) i / (histogram.length - 1);
                return min + fraction * (max - min);
            }
        }
        return max;
    }

    private static int clampToByte(double value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= 255) {
            return 255;
        }
        return (int) Math.round(value);
    }

    private static DicomMetadata readMetadata(byte[] bytes) throws IOException {
        DicomMetadata metadata = new DicomMetadata();
        ByteBuffer littleEndianBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int position = 132;
        boolean explicitVr = true;
        boolean syntaxResolved = false;

        while (position + 8 <= bytes.length) {
            int group = unsignedShort(littleEndianBuffer, position);
            int element = unsignedShort(littleEndianBuffer, position + 2);
            boolean useExplicitVr = group == 0x0002 || !syntaxResolved || explicitVr;
            TagValue tagValue = readTagValue(bytes, littleEndianBuffer, position, useExplicitVr);

            if (tagValue.valueOffset < 0 || tagValue.valueLength < 0
                    || tagValue.valueOffset + tagValue.valueLength > bytes.length) {
                throw new IOException("Invalid DICOM structure");
            }

            if (group == 0x0002 && element == 0x0010) {
                metadata.transferSyntaxUid = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
                explicitVr = !IMPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid);
                metadata.littleEndian = !EXPLICIT_VR_BIG_ENDIAN.equals(metadata.transferSyntaxUid);
                syntaxResolved = true;
            } else if (group == 0x0008 && element == 0x0060) {
                metadata.modality = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
            } else if (group == 0x0020 && element == 0x000D) {
                metadata.studyInstanceUid = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
            } else if (group == 0x0020 && element == 0x000E) {
                metadata.seriesInstanceUid = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
            } else if (group == 0x0020 && element == 0x0013) {
                metadata.instanceNumber = parseInteger(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0020 && element == 0x0032) {
                metadata.imagePositionPatient = parseDoubleArray(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0020 && element == 0x0037) {
                metadata.imageOrientationPatient = parseDoubleArray(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0028 && element == 0x0002) {
                metadata.samplesPerPixel = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0004) {
                metadata.photometricInterpretation = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
            } else if (group == 0x0028 && element == 0x0010) {
                metadata.rows = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0011) {
                metadata.columns = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0030) {
                metadata.pixelSpacing = parseDoubleArray(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0028 && element == 0x0100) {
                metadata.bitsAllocated = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0103) {
                metadata.pixelRepresentationSigned = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian) == 1;
            } else if (group == 0x0028 && element == 0x1050) {
                metadata.windowCenter = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0028 && element == 0x1051) {
                metadata.windowWidth = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0028 && element == 0x1052) {
                metadata.rescaleIntercept = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0028 && element == 0x1053) {
                metadata.rescaleSlope = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0018 && element == 0x0050) {
                metadata.sliceThickness = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x0018 && element == 0x0088) {
                metadata.spacingBetweenSlices = parseDouble(readAscii(bytes, tagValue.valueOffset, tagValue.valueLength));
            } else if (group == 0x7FE0 && element == 0x0010) {
                metadata.pixelDataOffset = tagValue.valueOffset;
                metadata.pixelDataLength = tagValue.valueLength;
                break;
            }

            position = tagValue.valueOffset + tagValue.valueLength;
        }

        return metadata;
    }

    private static double computeSliceSortPosition(DicomMetadata metadata) {
        if (metadata.imagePositionPatient == null || metadata.imagePositionPatient.length < 3) {
            return Double.NaN;
        }

        if (metadata.imageOrientationPatient == null || metadata.imageOrientationPatient.length < 6) {
            return metadata.imagePositionPatient[2];
        }

        double[] rowDirection = normalizeVector(
                metadata.imageOrientationPatient[0],
                metadata.imageOrientationPatient[1],
                metadata.imageOrientationPatient[2]
        );
        double[] columnDirection = normalizeVector(
                metadata.imageOrientationPatient[3],
                metadata.imageOrientationPatient[4],
                metadata.imageOrientationPatient[5]
        );

        double[] normal = cross(rowDirection, columnDirection);
        double length = vectorLength(normal);
        if (length < 1e-8) {
            return metadata.imagePositionPatient[2];
        }

        double[] unitNormal = new double[]{normal[0] / length, normal[1] / length, normal[2] / length};
        return dot(metadata.imagePositionPatient, unitNormal);
    }

    private static double resolveNominalSpacingMm(DicomMetadata metadata) {
        if (Double.isFinite(metadata.spacingBetweenSlices) && metadata.spacingBetweenSlices > 0.0) {
            return metadata.spacingBetweenSlices;
        }
        if (Double.isFinite(metadata.sliceThickness) && metadata.sliceThickness > 0.0) {
            return metadata.sliceThickness;
        }
        return Double.NaN;
    }

    private static double[] parseDoubleArray(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String[] parts = value.split("\\\\");
        double[] result = new double[parts.length];
        int count = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result[count++] = Double.parseDouble(trimmed);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        return count == 0 ? null : Arrays.copyOf(result, count);
    }

    private static double[] normalizeVector(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-8) {
            return new double[]{x, y, z};
        }
        return new double[]{x / length, y / length, z / length};
    }

    private static double[] cross(double[] left, double[] right) {
        return new double[]{
                left[1] * right[2] - left[2] * right[1],
                left[2] * right[0] - left[0] * right[2],
                left[0] * right[1] - left[1] * right[0]
        };
    }

    private static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    private static double vectorLength(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static TagValue readTagValue(byte[] bytes, ByteBuffer littleEndianBuffer, int position, boolean explicitVr) {
        if (explicitVr) {
            String vr = new String(bytes, position + 4, 2, StandardCharsets.US_ASCII);
            if (usesLongLength(vr)) {
                return new TagValue(position + 12, littleEndianBuffer.getInt(position + 8));
            }
            return new TagValue(position + 8, unsignedShort(littleEndianBuffer, position + 6));
        }

        return new TagValue(position + 8, littleEndianBuffer.getInt(position + 4));
    }

    private static boolean usesLongLength(String vr) {
        return "OB".equals(vr) || "OD".equals(vr) || "OF".equals(vr) || "OL".equals(vr)
                || "OW".equals(vr) || "SQ".equals(vr) || "UC".equals(vr) || "UR".equals(vr)
                || "UT".equals(vr) || "UN".equals(vr);
    }

    private static int readUnsignedShort(byte[] bytes, int offset, boolean littleEndian) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, 2)
                .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        return buffer.getShort() & 0xFFFF;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    private static String readAscii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
    }

    private static double parseDouble(String value) {
        if (value == null || value.isEmpty()) {
            return 0.0;
        }

        int separator = value.indexOf('\\');
        String firstValue = separator >= 0 ? value.substring(0, separator) : value;
        return Double.parseDouble(firstValue.trim());
    }

    private static int parseInteger(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Integer.MAX_VALUE;
        }

        int separator = value.indexOf('\\');
        String firstValue = separator >= 0 ? value.substring(0, separator) : value;
        try {
            return Integer.parseInt(firstValue.trim());
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean hasDicomPrefix(byte[] bytes) {
        return bytes[128] == 'D' && bytes[129] == 'I' && bytes[130] == 'C' && bytes[131] == 'M';
    }

    public static final class DicomSeriesInfo {
        private final String groupKey;
        private final int instanceNumber;
        private final double sliceSortPositionMm;
        private final double nominalSliceSpacingMm;
        private final double[] imagePositionPatient;
        private final double[] imageOrientationPatient;
        private final double[] pixelSpacing;
        private final double sliceThicknessMm;
        private final double spacingBetweenSlicesMm;

        private DicomSeriesInfo(String groupKey, int instanceNumber, double sliceSortPositionMm,
                                double nominalSliceSpacingMm, double[] imagePositionPatient,
                                double[] imageOrientationPatient, double[] pixelSpacing,
                                double sliceThicknessMm, double spacingBetweenSlicesMm) {
            this.groupKey = groupKey;
            this.instanceNumber = instanceNumber;
            this.sliceSortPositionMm = sliceSortPositionMm;
            this.nominalSliceSpacingMm = nominalSliceSpacingMm;
            this.imagePositionPatient = imagePositionPatient == null ? null : imagePositionPatient.clone();
            this.imageOrientationPatient = imageOrientationPatient == null ? null : imageOrientationPatient.clone();
            this.pixelSpacing = pixelSpacing == null ? null : pixelSpacing.clone();
            this.sliceThicknessMm = sliceThicknessMm;
            this.spacingBetweenSlicesMm = spacingBetweenSlicesMm;
        }

        public String getGroupKey() {
            return groupKey;
        }

        public int getInstanceNumber() {
            return instanceNumber;
        }

        public double getSliceSortPositionMm() {
            return sliceSortPositionMm;
        }

        public double getNominalSliceSpacingMm() {
            return nominalSliceSpacingMm;
        }

        public double[] getImagePositionPatient() {
            return imagePositionPatient == null ? null : imagePositionPatient.clone();
        }

        public double[] getImageOrientationPatient() {
            return imageOrientationPatient == null ? null : imageOrientationPatient.clone();
        }

        public double[] getPixelSpacing() {
            return pixelSpacing == null ? null : pixelSpacing.clone();
        }

        public double getSliceThicknessMm() {
            return sliceThicknessMm;
        }

        public double getSpacingBetweenSlicesMm() {
            return spacingBetweenSlicesMm;
        }

        public boolean hasSpatialGeometry() {
            return Double.isFinite(sliceSortPositionMm);
        }
    }

    public static final class IntensityWindow {
        private final double low;
        private final double high;

        public IntensityWindow(double low, double high) {
            this.low = low;
            this.high = high;
        }

        public double getLow() {
            return low;
        }

        public double getHigh() {
            return high;
        }

        private boolean isValid() {
            return Double.isFinite(low) && Double.isFinite(high) && high > low;
        }
    }

    private static final class IntensityRange {
        private final double min;
        private final double max;
        private final int pixelCount;

        private IntensityRange(double min, double max, int pixelCount) {
            this.min = min;
            this.max = max;
            this.pixelCount = pixelCount;
        }
    }

    private static final class TagValue {
        private final int valueOffset;
        private final int valueLength;

        private TagValue(int valueOffset, int valueLength) {
            this.valueOffset = valueOffset;
            this.valueLength = valueLength;
        }
    }

    private static final class DicomMetadata {
        private int rows;
        private int columns;
        private int samplesPerPixel = 1;
        private int bitsAllocated;
        private boolean pixelRepresentationSigned;
        private double[] imagePositionPatient;
        private double[] imageOrientationPatient;
        private double[] pixelSpacing;
        private double sliceThickness = Double.NaN;
        private double spacingBetweenSlices = Double.NaN;
        private double windowCenter;
        private double windowWidth;
        private double rescaleIntercept;
        private double rescaleSlope = 1.0;
        private String photometricInterpretation;
        private String transferSyntaxUid;
        private String modality;
        private String studyInstanceUid;
        private String seriesInstanceUid;
        private int instanceNumber = Integer.MAX_VALUE;
        private boolean littleEndian = true;
        private int pixelDataOffset = -1;
        private int pixelDataLength = -1;
    }
}
