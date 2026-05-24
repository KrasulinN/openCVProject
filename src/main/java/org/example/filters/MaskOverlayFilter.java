package org.example.filters;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

/**
 * Фильтр, который накладывает бинарную маску на исходное изображение.
 * Пиксели, соответствующие белым областям маски (255), остаются без изменений,
 * а пиксели, соответствующие чёрным областям маски (0), становятся чёрными.
 *
 * Этот фильтр должен применяться ПОСЛЕ создания маски (ThresholdFilter + ContourFilter)
 * и ДО отображения пользователю.
 */
public class MaskOverlayFilter implements FilterStrategy {

    private final Mat mask;

    /**
     * @param mask бинарная маска, где белые области (255) - это области для сохранения,
     *             а чёрные области (0) - это области для удаления
     */
    public MaskOverlayFilter(Mat mask) {
        if (mask == null || mask.empty()) {
            throw new IllegalArgumentException("Маска не может быть пустой");
        }
        this.mask = mask.clone();
    }

    @Override
    public Mat apply(Mat source) {
        if (source == null || source.empty()) {
            return source;
        }

        // Убеждаемся, что маска имеет тот же размер, что и исходное изображение
        if (mask.rows() != source.rows() || mask.cols() != source.cols()) {
            throw new IllegalArgumentException(
                    "Размеры маски (" + mask.cols() + "x" + mask.rows() +
                            ") не совпадают с размерами изображения (" + source.cols() + "x" + source.rows() + ")"
            );
        }

        Mat result = new Mat();

        // Если изображение цветное (3 канала)
        if (source.channels() == 3) {
            // Создаём трёхканальную маску из одноканальной
            Mat mask3Channel = new Mat();
            Core.merge(java.util.Arrays.asList(mask, mask, mask), mask3Channel);

            // Применяем маску: source & mask
            Core.bitwise_and(source, mask3Channel, result);

            mask3Channel.release();
        } else {
            // Для одноканального (чёрно-белого) изображения
            Core.bitwise_and(source, mask, result);
        }

        return result;
    }

    @Override
    public String getName() {
        return "Наложение маски";
    }

    /**
     * Освобождает ресурсы, занятые маской
     */
    public void release() {
        if (mask != null && !mask.empty()) {
            mask.release();
        }
    }
}