package org.example.utils;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static Mat load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 132 || !hasDicomPrefix(bytes)) {
            throw new IOException("Файл не похож на DICOM");
        }

        DicomMetadata metadata = readMetadata(bytes);
        validateMetadata(metadata, bytes.length);

        byte[] normalizedPixels = metadata.bitsAllocated == 8
                ? normalize8Bit(bytes, metadata)
                : normalize16Bit(bytes, metadata);

        Mat image = new Mat(metadata.rows, metadata.columns, CvType.CV_8UC1);
        image.put(0, 0, normalizedPixels);
        return image;
    }

    private static void validateMetadata(DicomMetadata metadata, int fileLength) throws IOException {
        if (metadata.rows <= 0 || metadata.columns <= 0) {
            throw new IOException("В DICOM не найдены размеры изображения");
        }
        if (metadata.pixelDataOffset < 0 || metadata.pixelDataLength <= 0) {
            throw new IOException("В DICOM не найден Pixel Data");
        }
        if (metadata.samplesPerPixel != 1) {
            throw new IOException("Поддерживаются только монохромные DICOM-изображения");
        }
        if (metadata.bitsAllocated != 8 && metadata.bitsAllocated != 16) {
            throw new IOException("Поддерживаются только 8-bit и 16-bit DICOM-изображения");
        }
        if (metadata.photometricInterpretation != null
                && !metadata.photometricInterpretation.toUpperCase(Locale.ROOT).startsWith("MONOCHROME")) {
            throw new IOException("Поддерживаются только MONOCHROME DICOM-изображения");
        }
        if (!EXPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid)
                && !IMPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid)) {
            throw new IOException("Поддерживаются только несжатые DICOM (Little Endian)");
        }

        int expectedBytes = metadata.rows * metadata.columns * (metadata.bitsAllocated / 8);
        if (metadata.pixelDataLength < expectedBytes || metadata.pixelDataOffset + expectedBytes > fileLength) {
            throw new IOException("Недостаточно пиксельных данных");
        }
    }

    private static byte[] normalize8Bit(byte[] bytes, DicomMetadata metadata) {
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

        double[] window = resolveWindow(metadata, min, max);
        double low = window[0];
        double range = Math.max(window[1] - window[0], 1.0);

        for (int i = 0; i < pixelCount; i++) {
            int value = bytes[metadata.pixelDataOffset + i] & 0xFF;
            double scaled = value * metadata.rescaleSlope + metadata.rescaleIntercept;
            output[i] = (byte) clampToByte((scaled - low) * 255.0 / range);
        }

        return output;
    }

    private static byte[] normalize16Bit(byte[] bytes, DicomMetadata metadata) {
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

        double[] window = resolveWindow(metadata, min, max);
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

    private static double[] resolveWindow(DicomMetadata metadata, double min, double max) {
        if (metadata.windowWidth > 1.0) {
            double low = metadata.windowCenter - metadata.windowWidth / 2.0;
            double high = metadata.windowCenter + metadata.windowWidth / 2.0;
            return new double[]{low, high};
        }
        return new double[]{min, max};
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
                throw new IOException("Некорректная структура DICOM");
            }

            if (group == 0x0002 && element == 0x0010) {
                metadata.transferSyntaxUid = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
                explicitVr = !IMPLICIT_VR_LITTLE_ENDIAN.equals(metadata.transferSyntaxUid);
                metadata.littleEndian = !EXPLICIT_VR_BIG_ENDIAN.equals(metadata.transferSyntaxUid);
                syntaxResolved = true;
            } else if (group == 0x0028 && element == 0x0002) {
                metadata.samplesPerPixel = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0004) {
                metadata.photometricInterpretation = readAscii(bytes, tagValue.valueOffset, tagValue.valueLength);
            } else if (group == 0x0028 && element == 0x0010) {
                metadata.rows = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
            } else if (group == 0x0028 && element == 0x0011) {
                metadata.columns = readUnsignedShort(bytes, tagValue.valueOffset, metadata.littleEndian);
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
            } else if (group == 0x7FE0 && element == 0x0010) {
                metadata.pixelDataOffset = tagValue.valueOffset;
                metadata.pixelDataLength = tagValue.valueLength;
                break;
            }

            position = tagValue.valueOffset + tagValue.valueLength;
        }

        return metadata;
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

    private static boolean hasDicomPrefix(byte[] bytes) {
        return bytes[128] == 'D' && bytes[129] == 'I' && bytes[130] == 'C' && bytes[131] == 'M';
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
        private double windowCenter;
        private double windowWidth;
        private double rescaleIntercept;
        private double rescaleSlope = 1.0;
        private String photometricInterpretation;
        private String transferSyntaxUid;
        private boolean littleEndian = true;
        private int pixelDataOffset = -1;
        private int pixelDataLength = -1;
    }
}