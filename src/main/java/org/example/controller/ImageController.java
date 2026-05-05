package org.example.controller;

import org.example.filters.FilterBatch;
import org.example.filters.ContourFilter;
import org.example.filters.ThresholdFilter;
import org.example.filters.MaskOverlayFilter;
import org.example.model.ImageModel;
import org.example.model.ImageSeriesModel;
import org.example.model.SeriesImageItem;
import org.example.model.SeriesTreeNodeData;
import org.example.utils.DicomImageLoader;
import org.example.view.MainView;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ImageController {
    private final ImageModel model;
    private final ImageSeriesModel seriesModel;
    private final MainView view;
    private FilterBatch activeContourBatch;
    private String activeContourGroupKey;
    private boolean contourApplied;
    private boolean contourCanRedo;

    public ImageController(ImageModel model, ImageSeriesModel seriesModel, MainView view) {
        this.model = model;
        this.seriesModel = seriesModel;
        this.view = view;
        this.activeContourBatch = null;
        this.activeContourGroupKey = null;
        this.contourApplied = false;
        this.contourCanRedo = false;

        this.view.setController(this);
        this.view.setGroupFilterListener(this::onGroupFilterChanged);
        this.view.setSeriesSelectionListener(this::onSeriesSelectionChanged);
    }

    public void onOpenFiles(ActionEvent e) {
        List<Path> paths = view.showOpenFilesDialog();
        loadSeries(paths);
    }

    public void onOpenFolder(ActionEvent e) {
        Path folder = view.showOpenFolderDialog();
        if (folder == null) {
            return;
        }

        List<Path> paths = collectFolderFiles(folder);
        loadSeries(paths);
    }

    public void onSeriesSelectionChanged(SeriesTreeNodeData nodeData) {
        if (nodeData == null) {
            return;
        }

        SeriesImageItem item = nodeData.isGroup()
                ? seriesModel.findFirstItemInGroup(nodeData.getGroupKey())
                : nodeData.getItem();

        if (item == null) {
            model.clear();
            view.displayImage(null);
            updateStatus("В выбранной группе нет снимков");
            return;
        }

        seriesModel.setSelectedItem(item);
        model.loadImage(item.getImage());
        view.displayImage(model.getCurrentImage());

        if (nodeData.isGroup()) {
            updateStatus("Группа " + item.getGroupKey() + ": " + item.getFileName());
        } else {
            updateStatus("Открыто: " + item.getGroupKey() + " / " + item.getFileName());
        }
    }

    public void onGroupFilterChanged(String selectedGroupLabel) {
        seriesModel.setGroupFilter(selectedGroupLabel);
        refreshSeriesBrowser();
        selectFirstVisibleItem();
    }

    public void onUndo(ActionEvent e) {
        if (undoContourFilter()) {
            return;
        }

        if (model.undo()) {
            syncSelectedItemImage();
            view.displayImage(model.getCurrentImage());
            updateStatus("Отмена: " + model.getUndoStackSize() + " шагов осталось");
        } else {
            updateStatus("Нечего отменять");
        }
    }

    public void onRedo(ActionEvent e) {
        if (redoContourFilter()) {
            return;
        }

        if (model.redo()) {
            syncSelectedItemImage();
            view.displayImage(model.getCurrentImage());
            updateStatus("Повтор: " + model.getRedoStackSize() + " шагов осталось");
        } else {
            updateStatus("Нечего повторять");
        }
    }

    public void onReset(ActionEvent e) {
        if (resetContourFilter()) {
            return;
        }

        if (model.hasImage()) {
            model.resetToOriginal();
            syncSelectedItemImage();
            view.displayImage(model.getCurrentImage());
            updateStatus("Сброшено к оригиналу");
        }
    }

    private void loadSeries(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }

        Set<Path> uniquePaths = new LinkedHashSet<>(paths);
        List<SeriesImageItem> loadedItems = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Path path : uniquePaths) {
            SeriesImageItem item = loadImageItem(path);
            if (item != null) {
                loadedItems.add(item);
            } else {
                errors.add(path.getFileName() != null ? path.getFileName().toString() : path.toString());
            }
        }

        if (loadedItems.isEmpty()) {
            model.clear();
            seriesModel.clear();
            clearContourFilterState();
            refreshSeriesBrowser();
            view.displayImage(null);
            view.showError("Не удалось загрузить ни один снимок.");
            return;
        }

        String currentFilter = seriesModel.getGroupFilter();
        seriesModel.replaceItems(loadedItems);
        clearContourFilterState();
        if (currentFilter != null && seriesModel.findFirstItemInGroup(currentFilter) == null) {
            seriesModel.setGroupFilter(null);
        }

        refreshSeriesBrowser();
        selectFirstVisibleItem();

        if (!errors.isEmpty()) {
            view.showError("Некоторые файлы не удалось загрузить:\n" + joinLines(errors));
        }
    }

    private SeriesImageItem loadImageItem(Path path) {
        if (path == null) {
            return null;
        }

        DicomImageLoader.DicomSeriesInfo seriesInfo = DicomImageLoader.readSeriesInfo(path);
        String groupKey = seriesInfo != null
                ? seriesInfo.getGroupKey()
                : ImageSeriesModel.extractGroupKey(path);
        double slicePositionMm = seriesInfo != null
                ? seriesInfo.getSliceSortPositionMm()
                : Double.NaN;

        Mat image = Imgcodecs.imread(path.toString());

        if (image.empty()) {
            try {
                image = DicomImageLoader.load(path);
            } catch (IOException ex) {
                return null;
            }
        }

        if (image.empty()) {
            return null;
        }

        return new SeriesImageItem(path, groupKey, slicePositionMm, image);
    }

    private List<Path> collectFolderFiles(Path folder) {
        List<Path> paths = new ArrayList<>();
        if (folder == null) {
            return paths;
        }

        try (java.util.stream.Stream<Path> stream = Files.list(folder)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(this::isLikelyImageFile)
                    .sorted(new java.util.Comparator<Path>() {
                        @Override
                        public int compare(Path left, Path right) {
                            return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
                        }
                    })
                    .forEach(paths::add);
        } catch (IOException ex) {
            view.showError("Не удалось прочитать папку: " + ex.getMessage());
        }

        return paths;
    }

    private boolean isLikelyImageFile(Path path) {
        return path != null && path.getFileName() != null;
    }

    private void refreshSeriesBrowser() {
        view.updateGroupFilterOptions(seriesModel.getAvailableGroupKeys(), seriesModel.getGroupFilter());
        view.setSeriesTreeModel(seriesModel.buildTreeModel());
        view.expandAllSeriesGroups();
    }

    private void selectFirstVisibleItem() {
        SeriesImageItem firstVisibleItem = seriesModel.findFirstVisibleItem();
        if (firstVisibleItem == null) {
            model.clear();
            seriesModel.setSelectedItem(null);
            view.clearSeriesSelection();
            view.displayImage(null);
            updateStatus("Нет снимков в выбранном фильтре");
            return;
        }

        view.selectFirstSeriesLeaf();
    }

    private void syncSelectedItemImage() {
        seriesModel.updateSelectedItemImage(model.getCurrentImage());
    }

    private void updateStatus(String message) {
        String info = model.hasImage() ? " [" + model.getImageInfo() + "]" : "";
        view.updateStatus(message + info
                + " | Undo: " + model.getUndoStackSize()
                + " Redo: " + model.getRedoStackSize());
    }

    private String joinLines(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    public void onApplyContourToGroup(ActionEvent e) {
        String selectedGroup = view.getSelectedGroupFilter();
        if (selectedGroup == null || selectedGroup.isEmpty() || selectedGroup.equals("Все группы")) {
            view.showError("Выберите группу снимков");
            return;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(selectedGroup);
        if (itemsInGroup.isEmpty()) {
            view.showError("В группе нет снимков");
            return;
        }

        // Получаем пороговые значения из полей ввода
        int minBrightness = view.getMinBrightnessThreshold();
        int maxBrightness = view.getMaxBrightnessThreshold();

        // Валидация порогов
        if (minBrightness < 0 || minBrightness > 255 || maxBrightness < 0 || maxBrightness > 255) {
            view.showError("Значения яркости должны быть в диапазоне 0-255");
            return;
        }
        if (minBrightness > maxBrightness) {
            view.showError("Минимальное значение яркости не может быть больше максимального");
            return;
        }

        System.out.println("\n========== НАЧАЛО ОБРАБОТКИ КОНТУРОВ ==========");
        System.out.println("Группа: " + selectedGroup);
        System.out.println("Порог яркости: [" + minBrightness + ", " + maxBrightness + "]");
        System.out.println("Количество снимков: " + itemsInGroup.size());

        FilterBatch batch = model.createFilterBatch("Поиск контуров для " + selectedGroup);
        ThresholdFilter thresholdFilter = new ThresholdFilter(minBrightness, maxBrightness);
        thresholdFilter.setDebugMode(true); // Включаем режим отладки
        batch.addFilter(thresholdFilter);

        ContourFilter contourFilter = new ContourFilter();
        contourFilter.setDebugMode(true); // Включаем режим отладки
        batch.addFilter(contourFilter);

        // Применяем фильтры для создания маски
        for (int i = 0; i < itemsInGroup.size(); i++) {
            SeriesImageItem item = itemsInGroup.get(i);
            System.out.println("\n--- Обработка снимка " + (i + 1) + "/" + itemsInGroup.size() +
                    ": " + item.getFileName() + " ---");

            Mat originalImage = item.getOriginalImage().clone();
            Mat processed = originalImage.clone();

            // Применяем пороговый фильтр для получения маски
            for (org.example.filters.FilterStrategy filter : batch.getFilters()) {
                processed = filter.apply(processed);
            }

            // Теперь processed содержит маску. Накладываем её на оригинальное изображение
            MaskOverlayFilter overlayFilter = new MaskOverlayFilter(processed);
            Mat result = overlayFilter.apply(originalImage);

            // Освобождаем ресурсы
            overlayFilter.release();
            processed.release();
            originalImage.release();

            // Заменяем изображение в элементе на результат
            item.getImage().release();

            originalImage = item.getOriginalImage().clone();
            Mat lowerMask = new Mat();
            Mat upperMask = new Mat();
            Imgproc.threshold(originalImage, lowerMask, minBrightness, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(originalImage, upperMask, maxBrightness, 255, Imgproc.THRESH_BINARY_INV);
            Mat result2 = new Mat();
            Core.bitwise_and(lowerMask, upperMask, result2);
            Mat result3 = new Mat();
            Core.bitwise_and(originalImage, result2, result3);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(3, 3));

            Mat closeMask = new Mat();
            Mat result4 = new Mat();
            Imgproc.morphologyEx(result3, closeMask, Imgproc.MORPH_CLOSE, kernel);
            Core.bitwise_and(result3, closeMask, result4);

            Mat openMask = new Mat();
            Mat result5 = new Mat();
            Imgproc.morphologyEx(result4, openMask, Imgproc.MORPH_OPEN, kernel);
            Core.bitwise_and(result4, closeMask, result5);
            item.setImage(result5);
        }

        System.out.println("\n========== ЗАВЕРШЕНИЕ ОБРАБОТКИ КОНТУРОВ ==========\n");

        activeContourBatch = batch;
        activeContourGroupKey = selectedGroup;
        contourApplied = true;
        contourCanRedo = false;

        refreshSeriesBrowser();
        selectFirstVisibleItem();
        updateStatus("Фильтр '" + batch.getName() + "' применен к " + itemsInGroup.size() + " снимкам (порог: [" + minBrightness + ", " + maxBrightness + "])");
    }


    private List<SeriesImageItem> findItemsInGroup(String groupKey) {
        List<SeriesImageItem> itemsInGroup = new ArrayList<>();
        for (SeriesImageItem item : seriesModel.getAllItems()) {
            if ((groupKey == null && item.getGroupKey() == null) ||
                    (groupKey != null && groupKey.equals(item.getGroupKey()))) {
                itemsInGroup.add(item);
            }
        }
        return itemsInGroup;
    }

    private void clearContourFilterState() {
        activeContourBatch = null;
        activeContourGroupKey = null;
        contourApplied = false;
        contourCanRedo = false;
    }

    private boolean undoContourFilter() {
        if (!contourApplied || activeContourBatch == null || activeContourGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeContourGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearContourFilterState();
            return false;
        }

        activeContourBatch.removeFromItems(itemsInGroup);
        contourApplied = false;
        contourCanRedo = true;
        refreshSeriesBrowser();
        selectFirstVisibleItem();
        updateStatus("Фильтр группы отменен");
        return true;
    }

    private boolean redoContourFilter() {
        if (contourApplied || !contourCanRedo || activeContourBatch == null || activeContourGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeContourGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearContourFilterState();
            return false;
        }

        activeContourBatch.applyToItems(itemsInGroup);
        contourApplied = true;
        contourCanRedo = false;
        refreshSeriesBrowser();
        selectFirstVisibleItem();
        updateStatus("Фильтр группы восстановлен");
        return true;
    }

    private boolean resetContourFilter() {
        if (activeContourBatch == null || activeContourGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeContourGroupKey);
        if (!itemsInGroup.isEmpty()) {
            activeContourBatch.removeFromItems(itemsInGroup);
        }

        clearContourFilterState();
        refreshSeriesBrowser();
        selectFirstVisibleItem();
        updateStatus("Фильтр группы сброшен");
        return true;
    }
}