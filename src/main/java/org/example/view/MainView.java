package org.example.view;

import org.example.controller.ImageController;
import org.example.model.SeriesTreeNodeData;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MainView extends JFrame {
    private static final String ALL_GROUPS_LABEL = "Все группы";

    private final ImageCanvas imagePanel;
    private final JTree seriesTree;
    private final JComboBox<String> groupFilterCombo;
    private final JLabel statusLabel;

    private JMenuItem openFilesMenuItem;
    private JMenuItem openFolderMenuItem;
    private JMenuItem undoMenuItem;
    private JMenuItem redoMenuItem;
    private JMenuItem resetMenuItem;
    private JMenuItem contourInGroupMenuItem;

    private DefaultTreeModel seriesTreeModel;
    private boolean updatingGroupFilter;
    private Consumer<String> groupFilterListener;
    private Consumer<SeriesTreeNodeData> seriesSelectionListener;

    public MainView() {
        setTitle("Medical Image Editor");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 760);
        setLocationRelativeTo(null);

        imagePanel = new ImageCanvas();
        seriesTree = new JTree(createEmptyTreeModel());
        groupFilterCombo = new JComboBox<>();
        statusLabel = new JLabel("Готов к работе");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        initTree();
        initComponents();
        layoutComponents();
    }

    private DefaultTreeModel createEmptyTreeModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Снимки");
        return new DefaultTreeModel(root);
    }

    private void initTree() {
        seriesTree.setRootVisible(false);
        seriesTree.setShowsRootHandles(true);
        seriesTree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                          boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode) {
                    Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                    if (userObject instanceof SeriesTreeNodeData) {
                        setText(userObject.toString());
                    } else if (userObject != null) {
                        setText(userObject.toString());
                    }
                }
                return this;
            }
        });

        seriesTree.addTreeSelectionListener(e -> {
            if (seriesSelectionListener == null) {
                return;
            }

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) seriesTree.getLastSelectedPathComponent();
            if (node == null) {
                return;
            }

            Object userObject = node.getUserObject();
            if (userObject instanceof SeriesTreeNodeData) {
                seriesSelectionListener.accept((SeriesTreeNodeData) userObject);
            }
        });
    }

    private void initComponents() {
        createMenuBar();
        createToolbar();
        updateGroupFilterOptions(new ArrayList<String>(), null);
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("Файл");
        openFilesMenuItem = new JMenuItem("Открыть файлы...");
        openFilesMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
        openFolderMenuItem = new JMenuItem("Открыть папку...");

        JMenuItem exitItem = new JMenuItem("Выход");
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(openFilesMenuItem);
        fileMenu.add(openFolderMenuItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        JMenu editMenu = new JMenu("Правка");
        undoMenuItem = new JMenuItem("Отменить");
        undoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK));

        redoMenuItem = new JMenuItem("Повторить");
        redoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK));

        resetMenuItem = new JMenuItem("Сбросить изменения");

        editMenu.add(undoMenuItem);
        editMenu.add(redoMenuItem);
        editMenu.addSeparator();
        editMenu.add(resetMenuItem);

        JMenu processMenu = new JMenu("Обработка");
        contourInGroupMenuItem = new JMenuItem("Поиск контуров в группе");
        processMenu.add(contourInGroupMenuItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(processMenu);

        setJMenuBar(menuBar);
    }

    private void createToolbar() {
        groupFilterCombo.setPrototypeDisplayValue(ALL_GROUPS_LABEL);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        toolbar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        toolbar.add(groupFilterCombo);

        add(toolbar, BorderLayout.NORTH);
    }

    private void layoutComponents() {
        JPanel seriesPanel = new JPanel(new BorderLayout(0, 8));
        seriesPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        JLabel seriesLabel = new JLabel("Снимки");
        seriesLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        seriesPanel.add(seriesLabel, BorderLayout.NORTH);
        seriesPanel.add(new JScrollPane(seriesTree), BorderLayout.CENTER);
        seriesPanel.setPreferredSize(new Dimension(320, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, seriesPanel, new JScrollPane(imagePanel));
        splitPane.setResizeWeight(0.26);
        splitPane.setDividerLocation(320);

        add(splitPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    public void setController(ImageController controller) {
        openFilesMenuItem.addActionListener(controller::onOpenFiles);
        openFolderMenuItem.addActionListener(controller::onOpenFolder);
        undoMenuItem.addActionListener(controller::onUndo);
        redoMenuItem.addActionListener(controller::onRedo);
        resetMenuItem.addActionListener(controller::onReset);
        contourInGroupMenuItem.addActionListener(controller::onApplyContourToGroup);

        groupFilterCombo.addActionListener(e -> {
            if (!updatingGroupFilter && groupFilterListener != null) {
                Object selectedItem = groupFilterCombo.getSelectedItem();
                groupFilterListener.accept(selectedItem == null ? null : selectedItem.toString());
            }
        });

        setupKeyBindings(controller);
    }

    private void setupKeyBindings(ImageController controller) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undo");
        getRootPane().getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controller.onUndo(e);
            }
        });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "redo");
        getRootPane().getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controller.onRedo(e);
            }
        });
    }

    public void setSeriesTreeModel(DefaultTreeModel model) {
        this.seriesTreeModel = model;
        seriesTree.setModel(model);
    }

    public void expandAllSeriesGroups() {
        for (int row = 0; row < seriesTree.getRowCount(); row++) {
            seriesTree.expandRow(row);
        }
    }

    public void selectFirstSeriesLeaf() {
        if (seriesTreeModel == null) {
            return;
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) seriesTreeModel.getRoot();
        if (root == null || root.getChildCount() == 0) {
            seriesTree.clearSelection();
            return;
        }

        DefaultMutableTreeNode groupNode = (DefaultMutableTreeNode) root.getChildAt(0);
        if (groupNode.getChildCount() == 0) {
            seriesTree.setSelectionPath(new TreePath(groupNode.getPath()));
            return;
        }

        DefaultMutableTreeNode imageNode = (DefaultMutableTreeNode) groupNode.getChildAt(0);
        TreePath path = new TreePath(imageNode.getPath());
        seriesTree.setSelectionPath(path);
        seriesTree.scrollPathToVisible(path);
    }

    public void clearSeriesSelection() {
        seriesTree.clearSelection();
    }

    public void updateGroupFilterOptions(List<String> groupKeys, String selectedGroupKey) {
        updatingGroupFilter = true;
        try {
            String currentSelection = selectedGroupKey;
            if (currentSelection == null || currentSelection.trim().isEmpty()) {
                currentSelection = ALL_GROUPS_LABEL;
            }

            groupFilterCombo.removeAllItems();
            groupFilterCombo.addItem(ALL_GROUPS_LABEL);
            if (groupKeys != null) {
                for (String groupKey : groupKeys) {
                    if (groupKey != null && !groupKey.trim().isEmpty()) {
                        groupFilterCombo.addItem(groupKey);
                    }
                }
            }

            ComboBoxModel<String> model = groupFilterCombo.getModel();
            boolean found = false;
            for (int i = 0; i < model.getSize(); i++) {
                String value = model.getElementAt(i);
                if (currentSelection.equalsIgnoreCase(value)) {
                    groupFilterCombo.setSelectedItem(value);
                    found = true;
                    break;
                }
            }

            if (!found) {
                groupFilterCombo.setSelectedItem(ALL_GROUPS_LABEL);
            }
        } finally {
            updatingGroupFilter = false;
        }
    }

    public String getSelectedGroupFilter() {
        Object selectedItem = groupFilterCombo.getSelectedItem();
        return selectedItem == null ? null : selectedItem.toString();
    }

    public void setGroupFilterListener(Consumer<String> listener) {
        this.groupFilterListener = listener;
    }

    public void setSeriesSelectionListener(Consumer<SeriesTreeNodeData> listener) {
        this.seriesSelectionListener = listener;
    }

    public void updateStatus(String message) {
        statusLabel.setText(message);
    }

    public void displayImage(Mat image) {
        imagePanel.setImage(image);
    }

    public List<Path> showOpenFilesDialog() {
        JFileChooser fileChooser = createFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();
            if (selectedFiles != null && selectedFiles.length > 0) {
                List<Path> paths = new ArrayList<>();
                for (File selectedFile : selectedFiles) {
                    paths.add(selectedFile.toPath());
                }
                return paths;
            }

            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile != null) {
                List<Path> paths = new ArrayList<>();
                paths.add(selectedFile.toPath());
                return paths;
            }
        }
        return new ArrayList<>();
    }

    public Path showOpenFolderDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Выберите папку со снимками");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile != null) {
                return selectedFile.toPath();
            }
        }
        return null;
    }

    private JFileChooser createFileChooser() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Выберите снимки");
        fileChooser.setAcceptAllFileFilterUsed(true);
        fileChooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Изображения и DICOM", "jpg", "jpeg", "png", "bmp", "dcm"));
        return fileChooser;
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private static final class ImageCanvas extends JPanel {
        private BufferedImage image;

        private ImageCanvas() {
            setBackground(Color.LIGHT_GRAY);
        }

        private void setImage(Mat mat) {
            if (mat == null || mat.empty()) {
                image = null;
                repaint();
                return;
            }

            image = toBufferedImage(mat);
            repaint();
        }

        private BufferedImage toBufferedImage(Mat mat) {
            if (mat.channels() == 3) {
                Mat rgbMat = new Mat();
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB);

                BufferedImage bufImage = new BufferedImage(
                        rgbMat.cols(),
                        rgbMat.rows(),
                        BufferedImage.TYPE_3BYTE_BGR
                );

                byte[] data = new byte[rgbMat.rows() * rgbMat.cols() * rgbMat.channels()];
                rgbMat.get(0, 0, data);
                bufImage.getRaster().setDataElements(0, 0, rgbMat.cols(), rgbMat.rows(), data);
                return bufImage;
            }

            BufferedImage bufImage = new BufferedImage(
                    mat.cols(),
                    mat.rows(),
                    BufferedImage.TYPE_BYTE_GRAY
            );

            byte[] data = new byte[mat.rows() * mat.cols()];
            mat.get(0, 0, data);
            bufImage.getRaster().setDataElements(0, 0, mat.cols(), mat.rows(), data);
            return bufImage;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                g.setColor(Color.GRAY);
                g.drawString("Нет изображения", getWidth() / 2 - 50, getHeight() / 2);
                return;
            }

            int x = (getWidth() - image.getWidth()) / 2;
            int y = (getHeight() - image.getHeight()) / 2;
            g.drawImage(image, x, y, this);
        }
    }
}
