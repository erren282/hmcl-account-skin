package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 确认页：选定一件材质后，调整形态并装备。
 *
 * <h2>为什么单独一页</h2>
 * <p>网格页的任务是“挑哪一件”，确认页的任务是“怎么穿”。挤在一起的话，
 * 挑的时候看不到形态效果，改形态时又看不清网格。分开之后每一步都只有一件事。</p>
 *
 * <h2>形态</h2>
 * <p>皮肤可切宽臂/纤细，披风可切普通/鞘翅，左侧预览实时跟着变。
 * 进入页面时按历史里记下的形态初始化——用户当初怎么穿的，就先给回什么。</p>
 */
final class HistoryConfirmPage extends VBox {

    private final Account account;
    private final SkinHistory.Entry entry;
    private final boolean isCape;
    private final ToggleGroup formGroup = new ToggleGroup();
    private final ElytraSkinCanvas canvas;
    private final Image texture;

    private boolean capeRequested;
    private boolean capeResolved;
    private Image capeImage;

    /**
     * @param onApplied 装备成功后的回调，用来回到来源页
     * @param onBack    返回上一页
     */
    HistoryConfirmPage(Account account, SkinHistory.Entry entry,
                       Runnable onApplied, Runnable onBack) {
        this.account = account;
        this.entry = entry;
        this.isCape = "cape".equals(entry.kind);

        setSpacing(10);
        setPadding(new Insets(16, 18, 12, 18));

        // ---- 标题栏 ----
        JFXButton back = new JFXButton("返回");
        back.getStyleClass().add("dialog-cancel");
        back.setOnAction(e -> onBack.run());

        Label title = new Label((isCape ? "装备披风 · " : "装备皮肤 · ")
                + (entry.name != null ? entry.name : "(未命名)"));
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        HBox header = new HBox(12, back, title);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- 左侧：3D 预览 ----
        canvas = new ElytraSkinCanvas(240, 250);
        StackPane previewPane = new StackPane(canvas);
        previewPane.setPrefSize(244, 254);
        previewPane.setMinSize(244, 254);
        previewPane.setMaxSize(244, 254);

        texture = loadTexture(entry.path);

        // ---- 右侧：形态 + 说明 ----
        Label formTitle = new Label(isCape ? "披风形态" : "手臂模型");
        formTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");

        HBox formButtons = new HBox(8);
        formButtons.setAlignment(Pos.CENTER);
        if (isCape) {
            formButtons.getChildren().setAll(
                    FormToggles.create(formGroup, "普通形态", false, !entry.elytra),
                    FormToggles.create(formGroup, "鞘翅形态", true, entry.elytra));
        } else {
            formButtons.getChildren().setAll(
                    FormToggles.create(formGroup, "史蒂夫形态", false, !entry.slim),
                    FormToggles.create(formGroup, "艾利克斯形态", true, entry.slim));
        }

        Label source = new Label(describeSource());
        source.setWrapText(true);
        source.setMaxWidth(230);
        source.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        JFXButton confirm = new JFXButton("确认装备");
        confirm.getStyleClass().add("dialog-accept");
        confirm.setOnAction(e -> apply());

        VBox right = new VBox(10, formTitle, formButtons, source, confirm);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPrefWidth(240);
        right.setMinWidth(240);
        right.setMaxWidth(240);
        right.setPadding(new Insets(20, 0, 0, 12));

        HBox body = new HBox(16, previewPane, right);
        body.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(body, Priority.ALWAYS);

        this.onApplied = onApplied;
        getChildren().setAll(header, body);

        FormToggles.installStyleListener(formGroup, this::refreshPreview);
        refreshPreview();
    }

    private final Runnable onApplied;

    private void refreshPreview() {
        boolean second = FormToggles.isSecond(formGroup);
        if (isCape) {
            canvas.setModel(AccountTextures.skin(account), AccountTextures.isSlim(account), texture, second);
        } else {
            // 皮肤预览也把身上的披风画出来：不然换了皮肤看不出和披风搭不搭。
            canvas.setModel(texture, second, accountCape(), false);
        }
    }

    /// 账户当前穿着的披风。异步取，取到后重画一次。
    private Image accountCape() {
        if (capeResolved)
            return capeImage;
        if (!capeRequested) {
            capeRequested = true;
            AccountTextures.cape(account, image -> {
                capeResolved = true;
                capeImage = image;
                refreshPreview();
            });
        }
        return null;
    }

    private String describeSource() {
        StringBuilder text = new StringBuilder("来源：");
        if (entry.tid > 0)
            text.append("LittleSkin 皮肤库");
        else
            text.append("本地文件");
        if (entry.author != null && !entry.author.isEmpty())
            text.append(" · ").append(entry.author);
        text.append("\n上次使用：").append(HistoryGridPage.describeAge(entry.usedAt));
        return text.toString();
    }

    private static Image loadTexture(String path) {
        try (var in = Files.newInputStream(Path.of(path))) {
            return new Image(in);
        } catch (Throwable e) {
            return null;
        }
    }

    private void apply() {
        Path file = Path.of(entry.path);
        if (!Files.isRegularFile(file)) {
            showError("这件材质已不在本地，请重新选择");
            return;
        }
        boolean second = FormToggles.isSecond(formGroup);

        // 记下这次的形态：下次再从历史里选它，就按这次的样子回来。
        entry.slim = !isCape && second;
        entry.elytra = isCape && second;
        SkinHistory.get().record(entry);

        if (isCape) {
            SkinApplier.applyCape(account, file, AccountTextures.isSlim(account), this::done,
                    HistoryConfirmPage::showError);
        } else {
            SkinApplier.applySkin(account, file, second, this::done, HistoryConfirmPage::showError);
        }
    }

    private void done() {
        Controllers.showToast("已装备" + (isCape ? "披风" : "皮肤"));
        if (onApplied != null)
            onApplied.run();
    }

    private static void showError(String message) {
        Controllers.dialog(message, "装备失败", MessageType.ERROR);
    }
}
