package org.example.view;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import org.example.model.SeriesImageItem;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PointCloud3DViewer extends JFXPanel {
    private static final AtomicBoolean JAVAFX_INITIALIZED = new AtomicBoolean(false);
    private static final int BACKGROUND_MARGIN = 14;
    private static final int BACKGROUND_MAX_THRESHOLD = 48;
    private static final int DEFAULT_SURFACE_THRESHOLD = 58;
    private static final double DEFAULT_TARGET_VOXEL_SIZE_MM = 1.5;
    private static final int MIN_TARGET_VOXEL_SIZE_TENTHS_MM = 6;
    private static final int MAX_TARGET_VOXEL_SIZE_TENTHS_MM = 40;
    private static final int MPR_PREVIEW_LONG_SIDE = 640;
    private static final int VOXEL_STEP_Z = 1;
    private static final int INTENSITY_BUCKETS = 8;

    private final List<SeriesImageItem> sortedItems;
    private final double pixelSpacingX;
    private final double pixelSpacingY;
    private final double sliceThickness;
    private final int imageWidth;
    private final int imageHeight;
    private final AtomicInteger meshBuildVersion = new AtomicInteger();
    private final Rotate rotateX = new Rotate(-20, Rotate.X_AXIS);
    private final Rotate rotateY = new Rotate(-35, Rotate.Y_AXIS);

    private Group root;
    private Group worldGroup;
    private PerspectiveCamera camera;
    private Group meshGroup;
    private double mousePosX;
    private double mousePosY;
    private double mouseOldX;
    private double mouseOldY;
    private int visibleSliceCount;
    private volatile int surfaceThreshold = DEFAULT_SURFACE_THRESHOLD;
    private volatile double targetVoxelSizeMm = DEFAULT_TARGET_VOXEL_SIZE_MM;
    private volatile double surfaceOpacity = 0.92;
    private volatile double defaultCameraZ = -700.0;

    public PointCloud3DViewer(List<SeriesImageItem> items, String title) {
        super();
        this.sortedItems = prepareSortedItems(items);
        SeriesImageItem firstItem = this.sortedItems.get(0);
        Mat firstImage = firstItem.getImage();
        this.imageWidth = firstImage.cols();
        this.imageHeight = firstImage.rows();
        double[] spacing = firstItem.getPixelSpacing();
        this.pixelSpacingX = spacing != null && spacing.length >= 2 ? spacing[1] : 0.7;
        this.pixelSpacingY = spacing != null && spacing.length >= 1 ? spacing[0] : 0.7;
        double computedThickness = getSliceThickness(this.sortedItems);
        this.sliceThickness = computedThickness > 0 ? computedThickness : 1.0;
        this.visibleSliceCount = this.sortedItems.size();

        Platform.setImplicitExit(false);
        Platform.runLater(() -> createScene(title));
    }

    private static void ensureJavaFxInitialized() {
        if (JAVAFX_INITIALIZED.compareAndSet(false, true)) {
            new JFXPanel();
            Platform.setImplicitExit(false);
        }
    }

    private static List<SeriesImageItem> prepareSortedItems(List<SeriesImageItem> items) {
        List<SeriesImageItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(PointCloud3DViewer::resolveSlicePositionStatic));
        return sorted;
    }

    private void createScene(String title) {
        root = new Group();
        Scene scene = new Scene(root, 1000, 760, true);
        scene.setFill(Color.rgb(12, 15, 21));

        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(50000);
        camera.setFieldOfView(35);
        scene.setCamera(camera);

        worldGroup = new Group();
        worldGroup.getTransforms().addAll(rotateX, rotateY);
        root.getChildren().add(worldGroup);

        meshGroup = new Group();
        worldGroup.getChildren().add(meshGroup);

        AmbientLight ambientLight = new AmbientLight(Color.color(0.88, 0.90, 0.96));
        root.getChildren().add(ambientLight);

        PointLight pointLight = new PointLight(Color.WHITE);
        pointLight.setTranslateX(280);
        pointLight.setTranslateY(-260);
        pointLight.setTranslateZ(-460);
        root.getChildren().add(pointLight);

        scene.setOnMousePressed(event -> {
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();
        });

        scene.setOnMouseDragged(event -> {
            mouseOldX = mousePosX;
            mouseOldY = mousePosY;
            mousePosX = event.getSceneX();
            mousePosY = event.getSceneY();

            double mouseDeltaX = mousePosX - mouseOldX;
            double mouseDeltaY = mousePosY - mouseOldY;

            rotateY.setAngle(rotateY.getAngle() + mouseDeltaX * 0.45);
            rotateX.setAngle(rotateX.getAngle() - mouseDeltaY * 0.45);
        });

        scene.setOnScroll(event -> camera.setTranslateZ(camera.getTranslateZ() - event.getDeltaY() * 0.45));

        setScene(scene);
        rebuildMeshAsync(visibleSliceCount);

        System.out.println("\n========== ЗАПУСК 3D/MPR РЕКОНСТРУКЦИИ ==========");
        System.out.println("Окно: " + title);
        System.out.println("Количество слайсов: " + sortedItems.size());
        System.out.println("Размер изображения: " + imageWidth + "x" + imageHeight);
        System.out.println("PixelSpacing: X=" + pixelSpacingX + " мм, Y=" + pixelSpacingY + " мм");
        System.out.println("Расстояние между слайсами (Z): " + sliceThickness + " мм");
        System.out.println("Порог поверхности: " + surfaceThreshold);
        System.out.println("Целевой размер вокселя: " + format(targetVoxelSizeMm) + " мм");
        System.out.println("Физический шаг Z: " + format(sliceThickness * VOXEL_STEP_Z) + " мм");
    }

    private PhongMaterial createMeshMaterial(int bucket) {
        double intensity = bucket / (double) Math.max(1, INTENSITY_BUCKETS - 1);
        PhongMaterial material = new PhongMaterial();
        double shade = 0.18 + intensity * 0.74;
        material.setDiffuseColor(Color.color(shade, shade, Math.min(1.0, shade + 0.04)));
        material.setSpecularColor(Color.color(
                Math.min(1.0, shade + 0.18),
                Math.min(1.0, shade + 0.18),
                Math.min(1.0, shade + 0.20)
        ));
        return material;
    }

    public void setVisibleSliceCount(int count) {
        int safeCount = Math.max(1, Math.min(count, sortedItems.size()));
        visibleSliceCount = safeCount;
        rebuildMeshAsync(safeCount);
    }

    public void setSurfaceThreshold(int threshold) {
        int safeThreshold = Math.max(0, Math.min(255, threshold));
        if (surfaceThreshold == safeThreshold) {
            return;
        }
        surfaceThreshold = safeThreshold;
        rebuildMeshAsync(visibleSliceCount);
    }

    public void setTargetVoxelSizeMm(double voxelSizeMm) {
        double safeSize = Math.max(
                MIN_TARGET_VOXEL_SIZE_TENTHS_MM / 10.0,
                Math.min(MAX_TARGET_VOXEL_SIZE_TENTHS_MM / 10.0, voxelSizeMm)
        );
        if (Math.abs(targetVoxelSizeMm - safeSize) < 0.001) {
            return;
        }
        targetVoxelSizeMm = safeSize;
        rebuildMeshAsync(visibleSliceCount);
    }

    public void setSurfaceOpacity(int opacityPercent) {
        double safeOpacity = Math.max(0.15, Math.min(1.0, opacityPercent / 100.0));
        surfaceOpacity = safeOpacity;
        Platform.runLater(() -> {
            if (meshGroup == null) {
                return;
            }
            meshGroup.getChildren().forEach(node -> {
                if (!(node instanceof MeshView) || "bounds".equals(node.getId())) {
                    return;
                }
                node.setOpacity(surfaceOpacity);
            });
        });
    }

    public void resetView() {
        Platform.runLater(() -> {
            rotateX.setAngle(-20);
            rotateY.setAngle(-35);
            if (camera != null) {
                camera.setTranslateX(0);
                camera.setTranslateY(0);
                camera.setTranslateZ(defaultCameraZ);
            }
        });
    }

    private void rebuildMeshAsync(int sliceCount) {
        int buildVersion = meshBuildVersion.incrementAndGet();
        int thresholdSnapshot = surfaceThreshold;
        double targetVoxelSizeSnapshot = targetVoxelSizeMm;
        double opacitySnapshot = surfaceOpacity;

        Thread worker = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            MeshBuildResult result = buildSurfaceMesh(sliceCount, thresholdSnapshot, targetVoxelSizeSnapshot);
            long endTime = System.currentTimeMillis();

            Platform.runLater(() -> {
                if (meshBuildVersion.get() != buildVersion || meshGroup == null) {
                    return;
                }
                meshGroup.getChildren().clear();
                meshGroup.getChildren().add(createBoundsView(result.widthMm, result.heightMm, result.depthMm));

                for (int bucket = 0; bucket < result.meshes.length; bucket++) {
                    TriangleMesh mesh = result.meshes[bucket];
                    if (mesh.getFaces().size() == 0) {
                        continue;
                    }
                    MeshView meshView = new MeshView(mesh);
                    meshView.setCullFace(CullFace.BACK);
                    meshView.setDrawMode(DrawMode.FILL);
                    meshView.setMaterial(createMeshMaterial(bucket));
                    meshView.setOpacity(opacitySnapshot);
                    meshGroup.getChildren().add(meshView);
                }

                double maxDimension = Math.max(result.widthMm, Math.max(result.heightMm, result.depthMm));
                defaultCameraZ = -Math.max(maxDimension * 2.4, 600);
                camera.setTranslateX(0);
                camera.setTranslateY(0);
                camera.setTranslateZ(defaultCameraZ);

                System.out.println("\n========== 3D MESH ГОТОВ ==========");
                System.out.println("Слоев в mesh: " + sliceCount + "/" + sortedItems.size());
                System.out.println("Порог поверхности: " + thresholdSnapshot);
                System.out.println("Целевой размер вокселя: " + format(targetVoxelSizeSnapshot) + " мм");
                System.out.println("Фактический воксель: "
                        + format(result.voxelSizeXmm) + " x "
                        + format(result.voxelSizeYmm) + " x "
                        + format(result.voxelSizeZmm) + " мм");
                System.out.println("Активных вокселей: " + result.activeVoxelCount);
                System.out.println("Треугольников: " + result.triangleCount);
                System.out.println("Время построения: " + (endTime - startTime) + " мс");
            });
        }, "medical-volume-mesh-builder");
        worker.setDaemon(true);
        worker.start();
    }

    private MeshView createBoundsView(float widthMm, float heightMm, float depthMm) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().addAll(0, 0);

        float x0 = -widthMm / 2f;
        float x1 = widthMm / 2f;
        float y0 = heightMm / 2f;
        float y1 = -heightMm / 2f;
        float z0 = -depthMm / 2f;
        float z1 = depthMm / 2f;

        addQuad(mesh, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
        addQuad(mesh, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1);
        addQuad(mesh, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
        addQuad(mesh, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
        addQuad(mesh, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
        addQuad(mesh, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);

        MeshView bounds = new MeshView(mesh);
        bounds.setId("bounds");
        bounds.setCullFace(CullFace.NONE);
        bounds.setDrawMode(DrawMode.LINE);
        PhongMaterial material = new PhongMaterial(Color.color(0.28, 0.44, 0.72));
        material.setSpecularColor(Color.color(0.28, 0.44, 0.72));
        bounds.setMaterial(material);
        bounds.setOpacity(0.42);
        return bounds;
    }

    private MeshBuildResult buildSurfaceMesh(int sliceCount, int threshold, double targetVoxelSizeMm) {
        int stepX = pixelStepForTargetVoxel(pixelSpacingX, targetVoxelSizeMm);
        int stepY = pixelStepForTargetVoxel(pixelSpacingY, targetVoxelSizeMm);
        int sampledDepth = Math.max(1, (int) Math.ceil(sliceCount / (double) VOXEL_STEP_Z));
        int sampledWidth = Math.max(1, (int) Math.ceil(imageWidth / (double) stepX));
        int sampledHeight = Math.max(1, (int) Math.ceil(imageHeight / (double) stepY));

        boolean[] occupancy = new boolean[sampledWidth * sampledHeight * sampledDepth];
        byte[] intensityBuckets = new byte[occupancy.length];
        int activeVoxelCount = populateOccupancy(
                occupancy,
                intensityBuckets,
                sampledWidth,
                sampledHeight,
                sampledDepth,
                sliceCount,
                threshold,
                stepX,
                stepY
        );

        float voxelSizeX = (float) (pixelSpacingX * stepX);
        float voxelSizeY = (float) (pixelSpacingY * stepY);
        float voxelSizeZ = (float) (sliceThickness * VOXEL_STEP_Z);
        float widthMm = sampledWidth * voxelSizeX;
        float heightMm = sampledHeight * voxelSizeY;
        float depthMm = sampledDepth * voxelSizeZ;

        TriangleMesh[] meshes = new TriangleMesh[INTENSITY_BUCKETS];
        for (int i = 0; i < INTENSITY_BUCKETS; i++) {
            meshes[i] = new TriangleMesh();
            meshes[i].getTexCoords().addAll(0, 0);
        }

        float originX = -widthMm / 2f;
        float originY = heightMm / 2f;
        float originZ = -depthMm / 2f;
        int triangleCount = 0;

        for (int z = 0; z < sampledDepth; z++) {
            for (int y = 0; y < sampledHeight; y++) {
                for (int x = 0; x < sampledWidth; x++) {
                    int index = occupancyIndex(x, y, z, sampledWidth, sampledHeight);
                    if (!occupancy[index]) {
                        continue;
                    }
                    TriangleMesh mesh = meshes[intensityBuckets[index] & 0xFF];

                    float x0 = originX + x * voxelSizeX;
                    float x1 = x0 + voxelSizeX;
                    float y0 = originY - y * voxelSizeY;
                    float y1 = y0 - voxelSizeY;
                    float z0 = originZ + z * voxelSizeZ;
                    float z1 = z0 + voxelSizeZ;

                    if (!isOccupied(occupancy, x - 1, y, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1);
                    }
                    if (!isOccupied(occupancy, x + 1, y, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0);
                    }
                    if (!isOccupied(occupancy, x, y - 1, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
                    }
                    if (!isOccupied(occupancy, x, y + 1, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
                    }
                    if (!isOccupied(occupancy, x, y, z - 1, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0);
                    }
                    if (!isOccupied(occupancy, x, y, z + 1, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
                    }
                }
            }
        }

        return new MeshBuildResult(
                meshes,
                widthMm,
                heightMm,
                depthMm,
                voxelSizeX,
                voxelSizeY,
                voxelSizeZ,
                activeVoxelCount,
                triangleCount
        );
    }

    private int populateOccupancy(boolean[] occupancy, byte[] intensityBuckets,
                                  int sampledWidth, int sampledHeight, int sampledDepth,
                                  int sliceCount, int threshold, int stepX, int stepY) {
        int active = 0;

        for (int z = 0; z < sampledDepth; z++) {
            int sliceIndex = Math.min(sliceCount - 1, z * VOXEL_STEP_Z);
            SeriesImageItem item = sortedItems.get(sliceIndex);
            byte[] grayscale = toGrayscaleBytes(item.getImage());
            boolean[] backgroundMask = buildBackgroundMask(grayscale, imageWidth, imageHeight);

            for (int y = 0; y < sampledHeight; y++) {
                int srcY0 = y * stepY;
                int srcY1 = Math.min(imageHeight, srcY0 + stepY);
                for (int x = 0; x < sampledWidth; x++) {
                    int srcX0 = x * stepX;
                    int srcX1 = Math.min(imageWidth, srcX0 + stepX);
                    int intensity = averageForegroundIntensity(grayscale, backgroundMask, srcX0, srcX1, srcY0, srcY1);
                    if (intensity >= threshold) {
                        int occupancyIndex = occupancyIndex(x, y, z, sampledWidth, sampledHeight);
                        occupancy[occupancyIndex] = true;
                        intensityBuckets[occupancyIndex] = (byte) intensityToBucket(intensity, threshold);
                        active++;
                    }
                }
            }
        }

        return active;
    }

    private int pixelStepForTargetVoxel(double pixelSpacingMm, double targetVoxelSizeMm) {
        if (!Double.isFinite(pixelSpacingMm) || pixelSpacingMm <= 0.0) {
            return Math.max(1, (int) Math.round(targetVoxelSizeMm));
        }
        return Math.max(1, (int) Math.round(targetVoxelSizeMm / pixelSpacingMm));
    }

    private int averageForegroundIntensity(byte[] pixels, boolean[] backgroundMask,
                                           int srcX0, int srcX1, int srcY0, int srcY1) {
        int sum = 0;
        int count = 0;
        int max = 0;

        for (int y = srcY0; y < srcY1; y++) {
            int rowOffset = y * imageWidth;
            for (int x = srcX0; x < srcX1; x++) {
                int index = rowOffset + x;
                if (backgroundMask[index]) {
                    continue;
                }
                int value = pixels[index] & 0xFF;
                sum += value;
                count++;
                if (value > max) {
                    max = value;
                }
            }
        }

        if (count == 0) {
            return 0;
        }

        int average = Math.round(sum / (float) count);
        return Math.max(average, (int) Math.round(max * 0.65));
    }

    private static byte[] toGrayscaleBytes(Mat image) {
        Mat grayscale = image;
        Mat converted = null;

        if (image.channels() != 1) {
            converted = new Mat();
            Imgproc.cvtColor(image, converted, Imgproc.COLOR_BGR2GRAY);
            grayscale = converted;
        }

        byte[] pixels = new byte[Math.max(1, grayscale.cols() * grayscale.rows())];
        grayscale.get(0, 0, pixels);

        if (converted != null) {
            converted.release();
        }

        return pixels;
    }

    private boolean[] buildBackgroundMask(byte[] pixels, int width, int height) {
        boolean[] background = new boolean[pixels.length];
        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];

        int threshold = estimateBackgroundThreshold(pixels, width, height);
        int head = 0;
        int tail = 0;

        for (int x = 0; x < width; x++) {
            tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, x);
            tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, (height - 1) * width + x);
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, y * width);
            tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, y * width + (width - 1));
        }

        while (head < tail) {
            int index = queue[head++];
            background[index] = true;

            int x = index % width;
            int y = index / width;

            if (x > 0) {
                tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, index - 1);
            }
            if (x < width - 1) {
                tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, index + 1);
            }
            if (y > 0) {
                tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, index - width);
            }
            if (y < height - 1) {
                tail = enqueueIfBackground(queue, visited, pixels, threshold, tail, index + width);
            }
        }

        return background;
    }

    private int enqueueIfBackground(int[] queue, boolean[] visited, byte[] pixels, int threshold, int tail, int index) {
        if (index < 0 || index >= pixels.length || visited[index]) {
            return tail;
        }
        visited[index] = true;
        if ((pixels[index] & 0xFF) <= threshold) {
            queue[tail++] = index;
        }
        return tail;
    }

    private int estimateBackgroundThreshold(byte[] pixels, int width, int height) {
        int[] cornerValues = new int[]{
                pixels[0] & 0xFF,
                pixels[Math.max(0, width - 1)] & 0xFF,
                pixels[Math.max(0, (height - 1) * width)] & 0xFF,
                pixels[Math.max(0, height * width - 1)] & 0xFF
        };

        int maxCorner = 0;
        for (int value : cornerValues) {
            if (value > maxCorner) {
                maxCorner = value;
            }
        }

        return Math.min(maxCorner + BACKGROUND_MARGIN, BACKGROUND_MAX_THRESHOLD);
    }

    private boolean isOccupied(boolean[] occupancy, int x, int y, int z, int width, int height, int depth) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            return false;
        }
        return occupancy[occupancyIndex(x, y, z, width, height)];
    }

    private int occupancyIndex(int x, int y, int z, int width, int height) {
        return z * width * height + y * width + x;
    }

    private int intensityToBucket(int intensity, int threshold) {
        int normalized = Math.max(0, intensity - threshold);
        int range = Math.max(1, 255 - threshold);
        return Math.min(INTENSITY_BUCKETS - 1, normalized * INTENSITY_BUCKETS / range);
    }

    private int addQuad(TriangleMesh mesh,
                        float x0, float y0, float z0,
                        float x1, float y1, float z1,
                        float x2, float y2, float z2,
                        float x3, float y3, float z3) {
        int vertexIndex = mesh.getPoints().size() / 3;
        mesh.getPoints().addAll(
                x0, y0, z0,
                x1, y1, z1,
                x2, y2, z2,
                x3, y3, z3
        );
        mesh.getFaces().addAll(
                vertexIndex, 0,
                vertexIndex + 1, 0,
                vertexIndex + 2, 0,
                vertexIndex, 0,
                vertexIndex + 2, 0,
                vertexIndex + 3, 0
        );
        return 2;
    }

    private double getSliceThickness(List<SeriesImageItem> items) {
        if (items.size() < 2) {
            return 0;
        }

        double totalDiff = 0;
        int count = 0;

        for (int i = 1; i < items.size(); i++) {
            double pos1 = resolveSlicePositionStatic(items.get(i - 1));
            double pos2 = resolveSlicePositionStatic(items.get(i));

            if (Double.isFinite(pos1) && Double.isFinite(pos2)) {
                totalDiff += Math.abs(pos2 - pos1);
                count++;
            }
        }

        return count > 0 ? totalDiff / count : 0;
    }

    private static double resolveSlicePositionStatic(SeriesImageItem item) {
        double slicePosition = item.getSlicePositionMm();
        if (Double.isFinite(slicePosition)) {
            return slicePosition;
        }
        return item.getSliceOrder();
    }

    public static void showViewer(List<SeriesImageItem> items, String title) {
        SwingUtilities.invokeLater(() -> {
            ensureJavaFxInitialized();

            JFrame frame = new JFrame(title.replace("облако точек", "3D/MPR реконструкция"));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1320, 900);
            frame.setLocationRelativeTo(null);

            PointCloud3DViewer viewer = new PointCloud3DViewer(items, title);
            MprPanel mprPanel = new MprPanel(
                    viewer.sortedItems,
                    viewer.imageWidth,
                    viewer.imageHeight,
                    viewer.pixelSpacingX,
                    viewer.pixelSpacingY,
                    viewer.sliceThickness
            );

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewer, createSidePanel(viewer, mprPanel));
            splitPane.setResizeWeight(0.72);
            splitPane.setDividerLocation(930);
            frame.add(splitPane, BorderLayout.CENTER);

            JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            infoPanel.add(new JLabel("3D: левая кнопка - вращение, колесо - зум | MPR: coronal / sagittal"));
            frame.add(infoPanel, BorderLayout.SOUTH);

            frame.setVisible(true);
        });
    }

    private static JPanel createSidePanel(PointCloud3DViewer viewer, MprPanel mprPanel) {
        int totalSlices = Math.max(1, viewer.sortedItems.size());

        JPanel sidePanel = new JPanel(new BorderLayout(0, 10));
        sidePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        sidePanel.setPreferredSize(new Dimension(360, 0));

        JLabel header = new JLabel("MPR / Volume", SwingConstants.LEFT);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 18f));
        sidePanel.add(header, BorderLayout.NORTH);
        sidePanel.add(mprPanel, BorderLayout.CENTER);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

        JLabel mprSliceLabel = new JLabel("Позиция по Z: " + totalSlices + " / " + totalSlices);
        JSlider mprSliceSlider = new JSlider(SwingConstants.HORIZONTAL, 1, totalSlices, totalSlices);
        mprSliceSlider.addChangeListener(event -> {
            int value = mprSliceSlider.getValue();
            mprSliceLabel.setText("Позиция по Z: " + value + " / " + totalSlices);
            mprPanel.setSliceIndex(value - 1);
        });
        controls.add(createSliderBlock("Положение MPR", mprSliceSlider, mprSliceLabel));

        JLabel meshDepthLabel = new JLabel("Слоев в 3D: " + totalSlices + " / " + totalSlices);
        JSlider meshDepthSlider = new JSlider(SwingConstants.HORIZONTAL, 1, totalSlices, totalSlices);
        meshDepthSlider.addChangeListener(event -> {
            int value = meshDepthSlider.getValue();
            meshDepthLabel.setText("Слоев в 3D: " + value + " / " + totalSlices);
            if (!meshDepthSlider.getValueIsAdjusting()) {
                viewer.setVisibleSliceCount(value);
            }
        });
        controls.add(createSliderBlock("Глубина реконструкции", meshDepthSlider, meshDepthLabel));

        JLabel thresholdLabel = new JLabel("Порог ткани: " + DEFAULT_SURFACE_THRESHOLD);
        JSlider thresholdSlider = new JSlider(SwingConstants.HORIZONTAL, 0, 255, DEFAULT_SURFACE_THRESHOLD);
        thresholdSlider.addChangeListener(event -> {
            int value = thresholdSlider.getValue();
            thresholdLabel.setText("Порог ткани: " + value);
            mprPanel.setSurfaceThreshold(value);
            if (!thresholdSlider.getValueIsAdjusting()) {
                viewer.setSurfaceThreshold(value);
            }
        });
        controls.add(createSliderBlock("Сегментация mesh", thresholdSlider, thresholdLabel));

        int defaultVoxelTenths = voxelSizeToTenths(viewer.targetVoxelSizeMm);
        JLabel detailLabel = new JLabel("Размер вокселя: " + format(viewer.targetVoxelSizeMm) + " мм");
        JSlider detailSlider = new JSlider(
                SwingConstants.HORIZONTAL,
                MIN_TARGET_VOXEL_SIZE_TENTHS_MM,
                MAX_TARGET_VOXEL_SIZE_TENTHS_MM,
                defaultVoxelTenths
        );
        detailSlider.addChangeListener(event -> {
            double value = detailSlider.getValue() / 10.0;
            detailLabel.setText("Размер вокселя: " + format(value) + " мм");
            if (!detailSlider.getValueIsAdjusting()) {
                viewer.setTargetVoxelSizeMm(value);
            }
        });
        controls.add(createSliderBlock("Качество 3D", detailSlider, detailLabel));

        JLabel opacityLabel = new JLabel("Прозрачность: 92%");
        JSlider opacitySlider = new JSlider(SwingConstants.HORIZONTAL, 15, 100, 92);
        opacitySlider.addChangeListener(event -> {
            int value = opacitySlider.getValue();
            opacityLabel.setText("Прозрачность: " + value + "%");
            viewer.setSurfaceOpacity(value);
        });
        controls.add(createSliderBlock("Отображение поверхности", opacitySlider, opacityLabel));

        JButton resetButton = new JButton("Сбросить 3D вид");
        resetButton.addActionListener(event -> viewer.resetView());
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttonPanel.add(resetButton);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
        controls.add(buttonPanel);

        JTextArea metadata = new JTextArea(buildVolumeSummary(viewer));
        metadata.setEditable(false);
        metadata.setFocusable(false);
        metadata.setOpaque(false);
        metadata.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        metadata.setBorder(BorderFactory.createTitledBorder("Volume info"));
        controls.add(metadata);

        sidePanel.add(controls, BorderLayout.SOUTH);
        return sidePanel;
    }

    private static JPanel createSliderBlock(String title, JSlider slider, JLabel valueLabel) {
        JPanel panel = new JPanel(new BorderLayout(6, 2));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(slider, BorderLayout.CENTER);
        panel.add(valueLabel, BorderLayout.SOUTH);
        return panel;
    }

    private static String buildVolumeSummary(PointCloud3DViewer viewer) {
        double widthMm = viewer.imageWidth * viewer.pixelSpacingX;
        double heightMm = viewer.imageHeight * viewer.pixelSpacingY;
        double depthMm = Math.max(1, viewer.sortedItems.size()) * viewer.sliceThickness;
        return "Slices:  " + viewer.sortedItems.size() + "\n"
                + "Matrix:  " + viewer.imageWidth + " x " + viewer.imageHeight + "\n"
                + "Spacing: " + format(viewer.pixelSpacingX) + " x "
                + format(viewer.pixelSpacingY) + " x "
                + format(viewer.sliceThickness) + " mm\n"
                + "Volume:  " + format(widthMm) + " x "
                + format(heightMm) + " x "
                + format(depthMm) + " mm\n"
                + "Voxel:   " + format(viewer.targetVoxelSizeMm) + " mm target";
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private static int voxelSizeToTenths(double voxelSizeMm) {
        return Math.max(
                MIN_TARGET_VOXEL_SIZE_TENTHS_MM,
                Math.min(MAX_TARGET_VOXEL_SIZE_TENTHS_MM, (int) Math.round(voxelSizeMm * 10.0))
        );
    }

    private static final class MprPanel extends JPanel {
        private final List<byte[]> slicePixels = new ArrayList<>();
        private final int imageWidth;
        private final int imageHeight;
        private final double pixelSpacingX;
        private final double pixelSpacingY;
        private final double sliceThickness;
        private final MprSlicePanel coronalPanel = new MprSlicePanel("Coronal");
        private final MprSlicePanel sagittalPanel = new MprSlicePanel("Sagittal");
        private int sliceIndex;
        private int surfaceThreshold = DEFAULT_SURFACE_THRESHOLD;

        private MprPanel(List<SeriesImageItem> items, int imageWidth, int imageHeight,
                         double pixelSpacingX, double pixelSpacingY, double sliceThickness) {
            super(new GridLayout(2, 1, 0, 8));
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.pixelSpacingX = pixelSpacingX;
            this.pixelSpacingY = pixelSpacingY;
            this.sliceThickness = sliceThickness;

            for (SeriesImageItem item : items) {
                slicePixels.add(toGrayscaleBytes(item.getImage()));
            }

            sliceIndex = Math.max(0, slicePixels.size() - 1);
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            add(coronalPanel);
            add(sagittalPanel);
            updateImages();
        }

        private void setSliceIndex(int index) {
            sliceIndex = Math.max(0, Math.min(index, slicePixels.size() - 1));
            updateImages();
        }

        private void setSurfaceThreshold(int threshold) {
            surfaceThreshold = Math.max(0, Math.min(255, threshold));
            updateImages();
        }

        private void updateImages() {
            int centerX = imageWidth / 2;
            int centerY = imageHeight / 2;
            int depth = Math.max(1, slicePixels.size());
            double widthMm = imageWidth * pixelSpacingX;
            double heightMm = imageHeight * pixelSpacingY;
            double depthMm = depth * sliceThickness;
            double selectedZmm = sliceIndex * sliceThickness;

            coronalPanel.setImage(
                    "Coronal",
                    "Y " + format(centerY * pixelSpacingY) + " mm | Z " + format(selectedZmm) + " mm",
                    createCoronalImage(centerY),
                    physicalRatio(centerX * pixelSpacingX, widthMm),
                    physicalRatio(selectedZmm, depthMm)
            );
            sagittalPanel.setImage(
                    "Sagittal",
                    "X " + format(centerX * pixelSpacingX) + " mm | Z/Y plane",
                    createSagittalImage(),
                    physicalRatio(selectedZmm, depthMm),
                    physicalRatio(centerY * pixelSpacingY, heightMm)
            );
        }

        private BufferedImage createCoronalImage(int sourceY) {
            int y = Math.max(0, Math.min(imageHeight - 1, sourceY));
            double widthMm = imageWidth * pixelSpacingX;
            double depthMm = Math.max(1, slicePixels.size()) * sliceThickness;
            return createPhysicalPreview(widthMm, depthMm,
                    (xMm, zMm) -> sampleVolume(xMm / pixelSpacingX, y, zMm / sliceThickness));
        }

        private BufferedImage createSagittalImage() {
            int x = Math.max(0, Math.min(imageWidth - 1, imageWidth / 2));
            double depthMm = Math.max(1, slicePixels.size()) * sliceThickness;
            double heightMm = imageHeight * pixelSpacingY;
            return createPhysicalPreview(depthMm, heightMm,
                    (zMm, yMm) -> sampleVolume(x, yMm / pixelSpacingY, zMm / sliceThickness));
        }

        private BufferedImage createPhysicalPreview(double widthMm, double heightMm, PlaneSampler sampler) {
            int[] dimensions = previewDimensions(widthMm, heightMm);
            int previewWidth = dimensions[0];
            int previewHeight = dimensions[1];
            BufferedImage image = new BufferedImage(previewWidth, previewHeight, BufferedImage.TYPE_INT_RGB);

            for (int y = 0; y < previewHeight; y++) {
                double yMm = previewHeight <= 1 ? heightMm / 2.0 : y * heightMm / (previewHeight - 1.0);
                for (int x = 0; x < previewWidth; x++) {
                    double xMm = previewWidth <= 1 ? widthMm / 2.0 : x * widthMm / (previewWidth - 1.0);
                    image.setRGB(x, y, toPreviewRgb(sampler.sample(xMm, yMm)));
                }
            }
            return image;
        }

        private int[] previewDimensions(double widthMm, double heightMm) {
            double safeWidthMm = Math.max(1.0, widthMm);
            double safeHeightMm = Math.max(1.0, heightMm);
            double scale = MPR_PREVIEW_LONG_SIDE / Math.max(safeWidthMm, safeHeightMm);
            int previewWidth = Math.max(1, (int) Math.round(safeWidthMm * scale));
            int previewHeight = Math.max(1, (int) Math.round(safeHeightMm * scale));
            return new int[]{previewWidth, previewHeight};
        }

        private int sampleVolume(double x, double y, double z) {
            double clampedZ = clamp(z, 0.0, slicePixels.size() - 1.0);
            int z0 = (int) Math.floor(clampedZ);
            int z1 = Math.min(slicePixels.size() - 1, z0 + 1);
            double fraction = clampedZ - z0;

            int value0 = sampleSlice(slicePixels.get(z0), x, y);
            int value1 = sampleSlice(slicePixels.get(z1), x, y);
            return (int) Math.round(value0 * (1.0 - fraction) + value1 * fraction);
        }

        private int sampleSlice(byte[] pixels, double x, double y) {
            double clampedX = clamp(x, 0.0, imageWidth - 1.0);
            double clampedY = clamp(y, 0.0, imageHeight - 1.0);

            int x0 = (int) Math.floor(clampedX);
            int y0 = (int) Math.floor(clampedY);
            int x1 = Math.min(imageWidth - 1, x0 + 1);
            int y1 = Math.min(imageHeight - 1, y0 + 1);
            double dx = clampedX - x0;
            double dy = clampedY - y0;

            int topLeft = pixels[y0 * imageWidth + x0] & 0xFF;
            int topRight = pixels[y0 * imageWidth + x1] & 0xFF;
            int bottomLeft = pixels[y1 * imageWidth + x0] & 0xFF;
            int bottomRight = pixels[y1 * imageWidth + x1] & 0xFF;

            double top = topLeft * (1.0 - dx) + topRight * dx;
            double bottom = bottomLeft * (1.0 - dx) + bottomRight * dx;
            return (int) Math.round(top * (1.0 - dy) + bottom * dy);
        }

        private double physicalRatio(double valueMm, double totalMm) {
            if (!Double.isFinite(totalMm) || totalMm <= 0.0) {
                return 0.5;
            }
            return clamp(valueMm / totalMm, 0.0, 1.0);
        }

        private double clamp(double value, double min, double max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private int toPreviewRgb(int value) {
            int gray = Math.max(0, Math.min(255, value));
            if (gray >= surfaceThreshold) {
                int r = Math.min(255, gray + 36);
                int g = Math.min(255, gray + 18);
                int b = Math.max(0, gray - 22);
                return (r << 16) | (g << 8) | b;
            }
            return (gray << 16) | (gray << 8) | gray;
        }

        private interface PlaneSampler {
            int sample(double xMm, double yMm);
        }
    }

    private static final class MprSlicePanel extends JPanel {
        private BufferedImage image;
        private String title;
        private String subtitle;
        private double crosshairX = 0.5;
        private double crosshairY = 0.5;

        private MprSlicePanel(String title) {
            this.title = title;
            this.subtitle = "";
            setPreferredSize(new Dimension(320, 170));
            setMinimumSize(new Dimension(220, 130));
            setBackground(new java.awt.Color(12, 15, 21));
            setBorder(BorderFactory.createLineBorder(new java.awt.Color(42, 48, 58)));
        }

        private void setImage(String title, String subtitle, BufferedImage image, double crosshairX, double crosshairY) {
            this.title = title;
            this.subtitle = subtitle;
            this.image = image;
            this.crosshairX = crosshairX;
            this.crosshairY = crosshairY;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();

            g.setColor(new java.awt.Color(12, 15, 21));
            g.fillRect(0, 0, width, height);

            g.setColor(new java.awt.Color(226, 231, 240));
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            g.drawString(title, 10, 18);

            g.setColor(new java.awt.Color(150, 158, 172));
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g.drawString(subtitle, 10, 34);

            if (image != null) {
                int top = 42;
                int availableWidth = Math.max(1, width - 20);
                int availableHeight = Math.max(1, height - top - 10);
                double scale = Math.min(
                        availableWidth / (double) image.getWidth(),
                        availableHeight / (double) image.getHeight()
                );
                int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (width - drawWidth) / 2;
                int y = top + (availableHeight - drawHeight) / 2;

                g.drawImage(image, x, y, drawWidth, drawHeight, null);
                drawCrosshair(g, x, y, drawWidth, drawHeight);
                g.setColor(new java.awt.Color(66, 75, 92));
                g.drawRect(x, y, drawWidth - 1, drawHeight - 1);
            }

            g.dispose();
        }

        private void drawCrosshair(Graphics2D g, int x, int y, int width, int height) {
            int cx = x + (int) Math.round(crosshairX * width);
            int cy = y + (int) Math.round(crosshairY * height);
            g.setColor(new java.awt.Color(59, 180, 220, 170));
            g.drawLine(x, cy, x + width, cy);
            g.drawLine(cx, y, cx, y + height);
            g.setColor(new java.awt.Color(255, 190, 86, 190));
            g.fillOval(cx - 2, cy - 2, 5, 5);
        }
    }

    private static final class MeshBuildResult {
        private final TriangleMesh[] meshes;
        private final float widthMm;
        private final float heightMm;
        private final float depthMm;
        private final float voxelSizeXmm;
        private final float voxelSizeYmm;
        private final float voxelSizeZmm;
        private final int activeVoxelCount;
        private final int triangleCount;

        private MeshBuildResult(TriangleMesh[] meshes, float widthMm, float heightMm, float depthMm,
                                float voxelSizeXmm, float voxelSizeYmm, float voxelSizeZmm,
                                int activeVoxelCount, int triangleCount) {
            this.meshes = meshes;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.depthMm = depthMm;
            this.voxelSizeXmm = voxelSizeXmm;
            this.voxelSizeYmm = voxelSizeYmm;
            this.voxelSizeZmm = voxelSizeZmm;
            this.activeVoxelCount = activeVoxelCount;
            this.triangleCount = triangleCount;
        }
    }
}
