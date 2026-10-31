package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.yggdrasil.TextureModel;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

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
public final class HistoryConfirmPane extends StackPane {

    private final Account account;
    private final SkinHistory.Entry entry;
    private final boolean isCape;
    private final ToggleGroup formGroup = new ToggleGroup();
    private final ElytraSkinCanvas canvas;
    private Image texture;

    /// 装备成功后的回调，用来刷新上一层的列表。
    private final Runnable onApplied;

    public HistoryConfirmPane(Account account, SkinHistory.Entry entry, Runnable onApplied) {
        this.account = account;
        this.entry = entry;
        this.isCape = "cape".equals(entry.kind);
        this.onApplied = onApplied;

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        layout.setHeading(new Label((isCape ? "装备披风 · " : "装备皮肤 · ")
                + (entry.name != null ? entry.name : "(未命名)")));

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

        VBox right = new VBox(10, formTitle, formButtons, source);
        right.setAlignment(Pos.TOP_CENTER);
        right.setPrefWidth(240);
        right.setMinWidth(240);
        right.setMaxWidth(240);
        right.setPadding(new Insets(20, 0, 0, 12));

        HBox body = new HBox(16, previewPane, right);
        body.setPadding(new Insets(8));
        body.setAlignment(Pos.TOP_LEFT);
        layout.setBody(body);

        JFXButton confirm = new JFXButton("确认装备");
        confirm.getStyleClass().add("dialog-accept");
        confirm.setOnAction(e -> apply());
        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.getStyleClass().add("dialog-cancel");
        cancel.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        layout.setActions(confirm, cancel);

        FormToggles.installStyleListener(formGroup, this::refreshPreview);
        refreshPreview();
    }

    private void refreshPreview() {
        boolean second = FormToggles.isSecond(formGroup);
        if (isCape)
            canvas.setModel(accountSkin(), accountSlim(), texture, second);
        else
            canvas.setModel(texture, second, null, false);
    }

    /// 披风预览要穿在用户自己的角色上，所以取账户当前的皮肤。
    private Image accountSkin() {
        try {
            var binding = org.jackhuang.hmcl.game.TexturesLoader.skinBinding(account);
            var value = binding.getValue();
            if (value != null)
                return value.image();
        } catch (Throwable ignored) {
        }
        return org.jackhuang.hmcl.game.TexturesLoader.getDefaultSkinImage();
    }

    private boolean accountSlim() {
        try {
            if (account instanceof OfflineAccount offline && offline.getSkin() != null)
                return offline.getSkin().textureModel() == TextureModel.SLIM;
            var value = org.jackhuang.hmcl.game.TexturesLoader.skinBinding(account).getValue();
            return value != null && "slim".equals(value.metadata().get("model"));
        } catch (Throwable t) {
            return false;
        }
    }

    private String describeSource() {
        StringBuilder text = new StringBuilder("来源：");
        if (entry.tid > 0)
            text.append("LittleSkin 皮肤库");
        else
            text.append("本地文件");
        if (entry.author != null && !entry.author.isEmpty())
            text.append(" · ").append(entry.author);
        text.append("\n上次使用：").append(HistoryGridPane.describeAge(entry.usedAt));
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
            SkinApplier.applyCape(account, file, accountSlim(), this::done, HistoryConfirmPane::showError);
        } else {
            SkinApplier.applySkin(account, file, second, this::done, HistoryConfirmPane::showError);
        }
    }

    private void done() {
        Controllers.showToast("已装备" + (isCape ? "披风" : "皮肤"));
        fireEvent(new DialogCloseEvent());
        if (onApplied != null)
            onApplied.run();
    }

    private static void showError(String message) {
        Controllers.dialog(message, "装备失败", MessageType.ERROR);
    }
}
