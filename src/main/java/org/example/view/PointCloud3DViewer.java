package org.example.view;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
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

    // Параметры для оптимизации - АГРЕССИВНЫЙ даунсемплинг для производительности
    private static final int DOWNSAMPLE_FACTOR = 8; // Пропускаем каждый 8-й пиксель
    private static final int MAX_POINTS_PER_SLICE = 2000; // Максимум точек на слайс
    private static final int MAX_TOTAL_POINTS = 50000; // Абсолютный максимум точек во всей модели

    // Для визуализации яркости
    private static final double POINT_SIZE = 2.5; // Размер точки

    public PointCloud3DViewer(List<SeriesImageItem> items, String title) {
        super();
        Platform.runLater(() -> createScene(items, title));
    }

    private void createScene(List<SeriesImageItem> items, String title) {
        root = new Group();
        Scene scene = new Scene(root, 800, 600, true);
        scene.setFill(Color.rgb(20, 20, 30));

        // Камера - будет настроена после создания облака точек
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(10000);
        camera.setFieldOfView(45);
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

        // Создаем облако точек и настраиваем камеру
        createPointCloud(items);

        // Обработчики мыши для вращения ОБЛАКА ТОЧЕК (не камеры)
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

            // Вращаем облако точек вокруг своего центра
            rotateY.setAngle(rotateY.getAngle() + mouseDeltaX * 0.5);
            rotateX.setAngle(rotateX.getAngle() - mouseDeltaY * 0.5);

            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
        });

        // Колесо мыши для зума (движение камеры вперед/назад)
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

        // Получаем PixelSpacing из метаданных
        double[] pixelSpacing = firstItem.getPixelSpacing();
        double pixelSpacingX = (pixelSpacing != null && pixelSpacing.length >= 2) ? pixelSpacing[1] : 0.7; // мм на пиксель по X
        double pixelSpacingY = (pixelSpacing != null && pixelSpacing.length >= 1) ? pixelSpacing[0] : 0.7; // мм на пиксель по Y

        // Вычисляем расстояние между слайсами (толщину среза)
        double sliceThickness = getSliceThickness(sortedItems);
        if (sliceThickness <= 0) {
            sliceThickness = 1.0; // Значение по умолчанию 1 мм
        }

        System.out.println("\n========== СОЗДАНИЕ 3D ОБЛАКА ТОЧЕК ==========");
        System.out.println("Количество слайсов: " + sortedItems.size());
        System.out.println("Размер изображения: " + imageWidth + "x" + imageHeight);
        System.out.println("PixelSpacing: X=" + pixelSpacingX + " мм, Y=" + pixelSpacingY + " мм");
        System.out.println("Расстояние между слайсами (Z): " + sliceThickness + " мм");
        System.out.println("Позиция Z: от " + minZ + " до " + maxZ + " мм");
        System.out.println("Физический размер слайса: " + (imageWidth * pixelSpacingX) + " x " + (imageHeight * pixelSpacingY) + " мм");
        System.out.println("Даунсемплинг: каждые " + DOWNSAMPLE_FACTOR + " пикселя");
        System.out.println("Макс. точек на слайс: " + MAX_POINTS_PER_SLICE);
        System.out.println("Макс. точек всего: " + MAX_TOTAL_POINTS);

        long totalPoints = 0;
        long startTime = System.currentTimeMillis();

        // Проходим по всем слайсам и создаем точки
        for (int sliceIndex = 0; sliceIndex < sortedItems.size(); sliceIndex++) {
            if (totalPoints >= MAX_TOTAL_POINTS) {
                System.out.println("Достигнут лимит общего количества точек: " + MAX_TOTAL_POINTS);
                break;
            }

            SeriesImageItem item = sortedItems.get(sliceIndex);
            Mat image = item.getImage();

            // Получаем позицию слайса по Z (в мм от начальной позиции)
            double zPos = item.getSlicePositionMm();
            if (!Double.isFinite(zPos)) {
                zPos = sliceIndex * sliceThickness;
            }

            // Нормализуем Z относительно первого слайса
            double zNormalized = zPos - minZ;

            // Преобразуем в координаты сцены с учетом физических размеров
            // Используем единый масштаб: 1 мм = 1 единица сцены
            double z = zNormalized;

            // Проходим по пикселям с даунсемплингом
            byte[] pixelData = new byte[(int) (image.total() * image.channels())];
            image.get(0, 0, pixelData);

            int pointsInSlice = 0;
            for (int y = 0; y < imageHeight; y += DOWNSAMPLE_FACTOR) {
                for (int x = 0; x < imageWidth; x += DOWNSAMPLE_FACTOR) {
                    // Ограничение на количество точек в слайсе или общее
                    if (pointsInSlice >= MAX_POINTS_PER_SLICE || totalPoints >= MAX_TOTAL_POINTS) {
                        break;
                    }

                    int pixelIndex = y * imageWidth + x;
                    if (pixelIndex >= pixelData.length) continue;

                    int brightness = pixelData[pixelIndex] & 0xFF;

                    // Если яркость > 0, добавляем точку
                    if (brightness > 0) {
                        // Преобразуем координаты с учетом физических размеров пикселей
                        // Центр изображения в (0, 0), Y инвертирован
                        double sceneX = (x * pixelSpacingX) - (imageWidth * pixelSpacingX) / 2.0;
                        double sceneY = -((y * pixelSpacingY) - (imageHeight * pixelSpacingY) / 2.0);

                        // Создаем Box для точки
                        Box box = new Box(POINT_SIZE, POINT_SIZE, POINT_SIZE);

                        // ЦВЕТ НА ОСНОВЕ ОРИГИНАЛЬНОЙ ЯРКОСТИ
                        // Сохраняем оригинальную яркость через цветовую схему
                        double intensity = brightness / 255.0;

                        // Градиент от темно-синего до ярко-белого:
                        // - Низкая яркость: темные оттенки синего
                        // - Средняя яркость: голубые оттенки
                        // - Высокая яркость: почти белый
                        Color color = Color.rgb(
                                (int)(50 + intensity * 205),  // R: от 50 до 255
                                (int)(80 + intensity * 175),  // G: от 80 до 255
                                (int)(120 + intensity * 135), // B: от 120 до 255
                                1.0
                        );

                        PhongMaterial material = new PhongMaterial(color);
                        // Отключаем specular highlights для производительности
                        material.setSpecularColor(Color.BLACK);

                        box.setMaterial(material);
                        box.setTranslateX(sceneX);
                        box.setTranslateY(sceneY);
                        box.setTranslateZ(z);

                        pointCloudGroup.getChildren().add(box);
                        totalPoints++;
                        pointsInSlice++;
                    }
                }
                if (pointsInSlice >= MAX_POINTS_PER_SLICE || totalPoints >= MAX_TOTAL_POINTS) {
                    break;
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

        // Вычисляем границы облака точек для настройки камеры
        double[] bounds = calculateBounds(sortedItems, pixelSpacingX, pixelSpacingY, sliceThickness, minZ);
        double maxX = bounds[0];
        double maxY = bounds[1];
        double maxZRange = bounds[2];

        // Находим максимальный размер для масштабирования камеры
        double maxDimension = Math.max(maxX, Math.max(maxY, maxZRange));
        maxDimension = Math.max(maxDimension, 100); // Минимальный размер

        // Настраиваем камеру так, чтобы всё облако было видно
        // Камера смотрит на центр облака (0, 0, средний Z)
        double averageZ = (maxZ - minZ) / 2.0;
        camera.setTranslateX(0);
        camera.setTranslateY(0);
        camera.setTranslateZ(-maxDimension * 2.5); // Отводим камеру назад

        System.out.println("Границы облака: X=" + maxX + ", Y=" + maxY + ", Z диапазон=" + maxZRange);
        System.out.println("Позиция камеры: Z=" + camera.getTranslateZ());
        System.out.println("========== 3D ОБЛАКО ТОЧЕК ГОТОВО ==========\n");
    }

    /**
     * Вычисляет физические границы облака точек для настройки камеры
     */
    private double[] calculateBounds(List<SeriesImageItem> items, double pixelSpacingX, double pixelSpacingY,
                                     double sliceThickness, double minZ) {
        double maxX = 0;
        double maxY = 0;
        double minSceneZ = Double.MAX_VALUE;
        double maxSceneZ = Double.MIN_VALUE;

        for (SeriesImageItem item : items) {
            Mat image = item.getImage();
            int width = image.cols();
            int height = image.rows();

            // Половина размера в координатах сцены с учетом физических размеров пикселей
            double halfWidth = (width * pixelSpacingX) / 2.0;
            double halfHeight = (height * pixelSpacingY) / 2.0;

            maxX = Math.max(maxX, halfWidth);
            maxY = Math.max(maxY, halfHeight);

            double zPos = item.getSlicePositionMm();
            if (Double.isFinite(zPos)) {
                double sceneZ = zPos - minZ;
                minSceneZ = Math.min(minSceneZ, sceneZ);
                maxSceneZ = Math.max(maxSceneZ, sceneZ);
            }
        }

        double zRange = maxSceneZ - minSceneZ;
        if (zRange < 0 || !Double.isFinite(zRange)) {
            zRange = items.size() * sliceThickness; // Значение по умолчанию
        }

        return new double[]{maxX, maxY, zRange};
    }

    /**
     * Вычисляет расстояние между слайсами (толщину среза)
     * Берется из разницы позиций соседних слайсов
     */
    private double getSliceThickness(List<SeriesImageItem> items) {
        if (items.size() < 2) {
            return 0;
        }

        // Считаем среднюю разницу между позициями слайсов
        double totalDiff = 0;
        int count = 0;

        for (int i = 1; i < items.size(); i++) {
            double pos1 = items.get(i - 1).getSlicePositionMm();
            double pos2 = items.get(i).getSlicePositionMm();

            if (Double.isFinite(pos1) && Double.isFinite(pos2)) {
                totalDiff += Math.abs(pos2 - pos1);
                count++;
            }
        }

        return count > 0 ? totalDiff / count : 0;
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
            infoPanel.add(new JLabel("Левая кнопка: вращение | Колесо: зум"));
            frame.add(infoPanel, BorderLayout.SOUTH);

            frame.setVisible(true);
        });
    }
}