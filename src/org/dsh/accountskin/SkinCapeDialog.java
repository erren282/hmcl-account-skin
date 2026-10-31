package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * 皮肤/披风切换对话框：一个 {@link PageHost} 装三层页面。
 *
 * <h2>三层各管一件事</h2>
 * <ul>
 *   <li>来源页：从哪来——皮肤库 / 本地文件 / 曾使用过；</li>
 *   <li>{@link HistoryGridPage}：挑哪一件（网格，可删除）；</li>
 *   <li>{@link HistoryConfirmPage}：怎么穿（形态 + 装备）。</li>
 * </ul>
 *
 * <p>之前把历史列表直接塞在来源页右侧，结果是三件事挤在一屏：预览小、历史只有几行、
 * 形态开关夹在中间分不清属于哪一步。</p>
 *
 * <h2>切换动画</h2>
 * <p>往里走从右滑入、往回走从左滑入，由 {@link PageHost} 负责。方向反了会让人
 * 分不清自己是进是退，所以每一层都明确说自己往哪走。</p>
 */
public final class SkinCapeDialog extends StackPane {

    public enum Mode { SKIN, CAPE }

    /// 右列宽度。按钮和说明都按这个宽度折行，对话框尺寸才稳定。
    private static final double COLUMN_WIDTH = 268;

    private final Account account;
    private final Mode mode;
    private final Path dataDir;

    /// 这个账户在历史记录里的标识。
    ///
    /// <p>用 `AccountID` 而不是用户名：用户名可以改，改了历史就找不回来了；
    /// 也不用 profileID，因为离线账户和同名在线账户的 profileID 有可能撞上。</p>
    private final String owner;

    private final PageHost pages = new PageHost();
    private final JFXDialogLayout layout = new JFXDialogLayout();

    private final ToggleGroup formGroup = new ToggleGroup();
    private ElytraSkinCanvas canvas;

    /// 当前预览的皮肤与披风贴图。
    private Image previewSkin = TexturesLoader.getDefaultSkinImage();
    private Image previewCape;

    /// 已经选好、等“应用”的本地文件。
    private Path pendingSkinFile;
    private Path pendingCapeFile;

    public SkinCapeDialog(Account account, Mode mode, Path dataDir) {
        this.account = account;
        this.mode = mode;
        this.dataDir = dataDir;
        this.owner = ownerOf(account);

        getChildren().setAll(layout);
        layout.setHeading(new Label((mode == Mode.SKIN ? "切换皮肤" : "切换披风")
                + " · " + account.getProfileName()));

        pages.setPrefSize(COLUMN_WIDTH + 244 + 18, 300);
        pages.setRoot(buildSourcePage());

        // 底部按钮由当前页决定：来源页是“应用 / 取消”，内页是“返回”。
        layout.setBody(pages);
        layout.setActions(buildSourceActions());
    }

    // ==== 来源页 ====

