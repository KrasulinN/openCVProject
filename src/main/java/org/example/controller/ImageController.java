package org.example.controller;

import org.example.filters.*;
import org.example.model.ImageModel;
import org.example.model.ImageSeriesModel;
import org.example.model.SeriesImageItem;
import org.example.model.SeriesTreeNodeData;
import org.example.utils.DicomImageLoader;
import org.example.view.MainView;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
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

    // Состояние для пакетной обработки группой
    private FilterBatch activeGroupFilterBatch;
    private String activeGroupFilterKey;
    private boolean groupFilterApplied;
    private boolean groupFilterCanRedo;

    // Состояние для ROI-фильтра (многоугольник)
    private List<Point> roiPolygonPoints;
    private String roiGroupKey;
    private boolean roiApplied;
    private boolean roiCanRedo;
    private PolygonRoiFilter.RoiMode roiMode =
            PolygonRoiFilter.RoiMode.KEEP_INSIDE;

    private PolygonRoiFilter.RoiMode appliedRoiMode =
            PolygonRoiFilter.RoiMode.KEEP_INSIDE;

    public void setRoiMode(PolygonRoiFilter.RoiMode roiMode) {
        this.roiMode = roiMode;
    }
    public ImageController(ImageModel model, ImageSeriesModel seriesModel, MainView view) {
        this.model = model;
        this.seriesModel = seriesModel;
        this.view = view;

        clearGroupFilterState();
        clearRoiState();

        this.view.setController(this);
        this.view.setGroupFilterListener(this::onGroupFilterChanged);
        this.view.setSeriesSelectionListener(this::onSeriesSelectionChanged);
        this.view.setRoiCompleteListener(this::onRoiComplete);
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
        if (undoRoiFilter()) {
            return;
        }
        if (undoGroupFilter()) {
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
        if (redoRoiFilter()) {
            return;
        }
        if (redoGroupFilter()) {
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
        if (resetRoiFilter()) {
            return;
        }
        if (resetGroupFilter()) {
            return;
        }

        if (model.hasImage()) {
            model.resetToOriginal();
            syncSelectedItemImage();
            view.displayImage(model.getCurrentImage());
            updateStatus("Сброшено к оригиналу");
        }
    }

    public void loadSeries(List<Path> paths) {
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
            clearAllFilterStates();
            refreshSeriesBrowser();
            view.displayImage(null);
            // Не показываем ошибку при автозагрузке, если файлы не подходят
            System.out.println("Не удалось загрузить ни один снимок из " + errors.size() + " файлов.");
            return;
        }

        String currentFilter = seriesModel.getGroupFilter();
        seriesModel.replaceItems(loadedItems);
        clearAllFilterStates();
        if (currentFilter != null && seriesModel.findFirstItemInGroup(currentFilter) == null) {
            seriesModel.setGroupFilter(null);
        }

        refreshSeriesBrowser();
        selectFirstVisibleItem();

        if (!errors.isEmpty() && errors.size() < loadedItems.size()) {
            // Показываем ошибку только если часть файлов загрузилась
            view.showError("Некоторые файлы не удалось загрузить:\n" + joinLines(errors));
        } else if (!errors.isEmpty()) {
            System.out.println("Некоторые файлы не загружены: " + errors.size() + " шт.");
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
        double[] pixelSpacing = seriesInfo != null
                ? seriesInfo.getPixelSpacing()
                : null;

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

        return new SeriesImageItem(path, groupKey, slicePositionMm, pixelSpacing, image);
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

        // Сохраняем текущий выбранный элемент для восстановления после обработки
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        int minBrightness = view.getMinBrightnessThreshold();
        int maxBrightness = view.getMaxBrightnessThreshold();
        boolean removeNoise = view.isNoiseRemovalEnabled();
        boolean fillGaps = view.isGapFillEnabled();

        if (minBrightness < 0 || minBrightness > 255 || maxBrightness < 0 || maxBrightness > 255) {
            view.showError("Значения яркости должны быть в диапазоне 0-255");
            return;
        }
        if (minBrightness > maxBrightness) {
            view.showError("Минимальное значение яркости не может быть больше максимального");
            return;
        }

        System.out.println("\n========== НАЧАЛО ОБРАБОТКИ ПОРОГОВЫМ ФИЛЬТРОМ ==========");
        System.out.println("Группа: " + selectedGroup);
        System.out.println("Порог яркости: [" + minBrightness + ", " + maxBrightness + "]");
        String morphologyText;
        if (removeNoise && fillGaps) {
            morphologyText = "Закрытие + Открытие";
        } else if (fillGaps) {
            morphologyText = "Закрытие";
        } else if (removeNoise) {
            morphologyText = "Открытие";
        } else {
            morphologyText = "нет";
        }
        System.out.println("Морфология: " + morphologyText);
        System.out.println("Количество снимков: " + itemsInGroup.size());

        // Создаём пакет фильтров: пороговый фильтр с выбранной морфологией
        FilterBatch batch = model.createFilterBatch("Порог + морфология для " + selectedGroup);

        ThresholdFilter thresholdFilter = new ThresholdFilter(minBrightness, maxBrightness, removeNoise, fillGaps);
        thresholdFilter.setDebugMode(true);
        batch.addFilter(thresholdFilter);

        // Применяем фильтры ко всем снимкам в группе
        for (int i = 0; i < itemsInGroup.size(); i++) {
            SeriesImageItem item = itemsInGroup.get(i);
            System.out.println("\n--- Обработка снимка " + (i + 1) + "/" + itemsInGroup.size() +
                    ": " + item.getFileName() + " ---");

            Mat originalImage = item.getOriginalImage().clone();
            Mat processed = originalImage.clone();

            // Применяем все фильтры из пакета последовательно
            for (FilterStrategy filter : batch.getFilters()) {
                Mat filtered = filter.apply(processed);
                processed.release();
                processed = filtered;
            }

            // Сохраняем результат
            item.setImage(processed);
            originalImage.release();
        }

        System.out.println("\n========== ЗАВЕРШЕНИЕ ОБРАБОТКИ ПОРОГОВЫМ ФИЛЬТРОМ ==========\n");

        activeGroupFilterBatch = batch;
        activeGroupFilterKey = selectedGroup;
        groupFilterApplied = true;
        groupFilterCanRedo = false;

        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, selectedGroup);
        updateStatus("Пороговый фильтр применен к " + itemsInGroup.size() + " снимкам (порог: [" + minBrightness + ", " + maxBrightness + "], морфология: " + morphologyText + ")");
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

    private void clearGroupFilterState() {
        activeGroupFilterBatch = null;
        activeGroupFilterKey = null;
        groupFilterApplied = false;
        groupFilterCanRedo = false;
    }

    /**
     * Восстанавливает выделение слайда после обработки.
     * Если ранее выбранный элемент всё ещё видим в группе, выбирает его.
     * Иначе выбирает первый видимый элемент в группе.
     */
    private void restoreSelectionAfterProcessing(SeriesImageItem previouslySelectedItem, String selectedGroup) {
        if (previouslySelectedItem != null &&
                selectedGroup.equals(previouslySelectedItem.getGroupKey())) {
            // Проверяем, что элемент всё ещё в группе и видим
            List<SeriesImageItem> itemsInGroup = findItemsInGroup(selectedGroup);
            for (SeriesImageItem item : itemsInGroup) {
                if (item.equals(previouslySelectedItem)) {
                    // Элемент найден, восстанавливаем выделение
                    seriesModel.setSelectedItem(item);
                    view.selectSeriesItem(item);

                    // Обновляем изображение в модели и отображении
                    model.loadImage(item.getImage());
                    view.displayImage(model.getCurrentImage());
                    return;
                }
            }
        }

        // Если не нашли ранее выбранный элемент, выбираем первый видимый
        selectFirstVisibleItem();
    }

    private void clearRoiState() {
        roiPolygonPoints = null;
        roiGroupKey = null;
        roiApplied = false;
        roiCanRedo = false;
    }

    private void clearAllFilterStates() {
        clearGroupFilterState();
        clearRoiState();
    }

    private boolean undoGroupFilter() {
        if (!groupFilterApplied || activeGroupFilterBatch == null || activeGroupFilterKey == null) {
            return false;
        }

        // Сохраняем текущий выбранный элемент для восстановления после отмены
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupFilterKey);
        if (itemsInGroup.isEmpty()) {
            clearGroupFilterState();
            return false;
        }

        activeGroupFilterBatch.removeFromItems(itemsInGroup);
        groupFilterApplied = false;
        groupFilterCanRedo = true;
        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, activeGroupFilterKey);
        updateStatus("Фильтр группы отменен");
        return true;
    }

    private boolean redoGroupFilter() {
        if (groupFilterApplied || !groupFilterCanRedo || activeGroupFilterBatch == null || activeGroupFilterKey == null) {
            return false;
        }

        // Сохраняем текущий выбранный элемент для восстановления после повтора
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupFilterKey);
        if (itemsInGroup.isEmpty()) {
            clearGroupFilterState();
            return false;
        }

        activeGroupFilterBatch.applyToItems(itemsInGroup);
        groupFilterApplied = true;
        groupFilterCanRedo = false;
        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, activeGroupFilterKey);
        updateStatus("Фильтр группы восстановлен");
        return true;
    }

    private boolean resetGroupFilter() {
        if (activeGroupFilterBatch == null || activeGroupFilterKey == null) {
            return false;
        }

        // Сохраняем текущий выбранный элемент для восстановления после сброса
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupFilterKey);
        if (!itemsInGroup.isEmpty()) {
            activeGroupFilterBatch.removeFromItems(itemsInGroup);
        }

        clearGroupFilterState();
        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, activeGroupFilterKey);
        updateStatus("Фильтр группы сброшен");
        return true;
    }

    private boolean undoRoiFilter() {
        if (!roiApplied || roiPolygonPoints == null || roiGroupKey == null) {
            return false;
        }

        // Сохраняем текущий выбранный элемент для восстановления после отмены
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(roiGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearRoiState();
            return false;
        }

        // Отменяем ROI-фильтр: восстанавливаем состояние до применения ROI
        // Т.е. возвращаемся к состоянию после порогового фильтра (или к оригиналу, если пороговый не применялся)
        for (SeriesImageItem item : itemsInGroup) {
            // Если есть активный групповой фильтр, то восстанавливаем его результат
            // Иначе сбрасываем к оригиналу
            if (groupFilterApplied && activeGroupFilterBatch != null && activeGroupFilterKey != null) {
                // Восстанавливаем состояние после порогового фильтра
                // Для этого нужно переapplyить только пороговый фильтр
                Mat originalImage = item.getOriginalImage().clone();
                Mat processed = originalImage.clone();

                // Применяем только фильтры из batch (пороговый + морфология)
                for (FilterStrategy filter : activeGroupFilterBatch.getFilters()) {
                    Mat filtered = filter.apply(processed);
                    processed.release();
                    processed = filtered;
                }

                item.setImage(processed);
                originalImage.release();
            } else {
                // Если пороговый фильтр не применён, сбрасываем к оригиналу
                if (item.hasOriginalImage()) {
                    item.resetToOriginal();
                }
            }
        }

        roiApplied = false;
        roiCanRedo = true;
        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, roiGroupKey);
        updateStatus("ROI-фильтр отменен");
        return true;
    }

    private boolean redoRoiFilter() {
        if (roiApplied || !roiCanRedo || roiPolygonPoints == null || roiGroupKey == null) {
            return false;
        }

        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(roiGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearRoiState();
            return false;
        }

        PolygonRoiFilter roiFilter =
                new PolygonRoiFilter(
                        roiPolygonPoints,
                        appliedRoiMode
                );

        for (SeriesImageItem item : itemsInGroup) {

            Mat currentImage = item.getImage().clone();

            Mat result = roiFilter.apply(currentImage);

            item.setImage(result);

            currentImage.release();
        }

        roiApplied = true;
        roiCanRedo = false;

        refreshSeriesBrowser();

        restoreSelectionAfterProcessing(previouslySelectedItem, roiGroupKey);

        updateStatus("ROI-фильтр восстановлен");

        return true;
    }

    public void onRoiComplete(List<Point> polygonPoints, String groupKey) {

        if (polygonPoints == null || polygonPoints.size() < 3) {
            view.showError("Многоугольник должен иметь минимум 3 вершины");
            return;
        }

        this.roiMode = view.getSelectedRoiMode();

        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(groupKey);

        if (itemsInGroup.isEmpty()) {
            view.showError("В группе нет снимков");
            return;
        }

        System.out.println("\n========== НАЧАЛО ОБРАБОТКИ ROI-ФИЛЬТРОМ ==========");
        System.out.println("Группа: " + groupKey);
        System.out.println("Вершин многоугольника: " + polygonPoints.size());
        System.out.println("Режим ROI: " + roiMode);
        System.out.println("Количество снимков: " + itemsInGroup.size());

        PolygonRoiFilter roiFilter =
                new PolygonRoiFilter(
                        polygonPoints,
                        roiMode
                );

        roiFilter.setDebugMode(true);

        for (int i = 0; i < itemsInGroup.size(); i++) {

            SeriesImageItem item = itemsInGroup.get(i);

            System.out.println(
                    "\n--- Обработка снимка "
                            + (i + 1)
                            + "/"
                            + itemsInGroup.size()
                            + ": "
                            + item.getFileName()
                            + " ---"
            );

            Mat currentImage = item.getImage().clone();

            Mat result = roiFilter.apply(currentImage);

            item.setImage(result);

            currentImage.release();
        }

        System.out.println("\n========== ЗАВЕРШЕНИЕ ОБРАБОТКИ ROI-ФИЛЬТРОМ ==========\n");

        this.roiPolygonPoints = new ArrayList<>(polygonPoints);
        this.roiGroupKey = groupKey;
        this.roiApplied = true;
        this.roiCanRedo = false;
        this.appliedRoiMode = roiMode;

        refreshSeriesBrowser();

        restoreSelectionAfterProcessing(previouslySelectedItem, groupKey);

        updateStatus(
                "ROI-фильтр применен к "
                        + itemsInGroup.size()
                        + " снимкам ("
                        + polygonPoints.size()
                        + " вершин, режим: "
                        + roiMode
                        + ")"
        );
    }

    private boolean resetRoiFilter() {
        if (roiPolygonPoints == null || roiGroupKey == null) {
            return false;
        }

        // Сохраняем текущий выбранный элемент для восстановления после сброса
        SeriesImageItem previouslySelectedItem = seriesModel.getSelectedItem();

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(roiGroupKey);
        if (!itemsInGroup.isEmpty()) {
            // Сбрасываем ROI-фильтр: восстанавливаем состояние до применения ROI
            // Т.е. возвращаемся к состоянию после порогового фильтра (или к оригиналу, если пороговый не применялся)
            for (SeriesImageItem item : itemsInGroup) {
                if (groupFilterApplied && activeGroupFilterBatch != null && activeGroupFilterKey != null) {
                    // Восстанавливаем состояние после порогового фильтра
                    Mat originalImage = item.getOriginalImage().clone();
                    Mat processed = originalImage.clone();

                    for (FilterStrategy filter : activeGroupFilterBatch.getFilters()) {
                        Mat filtered = filter.apply(processed);
                        processed.release();
                        processed = filtered;
                    }

                    item.setImage(processed);
                    originalImage.release();
                } else {
                    // Если пороговый фильтр не применён, сбрасываем к оригиналу
                    if (item.hasOriginalImage()) {
                        item.resetToOriginal();
                    }
                }
            }
        }

        clearRoiState();
        refreshSeriesBrowser();

        // Восстанавливаем выделение на том же слайсе или ближайшем видимом
        restoreSelectionAfterProcessing(previouslySelectedItem, roiGroupKey);
        updateStatus("ROI-фильтр сброшен");
        return true;
    }

    public void onShow3DViewer() {
        String selectedGroup = view.getSelectedGroupFilter();
        if (selectedGroup == null || selectedGroup.isEmpty() || selectedGroup.equals("Все группы")) {
            view.showError("Выберите группу снимков для 3D визуализации");
            return;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(selectedGroup);
        if (itemsInGroup.isEmpty()) {
            view.showError("В группе нет снимков");
            return;
        }

        System.out.println("\n========== ЗАПУСК 3D/MPR РЕКОНСТРУКЦИИ ==========");
        System.out.println("Группа: " + selectedGroup);
        System.out.println("Количество слайсов: " + itemsInGroup.size());

        // Проверяем, есть ли у слайсов данные о позиции
        int slicesWithPosition = 0;
        for (SeriesImageItem item : itemsInGroup) {
            if (Double.isFinite(item.getSlicePositionMm())) {
                slicesWithPosition++;
            }
        }
        System.out.println("Слайсы с позицией: " + slicesWithPosition + "/" + itemsInGroup.size());

        // Запускаем 3D/MPR viewer
        org.example.view.PointCloud3DViewer.showViewer(itemsInGroup, "3D/MPR реконструкция - " + selectedGroup);

        updateStatus("3D/MPR реконструкция запущена для " + itemsInGroup.size() + " слайсов");
    }
}
