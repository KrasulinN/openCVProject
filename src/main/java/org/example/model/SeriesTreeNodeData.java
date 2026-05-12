package org.example.model;

public class SeriesTreeNodeData {
    public enum Type {
        GROUP,
        IMAGE
    }

    private final Type type;
    private final String label;
    private final String groupKey;
    private final SeriesImageItem item;

    private SeriesTreeNodeData(Type type, String label, String groupKey, SeriesImageItem item) {
        this.type = type;
        this.label = label;
        this.groupKey = groupKey;
        this.item = item;
    }

    public static SeriesTreeNodeData group(String groupKey, int itemCount) {
        return new SeriesTreeNodeData(Type.GROUP, groupKey + " (" + itemCount + ")", groupKey, null);
    }

    public static SeriesTreeNodeData image(SeriesImageItem item) {
        return new SeriesTreeNodeData(Type.IMAGE, item.getFileName(), item.getGroupKey(), item);
    }

    public Type getType() {
        return type;
    }

    public boolean isGroup() {
        return type == Type.GROUP;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public SeriesImageItem getItem() {
        return item;
    }

    @Override
    public String toString() {
        return label;
    }
}