    private Node buildSourcePage() {
        // ---- 左侧：3D 预览（账户皮肤变化时自动刷新） ----
        canvas = new ElytraSkinCanvas(240, 250);
        StackPane previewPane = new StackPane(canvas);
        previewPane.setPrefSize(244, 254);
        previewPane.setMinSize(244, 254);
        previewPane.setMaxSize(244, 254);

        ObservableValue<TexturesLoader.LoadedTexture> binding = TexturesLoader.skinBinding(account);
        javafx.beans.value.ChangeListener<TexturesLoader.LoadedTexture> listener = (obs, ov, nv) -> {
            if (nv != null && pendingSkinFile == null) {
                previewSkin = nv.image();
                refreshPreview();
            }
        };
        TexturesLoader.LoadedTexture current = binding.getValue();
        if (current != null)
            listener.changed(binding, null, current);
        binding.addListener(listener);

        // 账户当前穿着的披风。skinBinding 只给皮肤，披风要另外取——不取的话
        // 披风分区里永远是光背，看不到身上那件。
        AccountTextures.cape(account, image -> {
            if (pendingCapeFile == null) {
                previewCape = image;
                refreshPreview();
            }
        });

        // ---- 右侧：来源 ----
        VBox options = new VBox(8);
        options.setPrefWidth(COLUMN_WIDTH);
        options.setMinWidth(COLUMN_WIDTH);
        options.setMaxWidth(COLUMN_WIDTH);

        if (mode == Mode.SKIN) {
            addOption(options, "在线皮肤（LittleSkin 皮肤库）",
                    () -> Controllers.dialog(new LittleSkinLibraryPane(
                            LittleSkinLibraryPane.Filter.SKIN, dataDir,
                            file -> {
                                pendingSkinFile = file;
                                loadSkinPreview(file);
                            })));
            addOption(options, "自定义皮肤（选择本地文件）", () -> pickLocal(true));
            addHistoryOption(options);
            if (account instanceof OfflineAccount)
                addOption(options, "跟随 LittleSkin 皮肤源（按用户名）",
                        () -> SkinApplier.applyLittleSkinSource((OfflineAccount) account,
                                this::closeAndRefresh, SkinCapeDialog::showError));
        } else {
            if (account instanceof MicrosoftAccount) {
                MicrosoftAccount microsoftAccount = (MicrosoftAccount) account;
                addOption(options, "选择已拥有的官方披风",
                        () -> Controllers.dialog(new MojangCapePane(microsoftAccount, this::closeAndRefresh)));
                Label hint = new Label("微软账户的披风由 Mojang 官方管理，\n只能切换账户已拥有的官方披风，\n不支持上传自定义披风。");
                hint.setWrapText(true);
                hint.setMaxWidth(COLUMN_WIDTH);
                hint.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");
                options.getChildren().add(hint);
            } else {
                addOption(options, "在线披风（LittleSkin 皮肤库）",
                        () -> Controllers.dialog(new LittleSkinLibraryPane(
                                LittleSkinLibraryPane.Filter.CAPE, dataDir,
                                file -> {
                                    pendingCapeFile = file;
                                    loadCapePreview(file);
                                })));
                addOption(options, "自定义披风（选择本地文件）", () -> pickLocal(false));
                addHistoryOption(options);
                if (account instanceof OfflineAccount)
                    addOption(options, "跟随 LittleSkin 皮肤源（皮肤与披风均跟随）",
                            () -> SkinApplier.applyLittleSkinSource((OfflineAccount) account,
                                    this::closeAndRefresh, SkinCapeDialog::showError));
            }
        }

        // ---- 形态切换 ----
        Label formTitle = new Label(mode == Mode.SKIN ? "手臂模型" : "披风形态");
        formTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");
        HBox formButtons = new HBox(8);
        formButtons.setAlignment(Pos.CENTER);
        if (mode == Mode.SKIN) {
            formButtons.getChildren().setAll(
                    FormToggles.create(formGroup, "史蒂夫形态", false, true),
                    FormToggles.create(formGroup, "艾利克斯形态", true, false));
        } else {
            formButtons.getChildren().setAll(
                    FormToggles.create(formGroup, "普通形态", false, true),
                    FormToggles.create(formGroup, "鞘翅形态", true, false));
        }
        FormToggles.installStyleListener(formGroup, this::refreshPreview);

        VBox right = new VBox(10, options, formTitle, formButtons);
        right.setPrefWidth(COLUMN_WIDTH);
        right.setMinWidth(COLUMN_WIDTH);
        right.setMaxWidth(COLUMN_WIDTH);

        HBox body = new HBox(18, previewPane, right);
        body.setPadding(new Insets(8));
        body.setAlignment(Pos.TOP_LEFT);
        return body;
    }

    private Node buildSourceActions() {
        JFXButton applyButton = new JFXButton("应用");
        applyButton.getStyleClass().add("dialog-accept");
        applyButton.setOnAction(e -> applySelection());
        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        return new HBox(10, applyButton, cancelButton);
    }

