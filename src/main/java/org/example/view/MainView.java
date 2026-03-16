package org.example.view;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import org.example.controller.ImageController;

public class MainView extends JFrame {
    private ImagePanel imagePanel;
    private JLabel statusLabel;
    private JMenuBar menuBar;
    private JPanel buttonPanel;

    // Кнопки и пункты меню (для доступа из контроллера)
    private JMenuItem openMenuItem;
    private JMenuItem undoMenuItem;
    private JMenuItem redoMenuItem;
    private JMenuItem grayMenuItem;
    private JMenuItem blurMenuItem;
    private JMenuItem resetMenuItem;

    private JButton openButton;
    private JButton grayButton;
    private JButton blurButton;
    private JButton undoButton;
    private JButton redoButton;

    public MainView() {
        setTitle("OpenCV + Swing Приложение");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);

        initComponents();
        layoutComponents();
    }

    private void initComponents() {
        imagePanel = new ImagePanel();
        statusLabel = new JLabel("Готов к работе");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        createMenuBar();
        createButtonPanel();
    }

    private void createMenuBar() {
        menuBar = new JMenuBar();

        // Меню "Файл"
        JMenu fileMenu = new JMenu("Файл");
        openMenuItem = new JMenuItem("Открыть изображение...");
        openMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));

        JMenuItem exitItem = new JMenuItem("Выход");
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(openMenuItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // Меню "Правка"
        JMenu editMenu = new JMenu("Правка");
        undoMenuItem = new JMenuItem("Отменить");
        undoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK));

        redoMenuItem = new JMenuItem("Повторить");
        redoMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK));

        editMenu.add(undoMenuItem);
        editMenu.add(redoMenuItem);

        // Меню "Обработка"
        JMenu processMenu = new JMenu("Обработка");
        grayMenuItem = new JMenuItem("Сделать черно-белым");
        grayMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.CTRL_DOWN_MASK));

        blurMenuItem = new JMenuItem("Размытие");
        blurMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK));

        resetMenuItem = new JMenuItem("Сбросить все изменения");

        processMenu.add(grayMenuItem);
        processMenu.add(blurMenuItem);
        processMenu.addSeparator();
        processMenu.add(resetMenuItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(processMenu);

        setJMenuBar(menuBar);
    }

    private void createButtonPanel() {
        buttonPanel = new JPanel();

        openButton = new JButton("Открыть");
        grayButton = new JButton("Ч/Б");
        blurButton = new JButton("Размыть");
        undoButton = new JButton("Отмена (Ctrl+Z)");
        redoButton = new JButton("Повтор (Ctrl+Y)");

        buttonPanel.add(openButton);
        buttonPanel.add(grayButton);
        buttonPanel.add(blurButton);
        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
    }

    private void layoutComponents() {
        setLayout(new BorderLayout());
        add(new JScrollPane(imagePanel), BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.NORTH);
        add(statusLabel, BorderLayout.SOUTH);
    }

    // Методы для контроллера
    public void setController(ImageController controller) {
        // Меню
        openMenuItem.addActionListener(controller::onOpenImage);
        undoMenuItem.addActionListener(controller::onUndo);
        redoMenuItem.addActionListener(controller::onRedo);
        grayMenuItem.addActionListener(controller::onGrayFilter);
        blurMenuItem.addActionListener(controller::onBlurFilter);
        resetMenuItem.addActionListener(controller::onReset);

        // Кнопки
        openButton.addActionListener(controller::onOpenImage);
        grayButton.addActionListener(controller::onGrayFilter);
        blurButton.addActionListener(controller::onBlurFilter);
        undoButton.addActionListener(controller::onUndo);
        redoButton.addActionListener(controller::onRedo);

        // Горячие клавиши на уровне окна
        setupKeyBindings(controller);
    }

    private void setupKeyBindings(ImageController controller) {
        // Ctrl+Z
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), "undo");
        getRootPane().getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controller.onUndo(e);
            }
        });

        // Ctrl+Y
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), "redo");
        getRootPane().getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controller.onRedo(e);
            }
        });
    }

    public void updateStatus(String message) {
        statusLabel.setText(message);
    }

    public void displayImage(org.opencv.core.Mat image) {
        imagePanel.setImage(image);
    }

    public String showOpenFileDialog() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Изображения", "jpg", "jpeg", "png", "bmp"));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }
}