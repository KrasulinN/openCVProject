package org.example.view;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import org.example.model.SeriesImageItem;
import org.opencv.core.Mat;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class PointCloud3DViewer extends JFXPanel {
    private Group root;
    private PerspectiveCamera camera;
    private Group pointCloudGroup;
    private double mousePosX, mousePosY, mouseOldX, mouseOldY;
    private double mouseDeltaX, mouseDeltaY;
    private final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);

    // Параметры для оптимизации
    private static final int DOWNSAMPLE_FACTOR = 2; // Пропускаем каждый 2-й пиксель
    private static final double POINT_SIZE = 2.0;   // Размер точки

    public PointCloud3DViewer(List<SeriesImageItem> items, String title) {
        super();
        Platform.runLater(() -> createScene(items, title));
    }

    private void createScene(List<SeriesImageItem> items, String title) {
        root = new Group();
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.rgb(20, 20, 30));

        // Камера
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        camera.setFieldOfView(45);
        camera.setTranslateZ(-500);
        scene.setCamera(camera);

        // Группа для облака точек
        pointCloudGroup = new Group();
        pointCloudGroup.getTransforms().addAll(rotateX, rotateY);
        root.getChildren().add(pointCloudGroup);

        // Добавляем свет
        AmbientLight ambientLight = new AmbientLight(Color.WHITE);
        root.getChildren().add(ambientLight);

        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(200);
        pointLight.setTranslateY(-200);
        pointLight.setTranslateZ(-300);
        root.getChildren().add(pointLight);

        // Создаем облако точек
        createPointCloud(items);

        // Обработчики мыши для вращения
        scene.setOnMousePressed(event -> {
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
        });

        scene.setOnMouseDragged(event -> {
            mouseOldX = mousePosX;
            mouseOldY = mousePosY;
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
            mouseDeltaX = mousePosX - mouseOldX;
            mouseDeltaY = mousePosY - mouseOldY;

            rotateY.setAngle(rotateY.getAngle() + mouseDeltaX * 0.5);
            rotateX.setAngle(rotateX.getAngle() - mouseDeltaY * 0.5);

            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
        });

        // Колесо мыши для зума
        scene.setOnScroll(event -> {
            double delta = event.getDeltaY();
            camera.setTranslateZ(camera.getTranslateZ() - delta * 0.5);
        });

        setScene(scene);
    }

    private void createPointCloud(List<SeriesImageItem> items) {
        if (items.isEmpty()) {
            return;
        }

        // Сортируем слайсы по позиции
        List<SeriesImageItem> sortedItems = new ArrayList<>(items);
        sortedItems.sort((a, b) -> Double.compare(a.getSlicePositionMm(), b.getSlicePositionMm()));

        // Находим минимальную позицию для нормализации
        double minZ = sortedItems.get(0).getSlicePositionMm();
        double maxZ = sortedItems.get(sortedItems.size() - 1).getSlicePositionMm();

        // Получаем параметры первого слайса для масштаба
        SeriesImageItem firstItem = sortedItems.get(0);
        Mat firstImage = firstItem.getImage();
        int imageWidth = firstImage.cols();
        int imageHeight = firstImage.rows();

        // Центр изображения
        double centerX = imageWidth / 2.0;
        double centerY = imageHeight / 2.0;

        System.out.println("\n========== СОЗДАНИЕ 3D ОБЛАКА ТОЧЕК ==========");
        System.out.println("Количество слайсов: " + sortedItems.size());
        System.out.println("Размер изображения: " + imageWidth + "x" + imageHeight);
        System.out.println("Позиция Z: от " + minZ + " до " + maxZ + " мм");
        System.out.println("Даунсемплинг: каждые " + DOWNSAMPLE_FACTOR + " пикселя");
        System.out.println("Размер точки: " + POINT_SIZE + " ед.");

        long totalPoints = 0;
        long startTime = System.currentTimeMillis();

        // Предварительно подсчитываем количество точек для прогресса
        long estimatedPoints = 0;
        for (SeriesImageItem item : sortedItems) {
            Mat image = item.getImage();
            byte[] pixelData = new byte[(int) (image.total() * image.channels())];
            image.get(0, 0, pixelData);
            for (int i = 0; i < pixelData.length; i += DOWNSAMPLE_FACTOR) {
                if ((pixelData[i] & 0xFF) > 0) {
                    estimatedPoints++;
                }
            }
        }
        System.out.println("Ожидаемое количество точек: ~" + estimatedPoints);

        // Проходим по всем слайсам
        for (int sliceIndex = 0; sliceIndex < sortedItems.size(); sliceIndex++) {
            SeriesImageItem item = sortedItems.get(sliceIndex);
            Mat image = item.getImage();

            // Получаем позицию слайса по Z
            double zPos = item.getSlicePositionMm();
            if (!Double.isFinite(zPos)) {
                // Если позиция не задана, используем индекс слайса
                zPos = sliceIndex;
            }

            // Преобразуем в координаты сцены
            double z = zPos * 10; // Масштабируем для лучшей видимости

            // Проходим по пикселям с даунсемплингом
            byte[] pixelData = new byte[(int) (image.total() * image.channels())];
            image.get(0, 0, pixelData);

            for (int y = 0; y < imageHeight; y += DOWNSAMPLE_FACTOR) {
                for (int x = 0; x < imageWidth; x += DOWNSAMPLE_FACTOR) {
                    int pixelIndex = y * imageWidth + x;
                    if (pixelIndex >= pixelData.length) continue;

                    int brightness = pixelData[pixelIndex] & 0xFF;

                    // Если яркость > 0, добавляем точку
                    if (brightness > 0) {
                        // Преобразуем координаты: центр в (0, 0), Y инвертирован
                        double sceneX = (x - centerX) * 2; // Масштаб для лучшей видимости
                        double sceneY = -(y - centerY) * 2; // Инвертируем Y

                        // Используем Box вместо Sphere для экономии памяти
                        Box box = new Box(POINT_SIZE, POINT_SIZE, POINT_SIZE);

                        // Цвет зависит от яркости
                        double intensity = brightness / 255.0;
                        Color color = Color.hsb(200, 0.5, intensity); // Голубоватые оттенки
                        PhongMaterial material = new PhongMaterial(color);
                        box.setMaterial(material);
                        box.setTranslateX(sceneX);
                        box.setTranslateY(sceneY);
                        box.setTranslateZ(z);

                        pointCloudGroup.getChildren().add(box);
                        totalPoints++;
                    }
                }
            }

            if ((sliceIndex + 1) % 10 == 0 || sliceIndex == sortedItems.size() - 1) {
                System.out.println("Обработано слайсов: " + (sliceIndex + 1) + "/" + sortedItems.size() +
                        ", точек: " + totalPoints);
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("Всего точек: " + totalPoints);
        System.out.println("Время создания: " + (endTime - startTime) + " мс");
        System.out.println("========== 3D ОБЛАКО ТОЧЕК ГОТОВО ==========\n");
    }

    public static void showViewer(List<SeriesImageItem> items, String title) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(title);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1000, 800);
            frame.setLocationRelativeTo(null);

            PointCloud3DViewer viewer = new PointCloud3DViewer(items, title);
            frame.add(viewer, BorderLayout.CENTER);

            // Панель с инструкциями
            JPanel infoPanel = new JPanel();
            infoPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
            infoPanel.add(new JLabel("🖱️ Левая кнопка: вращение | Колесо: зум"));
            frame.add(infoPanel, BorderLayout.SOUTH);

            frame.setVisible(true);
        });
    }
}