package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks that a history row shows a selection border when picked.
 *
 * <h2>Why this is measured rather than asserted on the code</h2>
 * <p>The reported problem was that no border appeared on the character card. The cause
 * was that the style was computed inside {@code updateItem}, which JavaFX only calls
 * when the item changes — a click changes the selection, not the item, so the border
 * only showed up after scrolling. That is a runtime behaviour: the cell has to be
 * selected for real and its style read back.</p>
 */
public final class SelectionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        Path dir = Files.createTempDirectory("account-skin-selection");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SkinHistory.initialize(dir.resolve("history.json"));
            // 同一个账户对象既用来种数据、又用来开对话框：历史是按账户分的，
            // 两边算出来的 owner 必须一致，否则列表是空的。
            org.jackhuang.hmcl.auth.offline.OfflineAccount account = offlineAccount();
            seed(dir, account);
            run(dir, account);
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

    /// Puts two skins into the account's history so the list has rows to select.
    private static void seed(Path dir, org.jackhuang.hmcl.auth.Account account) throws Exception {
        String owner = SkinCapeDialog.ownerOf(account);
        for (int i = 1; i <= 2; i++) {
            Path file = dir.resolve("skin" + i + ".png");
            Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            SkinHistory.Entry entry = new SkinHistory.Entry();
            entry.owner = owner;
            entry.kind = "skin";
            entry.tid = i;
            entry.name = "skin " + i;
            entry.type = "steve";
            entry.path = file.toString();
            SkinHistory.get().record(entry);
        }
    }

    private static void run(Path dir, org.jackhuang.hmcl.auth.Account account) throws Exception {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<SkinCapeDialog> dialogRef = new AtomicReference<>();

        onFx(() -> {
            SkinCapeDialog dialog = new SkinCapeDialog(account, SkinCapeDialog.Mode.SKIN, dir);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(dialog), 1000, 800));
            stage.show();
            stageRef.set(stage);
            dialogRef.set(dialog);
        });
        pump(10);

        ListView<?> list = findListView(dialogRef.get());
        check("the dialog has a history list", list != null);
        if (list == null) {
            onFx(() -> stageRef.get().close());
            return;
        }
        check("the history list has rows", list.getItems().size() == 2);

        System.out.println();
        System.out.println("=== 未选中时没有边框 ===");
        String beforeStyle = cellStyle(list, 0);
        check("the first row starts without a border",
                beforeStyle != null && beforeStyle.contains("transparent"));

        System.out.println();
        System.out.println("=== 点一下，边框立刻出现 ===");
        onFx(() -> list.getSelectionModel().select(0));
        pump(6);
        String afterStyle = cellStyle(list, 0);
        check("the selected row gets a border colour",
                afterStyle != null && afterStyle.contains("-monet-primary"));
        check("the border is two pixels", afterStyle != null && afterStyle.contains("-fx-border-width: 2"));
        check("it is no longer transparent",
                afterStyle != null && !afterStyle.contains("border-color: transparent"));
        check("it gets a tinted background", afterStyle != null && afterStyle.contains("background-color"));

        System.out.println();
        System.out.println("=== 换一行，边框跟着走 ===");
        onFx(() -> list.getSelectionModel().select(1));
        pump(6);
        String firstAfter = cellStyle(list, 0);
        String secondAfter = cellStyle(list, 1);
        check("the previously selected row lost its border",
                firstAfter != null && firstAfter.contains("border-color: transparent"));
        check("the newly selected row has one",
                secondAfter != null && secondAfter.contains("-monet-primary"));

        onFx(() -> stageRef.get().close());
        pump(4);

        System.out.println();
        System.out.println("=== 换一个账户，列表跟着换 ===");
        // Two different offline accounts must not see each other's history: this is the
        // whole point of the per-account change, so it is checked through the real dialog.
        AtomicReference<Stage> secondStage = new AtomicReference<>();
        AtomicReference<SkinCapeDialog> secondDialog = new AtomicReference<>();
        onFx(() -> {
            SkinCapeDialog dialog = new SkinCapeDialog(
                    offlineAccount("someone-else"), SkinCapeDialog.Mode.SKIN, dir);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(dialog), 1000, 800));
            stage.show();
            secondStage.set(stage);
            secondDialog.set(dialog);
        });
        pump(10);

        ListView<?> otherList = findListView(secondDialog.get());
        check("the other account's list is empty", otherList != null && otherList.getItems().isEmpty());

        onFx(() -> secondStage.get().close());
        pump(4);
    }

    /// Reads the inline style of the cell at {@code index}, which is what paints the border.
    private static String cellStyle(ListView<?> list, int index) throws Exception {
        AtomicReference<String> style = new AtomicReference<>();
        onFx(() -> {
            for (Node node : allNodes(list)) {
                if (node instanceof ListCell<?> cell && cell.getIndex() == index && !cell.isEmpty()) {
                    style.set(cell.getStyle());
                    return;
                }
            }
        });
        return style.get();
    }

    private static ListView<?> findListView(Parent root) throws Exception {
        AtomicReference<ListView<?>> found = new AtomicReference<>();
        onFx(() -> {
            for (Node node : allNodes(root)) {
                if (node instanceof ListView<?> list) {
                    found.set(list);
                    return;
                }
            }
        });
        return found.get();
    }

    private static List<Node> allNodes(Node root) {
        List<Node> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable())
                collect(child, out);
    }

    private static org.jackhuang.hmcl.auth.offline.OfflineAccount offlineAccount() {
        return offlineAccount("qwen");
    }

    private static org.jackhuang.hmcl.auth.offline.OfflineAccount offlineAccount(String name) {
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
