package org.example.model;

import org.opencv.core.Mat;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ImageSeriesModel {
    private static final String ALL_GROUPS = "Все группы";
    private static final String OTHER_GROUP = "OTHER";

    private final List<SeriesImageItem> items = new ArrayList<>();
    private String activeGroupFilter = null;
    private final boolean sortAscending = true;
    private SeriesImageItem selectedItem;

    public void replaceItems(List<SeriesImageItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        selectedItem = null;
    }

    public void clear() {
        items.clear();
        selectedItem = null;
        activeGroupFilter = null;
    }

    public void setGroupFilter(String groupKey) {
        if (groupKey == null || groupKey.trim().isEmpty() || ALL_GROUPS.equalsIgnoreCase(groupKey)) {
            activeGroupFilter = null;
        } else {
            activeGroupFilter = normalizeGroupKey(groupKey);
        }
    }

    public String getGroupFilter() {
        return activeGroupFilter;
    }

    public List<String> getAvailableGroupKeys() {
        Map<String, List<SeriesImageItem>> grouped = groupVisibleItems(false);
        List<String> groupKeys = new ArrayList<>(grouped.keySet());
        Collections.sort(groupKeys, groupComparator());
        return groupKeys;
    }

    public List<SeriesImageItem> getVisibleItems() {
        List<SeriesImageItem> result = new ArrayList<>();
        for (List<SeriesImageItem> groupItems : groupVisibleItems(true).values()) {
            result.addAll(groupItems);
        }
        return result;
    }

    public List<SeriesImageItem> getAllItems() {
        return new ArrayList<>(items);
    }

    public SeriesImageItem findFirstVisibleItem() {
        Map<String, List<SeriesImageItem>> grouped = groupVisibleItems(true);
        for (List<SeriesImageItem> groupItems : grouped.values()) {
            if (!groupItems.isEmpty()) {
                return groupItems.get(0);
            }
        }
        return null;
    }

    public SeriesImageItem findFirstItemInGroup(String groupKey) {
        if (groupKey == null) {
            return null;
        }

        String normalized = normalizeGroupKey(groupKey);
        for (SeriesImageItem item : sortedItems(items)) {
            if (normalized.equals(item.getGroupKey())) {
                return item;
            }
        }
        return null;
    }

    public void setSelectedItem(SeriesImageItem item) {
        selectedItem = item;
    }

    public void updateSelectedItemImage(Mat image) {
        if (selectedItem != null) {
            selectedItem.setImage(image);
        }
    }

    public DefaultTreeModel buildTreeModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Снимки");
        Map<String, List<SeriesImageItem>> grouped = groupVisibleItems(true);

        for (Map.Entry<String, List<SeriesImageItem>> entry : grouped.entrySet()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(
                    SeriesTreeNodeData.group(entry.getKey(), entry.getValue().size()));

            for (SeriesImageItem item : entry.getValue()) {
                groupNode.add(new DefaultMutableTreeNode(SeriesTreeNodeData.image(item)));
            }

            root.add(groupNode);
        }

        return new DefaultTreeModel(root);
    }

    private Map<String, List<SeriesImageItem>> groupVisibleItems(boolean applyCurrentSort) {
        List<SeriesImageItem> source = new ArrayList<>();
        for (SeriesImageItem item : items) {
            if (activeGroupFilter == null || activeGroupFilter.equals(item.getGroupKey())) {
                source.add(item);
            }
        }

        List<SeriesImageItem> sortedSource = applyCurrentSort ? sortedItems(source) : source;
        Map<String, List<SeriesImageItem>> grouped = new LinkedHashMap<>();

        for (SeriesImageItem item : sortedSource) {
            List<SeriesImageItem> groupItems = grouped.get(item.getGroupKey());
            if (groupItems == null) {
                groupItems = new ArrayList<>();
                grouped.put(item.getGroupKey(), groupItems);
            }
            groupItems.add(item);
        }

        if (!applyCurrentSort) {
            return grouped;
        }

        List<String> orderedGroups = new ArrayList<>(grouped.keySet());
        Collections.sort(orderedGroups, groupComparator());

        Map<String, List<SeriesImageItem>> ordered = new LinkedHashMap<>();
        for (String groupKey : orderedGroups) {
            ordered.put(groupKey, grouped.get(groupKey));
        }
        return ordered;
    }

    private List<SeriesImageItem> sortedItems(List<SeriesImageItem> source) {
        List<SeriesImageItem> sorted = new ArrayList<>(source);
        Collections.sort(sorted, new Comparator<SeriesImageItem>() {
            @Override
            public int compare(SeriesImageItem left, SeriesImageItem right) {
                int groupComparison = groupComparator().compare(left.getGroupKey(), right.getGroupKey());
                if (groupComparison != 0) {
                    return groupComparison;
                }

                int sliceComparison = Double.compare(left.getSlicePositionMm(), right.getSlicePositionMm());
                if (!sortAscending) {
                    sliceComparison = -sliceComparison;
                }
                if (sliceComparison != 0) {
                    return sliceComparison;
                }

                int fileComparison = left.getFileName().compareToIgnoreCase(right.getFileName());
                if (!sortAscending) {
                    fileComparison = -fileComparison;
                }
                if (fileComparison != 0) {
                    return fileComparison;
                }

                return left.getPath().toString().compareToIgnoreCase(right.getPath().toString());
            }
        });
        return sorted;
    }

    private Comparator<String> groupComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int leftPriority = groupPriority(left);
                int rightPriority = groupPriority(right);

                if (leftPriority != rightPriority) {
                    return sortAscending ? Integer.compare(leftPriority, rightPriority)
                            : Integer.compare(rightPriority, leftPriority);
                }

                int comparison = left.compareToIgnoreCase(right);
                return sortAscending ? comparison : -comparison;
            }
        };
    }

    private int groupPriority(String groupKey) {
        if (groupKey != null && groupKey.toUpperCase(Locale.ROOT).startsWith("CT")) {
            return 0;
        }
        if (groupKey != null && groupKey.toUpperCase(Locale.ROOT).startsWith("MR")) {
            return 1;
        }
        if (OTHER_GROUP.equalsIgnoreCase(groupKey)) {
            return 2;
        }
        return 3;
    }

    public static String extractGroupKey(Path path) {
        if (path == null || path.getFileName() == null) {
            return OTHER_GROUP;
        }

        String fileName = path.getFileName().toString().trim();
        if (fileName.isEmpty()) {
            return OTHER_GROUP;
        }

        int delimiterIndex = -1;
        char[] delimiters = new char[]{'.', '_', '-', ' '};
        for (char delimiter : delimiters) {
            int index = fileName.indexOf(delimiter);
            if (index > 0 && (delimiterIndex < 0 || index < delimiterIndex)) {
                delimiterIndex = index;
            }
        }

        String group = delimiterIndex > 0 ? fileName.substring(0, delimiterIndex) : fileName;
        return normalizeGroupKey(group);
    }

    private static String normalizeGroupKey(String groupKey) {
        if (groupKey == null || groupKey.trim().isEmpty()) {
            return OTHER_GROUP;
        }
        return groupKey.trim().toUpperCase(Locale.ROOT);
    }
}
