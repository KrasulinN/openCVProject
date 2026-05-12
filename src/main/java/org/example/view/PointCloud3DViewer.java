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

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PointCloud3DViewer extends JFXPanel {
    private static final AtomicBoolean JAVAFX_INITIALIZED = new AtomicBoolean(false);
    private static final int BACKGROUND_MARGIN = 14;
    private static final int BACKGROUND_MAX_THRESHOLD = 48;
    private static final int SURFACE_THRESHOLD = 58;
    private static final int VOXEL_STEP_XY = 4;
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

        AmbientLight ambientLight = new AmbientLight(Color.color(0.92, 0.94, 0.98));
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

        System.out.println("\n========== ЗАПУСК TRUE 3D MESH ==========");
        System.out.println("Количество слайсов: " + sortedItems.size());
        System.out.println("Размер изображения: " + imageWidth + "x" + imageHeight);
        System.out.println("PixelSpacing: X=" + pixelSpacingX + " мм, Y=" + pixelSpacingY + " мм");
        System.out.println("Расстояние между слайсами (Z): " + sliceThickness + " мм");
        System.out.println("Порог поверхности: " + SURFACE_THRESHOLD);
        System.out.println("Шаг по XY: " + VOXEL_STEP_XY + " пикс.");
        System.out.println("Шаг по Z: " + VOXEL_STEP_Z + " срез.");
    }

    private PhongMaterial createMeshMaterial(int bucket) {
        double intensity = bucket / (double) Math.max(1, INTENSITY_BUCKETS - 1);
        PhongMaterial material = new PhongMaterial();
        int shade = (int) Math.round(55 + intensity * 200);
        material.setDiffuseColor(Color.rgb(shade, shade, shade));
        material.setSpecularColor(Color.rgb(
                Math.min(255, shade + 16),
                Math.min(255, shade + 16),
                Math.min(255, shade + 16)
        ));
        return material;
    }

    public void setVisibleSliceCount(int count) {
        int safeCount = Math.max(1, Math.min(count, sortedItems.size()));
        visibleSliceCount = safeCount;
        rebuildMeshAsync(safeCount);
    }

    private void rebuildMeshAsync(int sliceCount) {
        int buildVersion = meshBuildVersion.incrementAndGet();
        Thread worker = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            MeshBuildResult result = buildSurfaceMesh(sliceCount);
            long endTime = System.currentTimeMillis();
            Platform.runLater(() -> {
                if (meshBuildVersion.get() != buildVersion || meshGroup == null) {
                    return;
                }
                meshGroup.getChildren().clear();
                for (int bucket = 0; bucket < result.meshes.length; bucket++) {
                    TriangleMesh mesh = result.meshes[bucket];
                    if (mesh.getFaces().size() == 0) {
                        continue;
                    }
                    MeshView meshView = new MeshView(mesh);
                    meshView.setCullFace(CullFace.BACK);
                    meshView.setDrawMode(DrawMode.FILL);
                    meshView.setMaterial(createMeshMaterial(bucket));
                    meshGroup.getChildren().add(meshView);
                }
                double maxDimension = Math.max(result.widthMm, Math.max(result.heightMm, result.depthMm));
                camera.setTranslateX(0);
                camera.setTranslateY(0);
                camera.setTranslateZ(-Math.max(maxDimension * 2.4, 600));

                System.out.println("\n========== MESH ГОТОВ ==========");
                System.out.println("Слоев в mesh: " + sliceCount + "/" + sortedItems.size());
                System.out.println("Активных вокселей: " + result.activeVoxelCount);
                System.out.println("Треугольников: " + result.triangleCount);
                System.out.println("Время построения: " + (endTime - startTime) + " мс");
            });
        }, "true-3d-mesh-builder");
        worker.setDaemon(true);
        worker.start();
    }

    private MeshBuildResult buildSurfaceMesh(int sliceCount) {
        int sampledDepth = Math.max(1, (int) Math.ceil(sliceCount / (double) VOXEL_STEP_Z));
        int sampledWidth = Math.max(1, (int) Math.ceil(imageWidth / (double) VOXEL_STEP_XY));
        int sampledHeight = Math.max(1, (int) Math.ceil(imageHeight / (double) VOXEL_STEP_XY));

        boolean[] occupancy = new boolean[sampledWidth * sampledHeight * sampledDepth];
        byte[] intensityBuckets = new byte[occupancy.length];
        int activeVoxelCount = populateOccupancy(occupancy, intensityBuckets, sampledWidth, sampledHeight, sampledDepth, sliceCount);

        float voxelSizeX = (float) (pixelSpacingX * VOXEL_STEP_XY);
        float voxelSizeY = (float) (pixelSpacingY * VOXEL_STEP_XY);
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
                        triangleCount += addQuad(mesh,
                                x0, y0, z0,
                                x0, y1, z0,
                                x0, y1, z1,
                                x0, y0, z1);
                    }
                    if (!isOccupied(occupancy, x + 1, y, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh,
                                x1, y0, z1,
                                x1, y1, z1,
                                x1, y1, z0,
                                x1, y0, z0);
                    }
                    if (!isOccupied(occupancy, x, y - 1, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh,
                                x0, y0, z1,
                                x1, y0, z1,
                                x1, y0, z0,
                                x0, y0, z0);
                    }
                    if (!isOccupied(occupancy, x, y + 1, z, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh,
                                x0, y1, z0,
                                x1, y1, z0,
                                x1, y1, z1,
                                x0, y1, z1);
                    }
                    if (!isOccupied(occupancy, x, y, z - 1, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh,
                                x0, y0, z0,
                                x1, y0, z0,
                                x1, y1, z0,
                                x0, y1, z0);
                    }
                    if (!isOccupied(occupancy, x, y, z + 1, sampledWidth, sampledHeight, sampledDepth)) {
                        triangleCount += addQuad(mesh,
                                x0, y1, z1,
                                x1, y1, z1,
                                x1, y0, z1,
                                x0, y0, z1);
                    }
                }
            }
        }

        return new MeshBuildResult(meshes, widthMm, heightMm, depthMm, activeVoxelCount, triangleCount);
    }

    private int populateOccupancy(boolean[] occupancy, byte[] intensityBuckets,
                                  int sampledWidth, int sampledHeight, int sampledDepth, int sliceCount) {
        int active = 0;

        for (int z = 0; z < sampledDepth; z++) {
            int sliceIndex = Math.min(sliceCount - 1, z * VOXEL_STEP_Z);
            SeriesImageItem item = sortedItems.get(sliceIndex);
            byte[] grayscale = toGrayscaleBytes(item.getImage());
            boolean[] backgroundMask = buildBackgroundMask(grayscale, imageWidth, imageHeight);

            for (int y = 0; y < sampledHeight; y++) {
                int srcY = Math.min(imageHeight - 1, y * VOXEL_STEP_XY + VOXEL_STEP_XY / 2);
                for (int x = 0; x < sampledWidth; x++) {
                    int srcX = Math.min(imageWidth - 1, x * VOXEL_STEP_XY + VOXEL_STEP_XY / 2);
                    int srcIndex = srcY * imageWidth + srcX;
                    int intensity = grayscale[srcIndex] & 0xFF;
                    if (!backgroundMask[srcIndex] && intensity >= SURFACE_THRESHOLD) {
                        int occupancyIndex = occupancyIndex(x, y, z, sampledWidth, sampledHeight);
                        occupancy[occupancyIndex] = true;
                        intensityBuckets[occupancyIndex] = (byte) intensityToBucket(intensity);
                        active++;
                    }
                }
            }
        }

        return active;
    }

    private byte[] toGrayscaleBytes(Mat image) {
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

    private int intensityToBucket(int intensity) {
        int normalized = Math.max(0, intensity - SURFACE_THRESHOLD);
        int range = Math.max(1, 255 - SURFACE_THRESHOLD);
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

            JFrame frame = new JFrame(title.replace("облако точек", "true 3D mesh"));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(1080, 860);
            frame.setLocationRelativeTo(null);

            PointCloud3DViewer viewer = new PointCloud3DViewer(items, title);
            frame.add(viewer, BorderLayout.CENTER);

            int totalSlices = Math.max(1, items.size());
            JLabel sliceLabel = new JLabel("Слоев в mesh: " + totalSlices + " / " + totalSlices);
            JSlider sliceSlider = new JSlider(SwingConstants.HORIZONTAL, 1, totalSlices, totalSlices);
            sliceSlider.addChangeListener(event -> {
                int value = sliceSlider.getValue();
                sliceLabel.setText("Слоев в mesh: " + value + " / " + totalSlices);
                viewer.setVisibleSliceCount(value);
            });

            JPanel controlsPanel = new JPanel(new BorderLayout(10, 0));
            controlsPanel.add(new JLabel("Глубина mesh по слоям"), BorderLayout.WEST);
            controlsPanel.add(sliceSlider, BorderLayout.CENTER);
            controlsPanel.add(sliceLabel, BorderLayout.EAST);

            JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            infoPanel.add(new JLabel("Левая кнопка: вращение | Колесо: зум"));

            JPanel southPanel = new JPanel(new BorderLayout(0, 6));
            southPanel.add(controlsPanel, BorderLayout.NORTH);
            southPanel.add(infoPanel, BorderLayout.SOUTH);
            frame.add(southPanel, BorderLayout.SOUTH);

            frame.setVisible(true);
        });
    }

    private static final class MeshBuildResult {
        private final TriangleMesh[] meshes;
        private final float widthMm;
        private final float heightMm;
        private final float depthMm;
        private final int activeVoxelCount;
        private final int triangleCount;

        private MeshBuildResult(TriangleMesh[] meshes, float widthMm, float heightMm, float depthMm,
                                int activeVoxelCount, int triangleCount) {
            this.meshes = meshes;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.depthMm = depthMm;
            this.activeVoxelCount = activeVoxelCount;
            this.triangleCount = triangleCount;
        }
    }
}
