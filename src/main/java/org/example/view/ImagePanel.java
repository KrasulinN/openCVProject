package org.example.view;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class ImagePanel extends JPanel {
    private BufferedImage image;

    public ImagePanel() {
        setBackground(Color.LIGHT_GRAY);
    }

    public void setImage(Mat mat) {
        if (mat == null || mat.empty()) {
            this.image = null;
            repaint();
            return;
        }

        this.image = matToBufferedImage(mat);
        repaint();
    }

    private BufferedImage matToBufferedImage(Mat mat) {
        if (mat == null || mat.empty()) return null;

        // Конвертируем BGR в RGB для правильных цветов
        if (mat.channels() == 3) {
            Mat rgbMat = new Mat();
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB);

            BufferedImage bufImage = new BufferedImage(
                    rgbMat.cols(),
                    rgbMat.rows(),
                    BufferedImage.TYPE_3BYTE_BGR
            );

            byte[] data = new byte[rgbMat.rows() * rgbMat.cols() * rgbMat.channels()];
            rgbMat.get(0, 0, data);
            bufImage.getRaster().setDataElements(0, 0, rgbMat.cols(), rgbMat.rows(), data);

            return bufImage;
        } else {
            // Черно-белое изображение
            BufferedImage bufImage = new BufferedImage(
                    mat.cols(),
                    mat.rows(),
                    BufferedImage.TYPE_BYTE_GRAY
            );

            byte[] data = new byte[mat.rows() * mat.cols()];
            mat.get(0, 0, data);
            bufImage.getRaster().setDataElements(0, 0, mat.cols(), mat.rows(), data);

            return bufImage;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null) {
            // Центрируем изображение
            int x = (getWidth() - image.getWidth()) / 2;
            int y = (getHeight() - image.getHeight()) / 2;
            g.drawImage(image, x, y, this);
        } else {
            // Рисуем подсказку, если нет изображения
            g.setColor(Color.GRAY);
            g.drawString("Нет изображения", getWidth()/2 - 50, getHeight()/2);
        }
    }
}