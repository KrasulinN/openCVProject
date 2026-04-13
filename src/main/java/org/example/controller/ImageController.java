package org.example.controller;

import org.example.model.FilterType;
import org.example.model.ImageModel;
import org.example.utils.DicomImageLoader;
import org.example.view.MainView;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ImageController {
    private final ImageModel model;
    private final MainView view;

    public ImageController(ImageModel model, MainView view) {
        this.model = model;
        this.view = view;
        this.view.setController(this);
    }

    public void onOpenImage(ActionEvent e) {
        String pathString = view.showOpenFileDialog();
        if (pathString == null) {
            return;
        }

        Path path = Paths.get(pathString);
        Mat image = Imgcodecs.imread(pathString);

        if (image.empty() && DicomImageLoader.isDicomFile(path)) {
            try {
                image = DicomImageLoader.load(path);
            } catch (Exception ex) {
                view.showError("Не удалось загрузить DICOM: " + ex.getMessage());
                return;
            }
        }

        if (image.empty()) {
            view.showError("Не удалось загрузить изображение");
            return;
        }

        model.loadImage(image);
        view.displayImage(model.getCurrentImage());
        updateStatus("Загружено: " + pathString);
    }

    public void onGrayFilter(ActionEvent e) {
        if (!model.hasImage()) {
            view.showError("Сначала загрузите изображение!");
            return;
        }

        if (model.getCurrentImage().channels() == 1) {
            updateStatus("Изображение уже черно-белое");
            return;
        }

        model.applyFilter(FilterType.GRAY);
        view.displayImage(model.getCurrentImage());
        updateStatus("Применен черно-белый фильтр");
    }

    public void onBlurFilter(ActionEvent e) {
        if (!model.hasImage()) {
            view.showError("Сначала загрузите изображение!");
            return;
        }

        model.applyFilter(FilterType.BLUR);
        view.displayImage(model.getCurrentImage());
        updateStatus("Применено размытие");
    }

    public void onUndo(ActionEvent e) {
        if (model.undo()) {
            view.displayImage(model.getCurrentImage());
            updateStatus("Отмена: " + model.getUndoStackSize() + " шагов осталось");
        } else {
            updateStatus("Нечего отменять");
        }
    }

    public void onRedo(ActionEvent e) {
        if (model.redo()) {
            view.displayImage(model.getCurrentImage());
            updateStatus("Повтор: " + model.getRedoStackSize() + " шагов осталось");
        } else {
            updateStatus("Нечего повторять");
        }
    }

    public void onReset(ActionEvent e) {
        if (model.hasImage()) {
            model.resetToOriginal();
            view.displayImage(model.getCurrentImage());
            updateStatus("Сброшено к оригиналу");
        }
    }

    private void updateStatus(String message) {
        String info = model.hasImage() ? " [" + model.getImageInfo() + "]" : "";
        view.updateStatus(message + info
                + " | Undo: " + model.getUndoStackSize()
                + " Redo: " + model.getRedoStackSize());
    }
}