    /// “曾使用过”按钮：带上件数，让人知道里面有没有东西。
    private void addHistoryOption(VBox options) {
        String kind = mode == Mode.SKIN ? "skin" : "cape";
        int count = SkinHistory.get().count(owner, kind);
        JFXButton button = FXUtils.newBorderButton(count > 0
                ? "曾使用过（" + count + "）"
                : "曾使用过");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> openHistory(kind));
        options.getChildren().add(button);
    }

    /// 打开网格页；选中后进入确认页。两层都用动画滑入。
    private void openHistory(String kind) {
        if (!HistoryGridPage.hasEntries(owner, kind)) {
            showInfo("还没有用过的" + ("cape".equals(kind) ? "披风" : "皮肤")
                    + "。\n先用一次，之后就能在这里找回来。");
            return;
        }
        pages.push(new HistoryGridPage(owner, kind, this::openConfirm, () -> {
            pages.pop();
            layout.setActions(buildSourceActions());
        }));
        layout.setActions(buildInnerActions());
    }

    /// 进入确认页。「确认装备」和「取消」并排放在底部，和其它页的按钮位置一致。
    private void openConfirm(SkinHistory.Entry entry) {
        HistoryConfirmPage[] page = new HistoryConfirmPage[1];
        page[0] = new HistoryConfirmPage(account, entry,
                () -> {
                    // 装备成功：回到来源页。
                    pages.pop();
                    pages.pop();
                    layout.setActions(buildSourceActions());
                },
                () -> {
                    // 返回网格。
                    pages.pop();
                    layout.setActions(buildGridActions());
                },
                null);
        pages.push(page[0]);
        layout.setActions(buildConfirmActions(page[0]));
    }

    /// 确认页底部：确认装备 + 取消。
    private Node buildConfirmActions(HistoryConfirmPage page) {
        JFXButton confirmButton = new JFXButton(HistoryConfirmPage.confirmLabel());
        confirmButton.getStyleClass().add("dialog-accept");
        confirmButton.setOnAction(e -> page.confirm());
        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        return new HBox(10, confirmButton, cancelButton);
    }

    /// 网格页底部：只有一个“返回”。
    private Node buildGridActions() {
        JFXButton backButton = new JFXButton("返回");
        backButton.getStyleClass().add("dialog-cancel");
        backButton.setOnAction(e -> {
            pages.pop();
            layout.setActions(buildSourceActions());
        });
        return new HBox(10, backButton);
    }

    /// 内页的兜底返回。
    private Node buildInnerActions() {
        return buildGridActions();
    }

    /// 账户在历史里的标识。
    ///
    /// <p>`AccountID` 是启动器给每条账户记录分配的稳定 id，改用户名、重新登录都不变。
    /// 拿不到时退回 profileID，再不行用用户名——总有值比丢失记录强。</p>
    static String ownerOf(Account account) {
        try {
            if (account.getAccountID() != null)
                return account.getAccountID().toString();
        } catch (Throwable ignored) {
        }
        try {
            if (account.getProfileID() != null)
                return "uuid:" + account.getProfileID();
        } catch (Throwable ignored) {
        }
        try {
            return "name:" + account.getProfileName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==== 预览 ====

    /// 按当前皮肤、披风与形态刷新左侧预览。
    private void refreshPreview() {
        boolean second = FormToggles.isSecond(formGroup);
        if (mode == Mode.SKIN) {
            canvas.setModel(previewSkin, second, previewCape, false);
        } else {
            // 披风分区：形态按钮在普通/鞘翅之间切换，皮肤保持账户当前那张。
            canvas.setModel(previewSkin, isCurrentSlim(), previewCape, second);
        }
    }

    private boolean isCurrentSlim() {
        // 每点一次形态按钮都会走到这里，而 `AccountTextures.isSlim` 会新建一次
        // `skinBinding`。取一次就够了。
        if (!slimResolved) {
            slimResolved = true;
            slimValue = AccountTextures.isSlim(account);
        }
        return slimValue;
    }

    private boolean slimResolved;
    private boolean slimValue;

    private void loadSkinPreview(Path file) {
        try (var in = Files.newInputStream(file)) {
            Image image = new Image(in);
            if (image.isError())
                throw new IllegalStateException("不是有效的图片");
            previewSkin = image;
            refreshPreview();
        } catch (Throwable e) {
            showError("无法预览这个皮肤文件：" + e.getMessage());
        }
    }

    private void loadCapePreview(Path file) {
        try (var in = Files.newInputStream(file)) {
            Image image = new Image(in);
            if (image.isError())
                throw new IllegalStateException("不是有效的图片");
            previewCape = image;
            refreshPreview();
        } catch (Throwable e) {
            showError("无法预览这个披风文件：" + e.getMessage());
        }
    }

    // ==== 应用 ====

    private void applySelection() {
        boolean second = FormToggles.isSecond(formGroup);
        if (mode == Mode.SKIN) {
            Path file = pendingSkinFile;
            if (file == null) {
                applyModelOnly();
                return;
            }
            if (!Files.isRegularFile(file)) {
                showError("这个皮肤文件已经不在了，请重新选择");
                return;
            }
            record(file, second, false);
            SkinApplier.applySkin(account, file, second, this::closeAndRefresh, SkinCapeDialog::showError);
        } else {
            Path file = pendingCapeFile;
            if (file == null) {
                showError("请先选择要切换的披风");
                return;
            }
            if (!Files.isRegularFile(file)) {
                showError("这个披风文件已经不在了，请重新选择");
                return;
            }
            record(file, isCurrentSlim(), second);
            SkinApplier.applyCape(account, file, isCurrentSlim(), this::closeAndRefresh, SkinCapeDialog::showError);
        }
    }

    /// 只改手臂模型，不换皮肤文件。
    ///
    /// <p>离线账户可以就地改 `Skin.textureModel`；在线账户必须重新上传同一张图并带上
    /// 新的 variant，所以这里只对离线账户开放，其余给一句说明。</p>
    private void applyModelOnly() {
        boolean slim = FormToggles.isSecond(formGroup);
        if (account instanceof OfflineAccount offline) {
            org.jackhuang.hmcl.auth.offline.Skin skin = offline.getSkin();
            if (skin == null || skin.type() != org.jackhuang.hmcl.auth.offline.Skin.Type.LOCAL_FILE
                    || skin.localSkinPath() == null) {
                showError("当前皮肤不是本地文件，无法只切换模型。\n请重新选择一次皮肤文件。");
                return;
            }
            offline.setSkin(new org.jackhuang.hmcl.auth.offline.Skin(
                    org.jackhuang.hmcl.auth.offline.Skin.Type.LOCAL_FILE, null,
                    slim ? org.jackhuang.hmcl.auth.yggdrasil.TextureModel.SLIM
                         : org.jackhuang.hmcl.auth.yggdrasil.TextureModel.WIDE,
                    skin.localSkinPath(), skin.localCapePath()));
            record(Path.of(skin.localSkinPath()), slim, false);
            closeAndRefresh();
        } else {
            showError("在线账户切换模型需要重新上传皮肤，\n请在列表里选择一次皮肤后再应用。");
        }
    }

    /// 把这次应用记进“曾使用过”。
    private void record(Path file, boolean slim, boolean elytra) {
        SkinHistory.Entry entry = new SkinHistory.Entry();
        entry.owner = owner;
        entry.kind = mode == Mode.SKIN ? "skin" : "cape";
        entry.path = file.toString();
        entry.slim = slim;
        entry.elytra = elytra;
        // 皮肤库下载的文件名是 skinlib-<tid>.png，可以从里面还原 tid 与来源信息。
        int tid = tidFromFileName(file);
        entry.tid = tid;
        if (tid > 0) {
            LittleSkinLibraryPane.Item known = LittleSkinLibraryPane.knownItem(tid);
            entry.name = known != null && known.name != null ? known.name : ("皮肤库 #" + tid);
            entry.author = known != null ? known.nickname : null;
            entry.type = known != null && known.type != null
                    ? known.type : (mode == Mode.SKIN ? "steve" : "cape");
            entry.hd = known != null && known.hd;
        } else {
            entry.name = org.jackhuang.hmcl.util.io.FileUtils.getName(file);
            entry.type = mode == Mode.SKIN ? "steve" : "cape";
        }
        SkinHistory.get().record(entry);
    }

    private static int tidFromFileName(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("skinlib-") || !name.endsWith(".png"))
            return 0;
        try {
            return Integer.parseInt(name.substring("skinlib-".length(), name.length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==== 杂项 ====

    private void addOption(VBox options, String text, Runnable action) {
        JFXButton button = FXUtils.newBorderButton(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        options.getChildren().add(button);
    }

    private void pickLocal(boolean isSkin) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(isSkin ? "选择皮肤文件" : "选择披风文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤/披风图片", "*.png"));
        Path file = org.jackhuang.hmcl.util.io.FileUtils.toPath(chooser.showOpenDialog(Controllers.getStage()));
        if (file == null)
            return;
        if (isSkin) {
            pendingSkinFile = file;
            loadSkinPreview(file);
        } else {
            pendingCapeFile = file;
            loadCapePreview(file);
        }
    }

    private void closeAndRefresh() {
        Controllers.showToast("已应用" + (mode == Mode.SKIN ? "皮肤" : "披风"));
        fireEvent(new DialogCloseEvent());
    }

    private static void showError(String message) {
        Controllers.dialog(message, "应用失败", MessageType.ERROR);
    }

    private static void showInfo(String message) {
        Controllers.dialog(message, "曾使用过");
    }

    /// 供测试读取页面容器。
    PageHost pageHost() {
        return pages;
    }

    /// 供测试直接进入网格页。
    void openHistoryForTest(String kind) {
        openHistory(kind);
    }

    /// 供测试直接进入确认页。
    void openConfirmForTest(SkinHistory.Entry entry) {
        openConfirm(entry);
    }
}
