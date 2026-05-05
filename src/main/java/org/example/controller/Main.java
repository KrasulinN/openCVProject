package org.example.controller;

import nu.pattern.OpenCV;
import org.example.model.ImageModel;
import org.example.model.ImageSeriesModel;
import org.example.view.MainView;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import javax.swing.*;
import java.util.Random;

public class Main {
    public static void main(String[] args) {
        try {
            OpenCV.loadLocally();
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(null,
                    "Не удалось загрузить OpenCV!\nПроверьте установку native DLL.",
                    "Критическая ошибка",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
/*
        Mat lowerMask = new Mat();
        Mat upperMask = new Mat();
        Imgproc.threshold(m, lowerMask, 180, 255, Imgproc.THRESH_BINARY);
        Imgproc.threshold(m, upperMask, 200, 255, Imgproc.THRESH_BINARY_INV);
        Mat result = new Mat();
        Core.bitwise_and(lowerMask, upperMask, result);
        Mat result2 = new Mat();
        Core.bitwise_and(m, result, result2);

*/
        ImageModel model = new ImageModel();
        ImageSeriesModel seriesModel = new ImageSeriesModel();
        MainView view = new MainView();
        new ImageController(model, seriesModel, view);
        SwingUtilities.invokeLater(() -> view.setVisible(true));
    }
}
