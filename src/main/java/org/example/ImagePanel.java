package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class ImagePanel extends JPanel {
    private BufferedImage image;

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            g.drawImage(image, 0, 0, this);
        }
    }

    // Конвертирует OpenCV Mat в BufferedImage для Swing
    public void setImage(Mat mat) {
        if (mat == null || mat.empty()) return;

        Mat convertedMat;

        // Если изображение цветное (3 канала) - конвертируем BGR в RGB
        if (mat.channels() == 3) {
            convertedMat = new Mat();
            Imgproc.cvtColor(mat, convertedMat, Imgproc.COLOR_BGR2RGB);
        } else {
            // Если черно-белое - оставляем как есть
            convertedMat = mat;
        }

        // Создаем BufferedImage
        int type = convertedMat.channels() > 1 ?
                BufferedImage.TYPE_3BYTE_BGR :
                BufferedImage.TYPE_BYTE_GRAY;

        BufferedImage bufImage = new BufferedImage(
                convertedMat.cols(),
                convertedMat.rows(),
                type
        );

        // Копируем данные
        byte[] data = new byte[convertedMat.rows() * convertedMat.cols() * convertedMat.channels()];
        convertedMat.get(0, 0, data);
        bufImage.getRaster().setDataElements(0, 0, convertedMat.cols(), convertedMat.rows(), data);

        this.image = bufImage;
        repaint();
    }
}