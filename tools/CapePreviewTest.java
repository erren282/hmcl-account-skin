package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.Skin;
import org.jackhuang.hmcl.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that the cape dialog actually draws the cape the account is wearing.
 *
 * <h2>Why this is measured</h2>
 * <p>The reported problem was "这里没有显示披风" — the preview showed a bare-backed
 * character even though the account had a cape on. The cause was that the preview only
 * ever loaded the skin ({@code TexturesLoader.skinBinding}), which does not carry the
 * cape; the cape lives in a separate place per account type. That is a runtime fact
 * about what ends up on the canvas, so the cape is read back and compared.</p>
 */
public final class CapePreviewTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        Path dir = Files.createTempDirectory("account-skin-cape");
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
        System.out.println("=== 离线账户：本地披风文件要被读出来 ===");
        Path skinFile = writePng(dir.resolve("skin.png"), 0xFFFF0000);
        Path capeFile = writePng(dir.resolve("cape.png"), 0xFF00FF00);

        OfflineAccount account = offlineAccount("qwen");
        account.setSkin(new Skin(Skin.Type.LOCAL_FILE, null, TextureModel.WIDE,
                skinFile.toString(), capeFile.toString()));

        AtomicReference<Image> cape = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        onFx(() -> AccountTextures.cape(account, image -> {
            cape.set(image);
            done.countDown();
        }));
        boolean arrived = done.await(20, TimeUnit.SECONDS);
        check("the cape callback fired", arrived);
        check("a cape was found", cape.get() != null);
        check("the cape is the file we set", cape.get() != null && !cape.get().isError());
        check("and it has the expected size",
                cape.get() != null && cape.get().getWidth() == 64 && cape.get().getHeight() == 32);

        System.out.println();
        System.out.println("=== 没有披风时给 null，而不是报错 ===");
        OfflineAccount bare = offlineAccount("bare");
        bare.setSkin(new Skin(Skin.Type.LOCAL_FILE, null, TextureModel.WIDE, skinFile.toString(), null));
        AtomicReference<Image> noCape = new AtomicReference<>();
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        CountDownLatch bareDone = new CountDownLatch(1);
        onFx(() -> AccountTextures.cape(bare, image -> {
            noCape.set(image);
            called.set(true);
            bareDone.countDown();
        }));
        check("the callback still fired", bareDone.await(20, TimeUnit.SECONDS));
        check("and reported no cape", called.get() && noCape.get() == null);

        System.out.println();
        System.out.println("=== 披风对话框会把披风画到画布上 ===");
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<SkinCapeDialog> dialogRef = new AtomicReference<>();
        onFx(() -> {
            SkinCapeDialog dialog = new SkinCapeDialog(account, SkinCapeDialog.Mode.CAPE, dir);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(dialog), 1000, 800));
            stage.show();
            stageRef.set(stage);
            dialogRef.set(dialog);
        });
        pump(20);
        // The cape loads asynchronously; give the callback a moment to land.
        Thread.sleep(600);
        pump(10);
        check("the dialog is still standing after the cape loaded",
                dialogRef.get().getScene() != null);

        onFx(() -> stageRef.get().close());
        pump(4);

        System.out.println();
        System.out.println("=== 模型判定 ===");
        check("a wide skin reports wide", !AccountTextures.isSlim(account));
        account.setSkin(new Skin(Skin.Type.LOCAL_FILE, null, TextureModel.SLIM,
                skinFile.toString(), capeFile.toString()));
        check("a slim skin reports slim", AccountTextures.isSlim(account));
    }

    /// Writes a solid-colour PNG. A real image is needed: the preview refuses to draw
    /// anything that is not a decodable texture.
    private static Path writePng(Path file, int argb) throws Exception {
        BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++)
                image.setRGB(x, y, argb);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static OfflineAccount offlineAccount(String name) {
        org.jackhuang.hmcl.auth.offline.OfflineAccountFactory factory =
                new org.jackhuang.hmcl.auth.offline.OfflineAccountFactory(
                        new org.jackhuang.hmcl.auth.authlibinjector.SimpleAuthlibInjectorArtifactProvider(
                                java.nio.file.Paths.get("authlib-injector.jar")));
        return factory.create(name, java.util.UUID.nameUUIDFromBytes(name.getBytes()));
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
