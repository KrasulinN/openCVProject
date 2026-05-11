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
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

        ImageModel model = new ImageModel();
        ImageSeriesModel seriesModel = new ImageSeriesModel();
        MainView view = new MainView();
        ImageController controller = new ImageController(model, seriesModel, view);
        SwingUtilities.invokeLater(() -> view.setVisible(true));

        // Пытаемся автоматически загрузить папку D:\data
        SwingUtilities.invokeLater(() -> {
            Path defaultPath = Paths.get("D:\\data");
            if (Files.exists(defaultPath) && Files.isDirectory(defaultPath)) {
                try {
                    List<Path> imagePaths = new ArrayList<>();
                    Files.list(defaultPath)
                            .filter(Files::isRegularFile)
                            .forEach(imagePaths::add);

                    if (!imagePaths.isEmpty()) {
                        System.out.println("Автоматическая загрузка из: " + defaultPath);
                        System.out.println("Найдено файлов: " + imagePaths.size());
                        // Вызываем загрузку через контроллер
                        controller.loadSeries(imagePaths);
                    }
                } catch (IOException e) {
                    System.out.println("Не удалось прочитать папку D:\\ " + e.getMessage());
                }
            } else {
                System.out.println("Папка D:\\data не найдена, работа продолжается без автозагрузки.");
            }
        });
    }
}