package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Stack;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class MainSwing extends JFrame {

    private ImagePanel imagePanel;
    private JLabel statusLabel;
    private Mat currentImage;

    // Стеки для Undo/Redo
    private Stack<Mat> undoStack = new Stack<>();
    private Stack<Mat> redoStack = new Stack<>();

    // Флаг, чтобы не сохранять при загрузке истории
    private boolean isLoadingFromHistory = false;

    public MainSwing() {
        setTitle("OpenCV + Swing Приложение");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        createMenuBar();
        setupKeyBindings(); // Добавляем горячие клавиши

        imagePanel = new ImagePanel();
        imagePanel.setBackground(Color.LIGHT_GRAY);

        JPanel buttonPanel = createButtonPanel();

        statusLabel = new JLabel("OpenCV версия: " + org.opencv.core.Core.VERSION);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        setLayout(new BorderLayout());
        add(new JScrollPane(imagePanel), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.NORTH);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Меню "Файл"
        JMenu fileMenu = new JMenu("Файл");
        JMenuItem openItem = new JMenuItem("Открыть изображение...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openImage());

        JMenuItem exitItem = new JMenuItem("Выход");
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(openItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // Меню "Правка" с Undo/Redo
        JMenu editMenu = new JMenu("Правка");

        JMenuItem undoItem = new JMenuItem("Отменить");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> undo());

        editMenu.add(undoItem);

        // Меню "Обработка"
        JMenu processMenu = new JMenu("Обработка");

        JMenuItem grayItem = new JMenuItem("Сделать черно-белым");
        grayItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK));
        grayItem.addActionListener(e -> convertToGray());

        JMenuItem blurItem = new JMenuItem("Размытие");
        blurItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK));
        blurItem.addActionListener(e -> applyBlur());

        JMenuItem resetItem = new JMenuItem("Сбросить все изменения");
        resetItem.addActionListener(e -> resetToOriginal());

        processMenu.add(grayItem);
        processMenu.add(blurItem);
        processMenu.addSeparator();
        processMenu.add(resetItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(processMenu);

        setJMenuBar(menuBar);
    }

    // Настройка горячих клавиш на уровне окна
    private void setupKeyBindings() {
        // Ctrl+Z
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undo");
        getRootPane().getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undo();
            }
        });

        // Ctrl+Y
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "redo");
        getRootPane().getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redo();
            }
        });
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel();

        JButton openButton = new JButton("Открыть");
        openButton.addActionListener(e -> openImage());

        JButton grayButton = new JButton("Ч/Б");
        grayButton.addActionListener(e -> convertToGray());

        JButton blurButton = new JButton("Размыть");
        blurButton.addActionListener(e -> applyBlur());

        JButton undoButton = new JButton("Отмена (Ctrl+Z)");
        undoButton.addActionListener(e -> undo());

        panel.add(openButton);
        panel.add(grayButton);
        panel.add(blurButton);
        panel.add(undoButton);

        return panel;
    }

    // Сохраняем состояние перед изменением
    private void saveStateForUndo() {
        if (isLoadingFromHistory || currentImage == null) return;

        // Сохраняем копию текущего изображения
        Mat copy = currentImage.clone();
        undoStack.push(copy);

        // При новом действии очищаем redo стек
        redoStack.clear();

        updateStatus("Изменение сохранено для отката");
    }

    // Undo (Ctrl+Z)
    private void undo() {
        if (undoStack.isEmpty()) {
            updateStatus("Нечего отменять");
            return;
        }

        // Сохраняем текущее состояние в redo стек
        if (currentImage != null) {
            redoStack.push(currentImage.clone());
        }

        // Восстанавливаем предыдущее состояние
        isLoadingFromHistory = true;
        currentImage = undoStack.pop();
        imagePanel.setImage(currentImage);
        isLoadingFromHistory = false;

        updateStatus("Отмена: " + undoStack.size() + " шагов осталось");
    }

    // Redo (Ctrl+Y)
    private void redo() {
        if (redoStack.isEmpty()) {
            updateStatus("Нечего повторять");
            return;
        }

        // Сохраняем текущее в undo
        if (currentImage != null) {
            undoStack.push(currentImage.clone());
        }

        // Восстанавливаем из redo
        isLoadingFromHistory = true;
        currentImage = redoStack.pop();
        imagePanel.setImage(currentImage);
        isLoadingFromHistory = false;

        updateStatus("Повтор: " + redoStack.size() + " шагов осталось");
    }

    // Сброс к оригиналу (если сохраняли)
    private void resetToOriginal() {
        if (undoStack.isEmpty()) {
            updateStatus("Нет сохраненных состояний");
            return;
        }

        // Очищаем оба стека
        redoStack.clear();

        // Берем самое первое состояние (со дна стека)
        Mat original = undoStack.firstElement().clone();

        isLoadingFromHistory = true;
        currentImage = original;
        imagePanel.setImage(currentImage);

        // Очищаем undo, оставляем только оригинал
        undoStack.clear();
        undoStack.push(original.clone());

        isLoadingFromHistory = false;
        updateStatus("Сброшено к оригиналу");
    }

    private void openImage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Изображения", "jpg", "jpeg", "png", "bmp"));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = fileChooser.getSelectedFile().getAbsolutePath();

            Mat image = Imgcodecs.imread(path);

            if (!image.empty()) {
                // Очищаем историю при загрузке нового файла
                undoStack.clear();
                redoStack.clear();

                currentImage = image.clone();

                // Сохраняем оригинал в undo стек
                undoStack.push(image.clone());

                imagePanel.setImage(image);
                updateStatus("Загружено: " + fileChooser.getSelectedFile().getName() +
                        " (" + image.cols() + "x" + image.rows() + ")");
            } else {
                JOptionPane.showMessageDialog(this,
                        "Не удалось загрузить изображение",
                        "Ошибка",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void convertToGray() {
        if (currentImage == null) {
            JOptionPane.showMessageDialog(this, "Сначала загрузите изображение!");
            return;
        }

        if (currentImage.channels() == 1) {
            updateStatus("Изображение уже черно-белое");
            return;
        }

        // Сохраняем состояние ДО изменения
        saveStateForUndo();

        Mat gray = new Mat();
        Imgproc.cvtColor(currentImage, gray, Imgproc.COLOR_BGR2GRAY);
        currentImage = gray;
        imagePanel.setImage(gray);

        updateStatus("Применен черно-белый фильтр");
    }

    private void applyBlur() {
        if (currentImage == null) {
            JOptionPane.showMessageDialog(this, "Сначала загрузите изображение!");
            return;
        }

        // Сохраняем состояние ДО изменения
        saveStateForUndo();

        Mat blurred = new Mat();
        Imgproc.GaussianBlur(currentImage, blurred, new org.opencv.core.Size(15, 15), 0);
        currentImage = blurred;
        imagePanel.setImage(blurred);

        updateStatus("Применено размытие");
    }

    private void updateStatus(String message) {
        String size = (currentImage != null) ?
                String.format(" [%dx%d, %d каналов]",
                        currentImage.cols(), currentImage.rows(), currentImage.channels()) : "";

        statusLabel.setText(message + size +
                " | Undo: " + undoStack.size() + " Redo: " + redoStack.size());
    }

    public static void main(String[] args) {
        if (!OpenCVLoader.loadOpenCV()) {
            JOptionPane.showMessageDialog(null,
                    "Не удалось загрузить OpenCV!\nПроверьте путь к DLL.",
                    "Критическая ошибка",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        SwingUtilities.invokeLater(() -> {
            new MainSwing().setVisible(true);
        });
    }
}