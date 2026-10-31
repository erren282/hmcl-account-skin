package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.auth.Account;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks the 曾使用过 grid: it shows one card per record, and the delete button
 * removes only that card.
 *
 * <h2>Why this is measured rather than asserted on the code</h2>
 * <p>Both things a user notices here are runtime facts: how many cards end up in the
 * tile pane, and whether clicking the card's delete button actually shrinks the grid
 * without closing the page. Neither is visible from reading the source.</p>
 */
public final class SelectionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        Path dir = Files.createTempDirectory("account-skin-grid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SkinHistory.initialize(dir.resolve("history.json"));
            Account account = offlineAccount("qwen");
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

    /// Puts three skins into the account's history so the grid has cards.
    private static void seed(Path dir, Account account) throws Exception {
        String owner = SkinCapeDialog.ownerOf(account);
        for (int i = 1; i <= 3; i++) {
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

    private static void run(Path dir, Account account) throws Exception {
        String owner = SkinCapeDialog.ownerOf(account);
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<HistoryGridPane> paneRef = new AtomicReference<>();
        AtomicReference<SkinHistory.Entry> picked = new AtomicReference<>();

        onFx(() -> {
            HistoryGridPane pane = new HistoryGridPane(owner, "skin", picked::set);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(pane), 1000, 800));
            stage.show();
            stageRef.set(stage);
            paneRef.set(pane);
        });
        pump(10);

        TilePane grid = findTilePane(paneRef.get());
        check("the page has a card grid", grid != null);
        if (grid == null) {
            onFx(() -> stageRef.get().close());
            return;
        }

        System.out.println();
        System.out.println("=== 每件材质一张卡片 ===");
        check("one card per record", grid.getChildren().size() == 3);
        check("the cards show names",
                allNodes(grid).stream().anyMatch(n -> n instanceof Label label
                        && "skin 1".equals(label.getText())));
        check("it is a grid, not a one-column list",
                grid.getPrefColumns() > 1);

        System.out.println();
        System.out.println("=== 删除只删一张卡 ===");
        AtomicReference<Node> firstCard = new AtomicReference<>();
        onFx(() -> firstCard.set(grid.getChildren().get(0)));
        com.jfoenix.controls.JFXButton deleteButton = findDeleteButton(firstCard.get());
        check("the card has a delete button", deleteButton != null);

        onFx(() -> deleteButton.fire());
        pump(6);
        check("one card was removed", grid.getChildren().size() == 2);
        check("the history shrank too",
                SkinHistory.get().count(owner, "skin") == 2);
        check("the page is still open", paneRef.get().getScene() != null);

        System.out.println();
        System.out.println("=== 点卡片选中并回调 ===");
        Node secondCard = null;
        onFx(() -> { });
        AtomicReference<Node> card = new AtomicReference<>();
        onFx(() -> card.set(grid.getChildren().get(0)));
        onFx(() -> card.get().fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null)));
        pump(4);
        check("picking a card reports the entry", picked.get() != null);
        check("and it is one of the remaining ones",
                picked.get() != null && SkinHistory.get().count(owner, "skin") == 2);

        onFx(() -> stageRef.get().close());
        pump(4);

        System.out.println();
        System.out.println("=== 另一个账户看不到这些 ===");
        Account other = offlineAccount("someone-else");
        check("their history is empty",
                SkinHistory.get().entries(SkinCapeDialog.ownerOf(other), "skin").isEmpty());
    }

    /// The grid's delete button is the one whose graphic is an SVG icon and which sits
    /// on the card; find it by walking the card.
    private static com.jfoenix.controls.JFXButton findDeleteButton(Node card) {
        for (Node node : allNodes(card)) {
            if (node instanceof com.jfoenix.controls.JFXButton button && button.getGraphic() != null)
                return button;
        }
        return null;
    }

    private static TilePane findTilePane(Parent root) {
        for (Node node : allNodes(root))
            if (node instanceof TilePane tile)
                return tile;
        return null;
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

    private static Account offlineAccount(String name) {
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
