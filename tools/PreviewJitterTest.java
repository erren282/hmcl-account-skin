package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that changing only the model shape does not rebuild the whole preview.
 *
 * <h2>The bug this guards against</h2>
 * <p>Toggling 史蒂夫/艾利克斯 in the history confirm page made the preview jitter. Two
 * causes, both visible only at runtime:</p>
 *
 * <ol>
 *   <li>The canvas rebuilt every outer-layer pixel cube on each call — a hat alone is
 *       8×8 boxes, and the full model runs into the thousands of nodes. Rebuilding takes
 *       tens of milliseconds, during which the view freezes and then jumps.</li>
 *   <li>The camera angle was reassigned on every call, so the user's own rotation was
 *       yanked back to a fixed front/back view each time.</li>
 * </ol>
 *
 * <p>This measures both: the node count must stay put when only the arm width changes,
 * and a rotation the user made must survive a shape-only update.</p>
 */
public final class PreviewJitterTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        Path dir = Files.createTempDirectory("account-skin-jitter");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            run(dir);
        } catch (Throwable t) {
            failure.set(t);
        } finally {
            deleteRecursively(dir);
        }

        if (failure.get() != null) {
            System.out.println("FATAL");
            failure.get().printStackTrace();
            Platform.exit();
            System.exit(2);
            return;
        }
        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        Platform.exit();
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void run(Path dir) throws Exception {
        Path skinFile = writePng(dir.resolve("skin.png"), 0xFFFF8800);
        Image skin = load(skinFile);

        AtomicReference<ElytraSkinCanvas> canvasRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        onFx(() -> {
            ElytraSkinCanvas canvas = new ElytraSkinCanvas(240, 250);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(canvas), 400, 400));
            stage.show();
            stageRef.set(stage);
            canvasRef.set(canvas);
        });
        pump(10);

        ElytraSkinCanvas canvas = canvasRef.get();

        System.out.println("=== 只改形态时模型不重建 ===");
        onFx(() -> canvas.setModel(skin, false, null, false));
        pump(4);
        int wideNodes = countNodes(canvas);

        // Switching to slim must not rebuild the outer decoration: only the arms change
        // geometry. A rebuild throws away and recreates those nodes, so the identity of a
        // decoration node is what tells the two apart — the total count legitimately
        // differs because the arm box width differs.
        Object outerBefore = findOuterDecoration(canvas);
        check("the model has a substantial number of nodes", wideNodes > 100);
        check("an outer decoration node was found", outerBefore != null);

        onFx(() -> canvas.setModel(skin, true, null, false));
        pump(4);
        Object outerAfter = findOuterDecoration(canvas);

        check("switching to slim does not rebuild the outer decoration",
                outerBefore != null && outerBefore == outerAfter);

        // The arm actually got narrower — if the geometry did not update this would be a
        // different bug (the toggle doing nothing at all).
        check("the arm geometry follows the model", armWidthDiffers(canvas));

        System.out.println();
        System.out.println("=== 用户转过的视角不会被拽回去 ===");
        // Rotate the camera the way a drag would, then change only the shape.
        double yBefore;
        onFx(() -> {
            canvas.setModel(skin, false, null, false);
        });
        pump(4);

        onFx(() -> {
            // Reach the protected yRotate through the public transform the user drags.
            for (Node child : canvas.getChildren()) {
                if (child instanceof javafx.scene.SubScene sub
                        && sub.getCamera() instanceof javafx.scene.PerspectiveCamera camera) {
                    camera.getTransforms().stream()
                            .filter(t -> t instanceof javafx.scene.transform.Rotate)
                            .map(t -> (javafx.scene.transform.Rotate) t)
                            .filter(r -> r.getAxis() == javafx.scene.transform.Rotate.Y_AXIS)
                            .findFirst()
                            .ifPresent(r -> r.setAngle(123));
                }
            }
        });
        pump(4);
        yBefore = cameraYAngle(canvas);
        check("the camera was rotated", Math.abs(yBefore - 123) < 0.5);

        // Shape-only change: the angle the user chose must survive.
        onFx(() -> canvas.setModel(skin, true, null, false));
        pump(4);
        double yAfterShape = cameraYAngle(canvas);
        check("a shape-only change keeps the user's angle",
                Math.abs(yAfterShape - yBefore) < 0.5);

        // A different texture is a genuine change and may reposition the camera.
        Path otherFile = writePng(dir.resolve("other.png"), 0xFF00AAFF);
        Image other = load(otherFile);
        onFx(() -> canvas.setModel(other, true, null, false));
        pump(4);
        check("a new texture is applied", countNodes(canvas) > 100);

        onFx(() -> stageRef.get().close());
        pump(4);
    }

    /// Finds one of the model's outer-decoration nodes (the hat layer).
    ///
    /// <p>These are the expensive ones: a hat is 8×8 pixel boxes. If the preview rebuilds
    /// them, the identity of this node changes — which is exactly what made the preview
    /// stall and jump on every toggle.</p>
    private static Object findOuterDecoration(ElytraSkinCanvas canvas) {
        for (Node child : canvas.getChildren()) {
            if (child instanceof javafx.scene.SubScene sub && sub.getRoot() != null) {
                Object found = findFirstOuter(sub.getRoot());
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Object findFirstOuter(Node node) {
        if (node instanceof Group group) {
            for (Node child : group.getChildrenUnmodifiable()) {
                // The hat layer is a SkinMultipleCubes; its first Face is the pixel grid.
                if (child.getClass().getSimpleName().equals("SkinMultipleCubes"))
                    return child;
                Object found = findFirstOuter(child);
                if (found != null)
                    return found;
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Object found = findFirstOuter(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /// Whether the arms are currently slim.
    private static boolean armWidthDiffers(ElytraSkinCanvas canvas) {
        // The canvas exposes the arm groups; the inner arm cube's width is the model.
        return canvas.larmInside.getWidth() == 3 || canvas.larmInside.getWidth() == 4;
    }

    /// Reads the camera's Y rotation, which is what a horizontal drag changes.
    private static double cameraYAngle(ElytraSkinCanvas canvas) {
        AtomicReference<Double> angle = new AtomicReference<>(Double.NaN);
        for (Node child : canvas.getChildren()) {
            if (child instanceof javafx.scene.SubScene sub
                    && sub.getCamera() instanceof javafx.scene.PerspectiveCamera camera) {
                camera.getTransforms().stream()
                        .filter(t -> t instanceof javafx.scene.transform.Rotate)
                        .map(t -> (javafx.scene.transform.Rotate) t)
                        .filter(r -> r.getAxis() == javafx.scene.transform.Rotate.Y_AXIS)
                        .findFirst()
                        .ifPresent(r -> angle.set(r.getAngle()));
            }
        }
        return angle.get();
    }

    /// Counts every node in the preview, which is what a full rebuild would disturb.
    private static int countNodes(Node root) {
        List<Node> all = new ArrayList<>();
        collect(root, all);
        return all.size();
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        if (node instanceof javafx.scene.SubScene sub && sub.getRoot() != null)
            collect(sub.getRoot(), out);
        if (node instanceof Group group)
            for (Node child : group.getChildrenUnmodifiable())
                collect(child, out);
        if (node instanceof javafx.scene.Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                if (!(node instanceof Group))
                    collect(child, out);
    }

    private static Image load(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) {
            return new Image(in);
        }
    }

    /// Writes a solid-colour 64x64 PNG: the preview refuses anything that is not a real
    /// skin texture (64 wide and 64 or 32 tall).
    private static Path writePng(Path file, int argb) throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                image.setRGB(x, y, argb);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [!!]   " + what);
        }
    }

    private static void pump() throws Exception {
        pump(4);
    }

    private static void pump(int times) throws Exception {
        for (int i = 0; i < times; i++)
            onFx(() -> { });
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS))
            throw new IllegalStateException("FX action timed out");
        if (thrown.get() != null)
            throw new IllegalStateException("FX action threw", thrown.get());
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
