package org.example.filters;

import org.example.model.SeriesImageItem;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

public class FilterBatch {
    private final List<FilterStrategy> filters = new ArrayList<>();
    private final String name;

    public FilterBatch(String name) {
        this.name = name;
    }

    public void addFilter(FilterStrategy filter) {
        if (filter != null) {
            filters.add(filter);
        }
    }

    public String getName() {
        return name;
    }

    public List<FilterStrategy> getFilters() {
        return new ArrayList<>(filters);
    }

    public void applyToItems(List<SeriesImageItem> items) {
        if (items == null || items.isEmpty() || filters.isEmpty()) {
            return;
        }

        for (SeriesImageItem item : items) {
            Mat processed = item.getImage().clone();
            for (FilterStrategy filter : filters) {
                processed = filter.apply(processed);
            }
            item.setImage(processed);
        }
    }

    public void removeFromItems(List<SeriesImageItem> items) {
        // Reset items to their original state
        if (items != null) {
            for (SeriesImageItem item : items) {
                if (item.hasOriginalImage()) {
                    item.resetToOriginal();
                }
            }
        }
    }
}