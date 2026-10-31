package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
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
 * Checks the 曾使用过 grid and the page transitions between the three screens.
 *
 * <h2>Why this is measured rather than asserted on the code</h2>
 * <p>Three things a user notices here are runtime facts: how many cards end up in the
 * grid, whether deleting one shrinks it, and whether pushing a page actually leaves two
 * pages on screen mid-animation (which is what makes it read as a slide rather than a
 * jump). None of them is visible from reading the source.</p>
 */
public final class SelectionTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> { });
        Platform.setImplicitExit(false);
        pump();

        Path dir = Files.createTempDirectory("account-skin-pages");
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
        AtomicReference<SkinHistory.Entry> picked = new AtomicReference<>();
        AtomicReference<HistoryGridPage> pageRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        onFx(() -> {
            HistoryGridPage page = new HistoryGridPage(owner, "skin", picked::set, () -> { });
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(page), 1000, 800));
            stage.show();
            stageRef.set(stage);
            pageRef.set(page);
        });
        pump(10);

        System.out.println("=== 每件材质一张卡片 ===");
        check("one card per record", pageRef.get().cardCount() == 3);
        check("the cards show names",
                allNodes(pageRef.get()).stream().anyMatch(n -> n instanceof Label label
                        && "skin 1".equals(label.getText())));

        System.out.println();
        System.out.println("=== 删除只删一张卡 ===");
        com.jfoenix.controls.JFXButton deleteButton = findDeleteButton(pageRef.get());
        check("the card has a delete button", deleteButton != null);
        onFx(() -> deleteButton.fire());
        pump(6);
        check("one card was removed", pageRef.get().cardCount() == 2);
        check("the history shrank too", SkinHistory.get().count(owner, "skin") == 2);

        System.out.println();
        System.out.println("=== 点卡片选中并回调 ===");
        AtomicReference<Node> card = new AtomicReference<>();
        onFx(() -> card.set(firstCard(pageRef.get())));
        onFx(() -> card.get().fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null)));
        pump(4);
        check("picking a card reports the entry", picked.get() != null);

        onFx(() -> stageRef.get().close());
        pump(4);

        System.out.println();
        System.out.println("=== 页面切换有动画 ===");
        checkTransitions();

        System.out.println();
        System.out.println("=== 确认页的按钮在底部栏 ===");
        checkConfirmActions(account, dir);

        System.out.println();
        System.out.println("=== 另一个账户看不到这些 ===");
        check("their history is empty",
                SkinHistory.get().entries(SkinCapeDialog.ownerOf(offlineAccount("someone-else")), "skin")
                        .isEmpty());
    }

    /// Drives the page host directly: push a page and check that during the animation
    /// both pages are on screen and the incoming one is offset, which is what makes it
    /// read as a slide instead of an instant swap.
    private static void checkTransitions() throws Exception {
        // The launcher settings are not loaded in this harness, so AnimationUtils cannot
        // initialise; force the animation on to exercise the real transition path.
        PageHost.FORCE_ANIMATION_FOR_TEST = Boolean.TRUE;
        AtomicReference<PageHost> hostRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<Node> second = new AtomicReference<>();

        onFx(() -> {
            PageHost host = new PageHost();
            Node first = new Label("first");
            second.set(new Label("second"));
            host.setRoot(first);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(host), 800, 600));
            stage.show();
            stageRef.set(stage);
            hostRef.set(host);
        });
        pump(8);

        check("the host starts with one page",
                hostRef.get().getChildren().size() == 1);

        onFx(() -> hostRef.get().push(second.get()));
        // One pulse is enough to see the animation mid-flight; it lasts 200 ms.
        pump(1);
        int during = hostRef.get().getChildren().size();
        double offset = second.get().getTranslateX();
        check("pushing puts two pages on screen at once", during == 2);
        check("the incoming page starts offset to the right", offset > 0);
        check("the offset matches the launcher's own page transition (50 px)",
                Math.abs(offset - 50) < 0.5);

        // Let it finish.
        Thread.sleep(400);
        pump(4);
        check("the old page is removed when the animation ends",
                hostRef.get().getChildren().size() == 1);
        check("the new page lands at its final position",
                Math.abs(second.get().getTranslateX()) < 0.5);
        check("and is fully opaque",
                Math.abs(second.get().getOpacity() - 1.0) < 0.01);

        System.out.println();
        System.out.println("=== 返回是反方向的 ===");
        AtomicReference<Node> third = new AtomicReference<>();
        onFx(() -> {
            third.set(new Label("third"));
            hostRef.get().push(third.get());
        });
        Thread.sleep(400);
        pump(4);
        // Two pushes happened (second, then third), so there are still pages to go back to.
        check("a second page was pushed", hostRef.get().canGoBack());

        onFx(() -> hostRef.get().pop());
        pump(1);
        // After popping, the page coming back should slide in from the left (negative).
        check("the returning page starts offset to the left",
                hostRef.get().getChildren().size() == 2
                        && hostRef.get().getChildren().get(1).getTranslateX() < 0);
        check("and by the same distance as the launcher's transition",
                hostRef.get().getChildren().size() == 2
                        && Math.abs(hostRef.get().getChildren().get(1).getTranslateX() + 50) < 0.5);

        Thread.sleep(400);
        pump(4);
        check("popping settles back to one page",
                hostRef.get().getChildren().size() == 1);
        check("and the stack went back one level",
                hostRef.get().canGoBack());

        onFx(() -> stageRef.get().close());
        pump(4);
    }

    /// The confirm page must put 确认装备 next to 取消 in the dialog's action bar,
    /// not inside the page body: the body is the right-hand column of explanation text,
    /// where a primary button is easy to miss and sits in a different place than on
    /// every other page.
    private static void checkConfirmActions(Account account, Path dir) throws Exception {
        String owner = SkinCapeDialog.ownerOf(account);
        SkinHistory.Entry entry = SkinHistory.get().entries(owner, "skin").get(0);
        AtomicReference<SkinCapeDialog> dialogRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        onFx(() -> {
            SkinCapeDialog dialog = new SkinCapeDialog(account, SkinCapeDialog.Mode.SKIN, dir);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(dialog), 1000, 800));
            stage.show();
            stageRef.set(stage);
            dialogRef.set(dialog);
        });
        pump(10);

        // Walk into the confirm page the way the user does.
        onFx(() -> dialogRef.get().openHistoryForTest("skin"));
        pump(6);
        onFx(() -> dialogRef.get().openConfirmForTest(entry));
        pump(6);

        List<Node> nodes = allNodes(dialogRef.get());
        check("the confirm page is showing",
                nodes.stream().anyMatch(n -> n instanceof Label label
                        && label.getText() != null && label.getText().startsWith("装备皮肤")));

        // Find the action bar buttons by their style classes.
        Node accept = nodes.stream()
                .filter(n -> n instanceof com.jfoenix.controls.JFXButton)
                .filter(n -> n.getStyleClass().contains("dialog-accept"))
                .findFirst().orElse(null);
        // The page bodies also carry a 返回 button with the same style class, so the
        // cancel button is located as a sibling of the confirm button instead.
        Node cancel = accept == null || !(accept.getParent() instanceof HBox bar)
                ? null
                : bar.getChildren().stream()
                        .filter(n -> n instanceof com.jfoenix.controls.JFXButton)
                        .filter(n -> n.getStyleClass().contains("dialog-cancel"))
                        .findFirst().orElse(null);

        check("there is a confirm button", accept != null);
        check("and a cancel button", cancel != null);
        check("they are siblings in the same action bar",
                accept != null && cancel != null && accept.getParent() == cancel.getParent());
        check("the confirm button says 确认装备",
                accept instanceof com.jfoenix.controls.JFXButton button
                        && "确认装备".equals(button.getText()));
        check("confirm comes before cancel, like a standard dialog",
                accept != null && cancel != null
                        && ((HBox) accept.getParent()).getChildren().indexOf(accept)
                        < ((HBox) accept.getParent()).getChildren().indexOf(cancel));

        onFx(() -> stageRef.get().close());
        pump(4);
    }

    private static Node firstCard(Parent root) {
        for (Node node : allNodes(root))
            if (node instanceof StackPane pane && pane.getStyleClass().isEmpty()
                    && pane.getChildrenUnmodifiable().size() == 3)
                return node;
        return null;
    }

    private static com.jfoenix.controls.JFXButton findDeleteButton(Node root) {
        for (Node node : allNodes(root))
            if (node instanceof com.jfoenix.controls.JFXButton button && button.getGraphic() != null)
                return button;
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
