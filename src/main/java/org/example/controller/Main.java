package org.example.controller;

import org.example.model.ImageModel;
import org.example.view.MainView;
import org.example.utils.OpenCVLoader;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        // Загружаем OpenCV
        if (!OpenCVLoader.loadOpenCV()) {
            JOptionPane.showMessageDialog(null,
                    "Не удалось загрузить OpenCV!\nПроверьте путь к DLL.",
                    "Критическая ошибка",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Создаем компоненты MVC
        ImageModel model = new ImageModel();
        MainView view = new MainView();
        ImageController controller = new ImageController(model, view);

        // Запускаем приложение
        SwingUtilities.invokeLater(() -> {
            view.setVisible(true);
        });
    }
}