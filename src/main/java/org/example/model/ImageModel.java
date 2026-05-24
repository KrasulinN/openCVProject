package org.example.model;

import org.example.filters.FilterBatch;
import org.opencv.core.Mat;

import java.util.Stack;

public class ImageModel {
    private Mat currentImage;
    private Stack<Mat> undoStack;
    private Stack<Mat> redoStack;
    private boolean isLoadingFromHistory;

    public ImageModel() {
        this.undoStack = new Stack<>();
        this.redoStack = new Stack<>();
        this.isLoadingFromHistory = false;
    }

    public void loadImage(Mat image) {
        undoStack.clear();
        redoStack.clear();

        this.currentImage = image.clone();
        undoStack.push(image.clone());
    }

    public void clear() {
        currentImage = null;
        undoStack.clear();
        redoStack.clear();
        isLoadingFromHistory = false;
    }

    public FilterBatch createFilterBatch(String name) {
        return new FilterBatch(name);
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;

        if (currentImage != null) {
            redoStack.push(currentImage.clone());
        }

        isLoadingFromHistory = true;
        currentImage = undoStack.pop();
        isLoadingFromHistory = false;

        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;

        if (currentImage != null) {
            undoStack.push(currentImage.clone());
        }

        isLoadingFromHistory = true;
        currentImage = redoStack.pop();
        isLoadingFromHistory = false;

        return true;
    }

    public void resetToOriginal() {
        if (undoStack.isEmpty()) return;

        redoStack.clear();
        Mat original = undoStack.firstElement().clone();

        isLoadingFromHistory = true;
        currentImage = original;

        undoStack.clear();
        undoStack.push(original.clone());

        isLoadingFromHistory = false;
    }

    public Mat getCurrentImage() {
        return currentImage;
    }

    public int getUndoStackSize() {
        return undoStack.size();
    }

    public int getRedoStackSize() {
        return redoStack.size();
    }

    public boolean hasImage() {
        return currentImage != null;
    }

    public String getImageInfo() {
        if (currentImage == null) return "Нет изображения";
        return String.format("%dx%d, %d каналов",
                currentImage.cols(), currentImage.rows(), currentImage.channels());
    }
}
