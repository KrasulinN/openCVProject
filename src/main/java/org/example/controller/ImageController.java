package org.example.controller;

import org.example.filters.FilterBatch;
import org.example.filters.ContourFilter;
import org.example.filters.FilterStrategy;
import org.example.filters.ThresholdFilter;
import org.example.model.ImageModel;
import org.example.model.ImageSeriesModel;
import org.example.model.SeriesImageItem;
import org.example.model.SeriesTreeNodeData;
import org.example.utils.DicomImageLoader;
import org.example.view.MainView;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

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
    private FilterBatch activeGroupBatch;
    private String activeGroupKey;
    private boolean groupFilterApplied;
    private boolean groupFilterCanRedo;
    private String activeGroupFilterName;

    public ImageController(ImageModel model, ImageSeriesModel seriesModel, MainView view) {
        this.model = model;
        this.seriesModel = seriesModel;
        this.view = view;
        this.activeGroupBatch = null;
        this.activeGroupKey = null;
        this.groupFilterApplied = false;
        this.groupFilterCanRedo = false;
        this.activeGroupFilterName = null;

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
            clearGroupFilterState();
            refreshSeriesBrowser();
            view.displayImage(null);
            view.showError("Не удалось загрузить ни один снимок.");
            return;
        }

        String currentFilter = seriesModel.getGroupFilter();
        seriesModel.replaceItems(loadedItems);
        clearGroupFilterState();
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
        applyFilterToSelectedGroup("Поиск контуров", new ContourFilter(), "contour");
    }

    public void onContourFilterToggled(ActionEvent e) {
        if (view.isContourFilterSelected()) {
            applyFilterToSelectedGroup("Поиск контуров", new ContourFilter(), "contour");
        } else {
            resetContourFilter();
        }
    }

    public void onThresholdFilterToggled(ActionEvent e) {
        if (view.isThresholdFilterSelected()) {
            applyThresholdToSelectedGroup(true);
        } else {
            resetThresholdFilter();
        }
    }

    public void onThresholdSettingsChanged() {
        if (view.isThresholdFilterSelected()) {
            applyThresholdToSelectedGroup(false);
        }
    }

    private void applyThresholdToSelectedGroup(boolean showValidationErrors) {
        applyFilterToSelectedGroup(
                "Пороговая фильтрация",
                new ThresholdFilter(
                        view.getThresholdValue(),
                        view.getThresholdMaxValue(),
                        view.getThresholdType(),
                        view.getThresholdOpacity()
                ),
                "threshold",
                showValidationErrors
        );
    }

    private void applyFilterToSelectedGroup(String filterName, FilterStrategy filter, String filterKey) {
        applyFilterToSelectedGroup(filterName, filter, filterKey, true);
    }

    private void applyFilterToSelectedGroup(String filterName, FilterStrategy filter, String filterKey,
                                            boolean showValidationErrors) {
        String selectedGroup = view.getSelectedGroupFilter();
        if (selectedGroup == null || selectedGroup.isEmpty() || selectedGroup.equals("Все группы")) {
            if (showValidationErrors) {
                view.showError("Выберите группу снимков");
            }
            if ("threshold".equals(filterKey)) {
                view.setThresholdFilterApplied(false);
            }
            if ("contour".equals(filterKey)) {
                view.setContourFilterApplied(false);
            }
            return;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(selectedGroup);
        if (itemsInGroup.isEmpty()) {
            if (showValidationErrors) {
                view.showError("В группе нет снимков");
            }
            if ("threshold".equals(filterKey)) {
                view.setThresholdFilterApplied(false);
            }
            if ("contour".equals(filterKey)) {
                view.setContourFilterApplied(false);
            }
            return;
        }

        SeriesImageItem selectedItem = seriesModel.getSelectedItem();
        resetActiveGroupFilter();

        FilterBatch batch = model.createFilterBatch(filterName + " для " + selectedGroup);
        batch.addFilter(filter);
        batch.applyToItems(itemsInGroup);

        activeGroupBatch = batch;
        activeGroupKey = selectedGroup;
        groupFilterApplied = true;
        groupFilterCanRedo = false;
        activeGroupFilterName = filterKey;
        updateFilterAppliedIndicators();

        refreshSeriesBrowserKeepingSelection(selectedItem);
        updateStatus("Фильтр '" + batch.getName() + "' применен к " + itemsInGroup.size() + " снимкам");
    }

    private void resetThresholdFilter() {
        if (!"threshold".equals(activeGroupFilterName)) {
            view.setThresholdFilterApplied(false);
            return;
        }

        resetGroupFilter();
    }

    private void resetContourFilter() {
        if (!"contour".equals(activeGroupFilterName)) {
            view.setContourFilterApplied(false);
            return;
        }

        resetGroupFilter();
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
        activeGroupBatch = null;
        activeGroupKey = null;
        groupFilterApplied = false;
        groupFilterCanRedo = false;
        activeGroupFilterName = null;
        view.clearFilterAppliedIndicators();
    }

    private void resetActiveGroupFilter() {
        if (activeGroupBatch == null || activeGroupKey == null) {
            return;
        }

        List<SeriesImageItem> activeItems = findItemsInGroup(activeGroupKey);
        if (!activeItems.isEmpty()) {
            activeGroupBatch.removeFromItems(activeItems);
        }
    }

    private boolean undoGroupFilter() {
        if (!groupFilterApplied || activeGroupBatch == null || activeGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearGroupFilterState();
            return false;
        }

        SeriesImageItem selectedItem = seriesModel.getSelectedItem();
        activeGroupBatch.removeFromItems(itemsInGroup);
        groupFilterApplied = false;
        groupFilterCanRedo = true;
        updateFilterAppliedIndicators();
        refreshSeriesBrowserKeepingSelection(selectedItem);
        updateStatus("Фильтр группы отменен");
        return true;
    }

    private boolean redoGroupFilter() {
        if (groupFilterApplied || !groupFilterCanRedo || activeGroupBatch == null || activeGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupKey);
        if (itemsInGroup.isEmpty()) {
            clearGroupFilterState();
            return false;
        }

        SeriesImageItem selectedItem = seriesModel.getSelectedItem();
        activeGroupBatch.applyToItems(itemsInGroup);
        groupFilterApplied = true;
        groupFilterCanRedo = false;
        updateFilterAppliedIndicators();
        refreshSeriesBrowserKeepingSelection(selectedItem);
        updateStatus("Фильтр группы восстановлен");
        return true;
    }

    private boolean resetGroupFilter() {
        if (activeGroupBatch == null || activeGroupKey == null) {
            return false;
        }

        List<SeriesImageItem> itemsInGroup = findItemsInGroup(activeGroupKey);
        if (!itemsInGroup.isEmpty()) {
            activeGroupBatch.removeFromItems(itemsInGroup);
        }

        SeriesImageItem selectedItem = seriesModel.getSelectedItem();
        clearGroupFilterState();
        refreshSeriesBrowserKeepingSelection(selectedItem);
        updateStatus("Фильтр группы сброшен");
        return true;
    }

    private void refreshSeriesBrowserKeepingSelection(SeriesImageItem selectedItem) {
        refreshSeriesBrowser();
        if (selectedItem != null && seriesModel.isVisible(selectedItem) && view.selectSeriesItem(selectedItem)) {
            return;
        }
        selectFirstVisibleItem();
    }

    private void updateFilterAppliedIndicators() {
        view.setThresholdFilterApplied(groupFilterApplied && "threshold".equals(activeGroupFilterName));
        view.setContourFilterApplied(groupFilterApplied && "contour".equals(activeGroupFilterName));
    }
}
