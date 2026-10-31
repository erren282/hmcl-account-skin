package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/**
 * 皮肤/披风切换主对话框：左侧 3D 预览，右侧来源按钮 + 曾使用过列表。
 *
 * <h2>为什么历史放在这里</h2>
 * <p>用户点“切换皮肤”时想要的多半是“换回上次那个”，让他先打开皮肤库再想起来去历史里翻，
 * 是多余的一步。所以历史直接列在来源按钮下面，一进来就看得见。</p>
 *
 * <h2>模型与形态</h2>
 * <p>皮肤可切宽臂/纤细，披风可切普通/鞘翅，两者都实时反映到左侧预览；
 * 从历史里再次使用时按当时记下的模型恢复，而不是回到默认值。</p>
 */
public final class SkinCapeDialog extends StackPane {

    public enum Mode { SKIN, CAPE }

    /// 历史列表宽度。与预览并排，整个对话框保持在最小窗口（492 px 高）以内。
    private static final double HISTORY_WIDTH = 268;

    /// 列表最多显示几行的高度；超出后滚动。
    private static final double HISTORY_HEIGHT = 190;

    private static final String TAB_SELECTED =
            "-fx-background-color: -monet-primary; -fx-text-fill: -monet-on-primary;"
                    + " -fx-background-radius: 16; -fx-padding: 4 14 4 14; -fx-font-size: 12px; -fx-cursor: hand;";
    private static final String TAB_UNSELECTED =
            "-fx-background-color: -monet-surface-container-high; -fx-text-fill: -monet-on-surface-variant;"
                    + " -fx-background-radius: 16; -fx-padding: 4 14 4 14; -fx-font-size: 12px; -fx-cursor: hand;";

    /// 历史行选中态：主题色圆角边框 + 同色浅底，与皮肤库列表保持一致。
    private static final String ROW_SELECTED =
            "-fx-border-color: -monet-primary; -fx-border-width: 2; -fx-border-radius: 8;"
                    + " -fx-background-radius: 8; -fx-background-color: derive(-monet-primary, 90%); -fx-padding: 4;";
    private static final String ROW_UNSELECTED =
            "-fx-border-color: transparent; -fx-border-width: 2; -fx-border-radius: 8;"
                    + " -fx-background-radius: 8; -fx-padding: 4;";

    private final Account account;
    private final Mode mode;
    private final Path dataDir;

    /// 这个账户在历史记录里的标识。
    ///
    /// <p>用 `AccountID` 而不是用户名：用户名可以改，改了历史就找不回来了；
    /// 也不用 profileID，因为离线账户和同名在线账户的 profileID 有可能撞上。</p>
    private final String owner;

    private final ToggleGroup formGroup = new ToggleGroup();
    private final ElytraSkinCanvas canvas;
    private final ListView<Row> historyView = new ListView<>();
    private final Label historyEmpty = new Label();
    private final VBox historyBox = new VBox(6);
    private final StackPane previewPane = new StackPane();

    /// 当前预览的皮肤贴图与披风贴图。来源按钮选中的本地文件会覆盖它们。
    private Image previewSkin = TexturesLoader.getDefaultSkinImage();
    private Image previewCape;
    /// 来源按钮选中的本地文件；为 null 表示还没选，预览用账户当前的皮肤。
    private Path pendingSkinFile;
    private Path pendingCapeFile;

    /// 历史列表的一行：可能是真实历史条目，也可能是“当前已在使用”的占位。
    static final class Row {
        final SkinHistory.Entry entry;
        final Image thumbnail;
        final boolean current;

        Row(SkinHistory.Entry entry, Image thumbnail, boolean current) {
            this.entry = entry;
            this.thumbnail = thumbnail;
            this.current = current;
        }
    }

    public SkinCapeDialog(Account account, Mode mode, Path dataDir) {
        this.account = account;
        this.mode = mode;
        this.dataDir = dataDir;
        this.owner = ownerOf(account);

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        String title = mode == Mode.SKIN ? "切换皮肤" : "切换披风";
        layout.setHeading(new Label(title + " · " + account.getProfileName()));

        // ---- 左侧：3D 预览（账户皮肤变化时自动刷新） ----
        canvas = new ElytraSkinCanvas(240, 250);
        previewPane.getChildren().setAll(canvas);
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

        // ---- 右侧：来源按钮 + 曾使用过 ----
        VBox options = new VBox(8);
        options.setPrefWidth(HISTORY_WIDTH);
        options.setMinWidth(HISTORY_WIDTH);
        options.setMaxWidth(HISTORY_WIDTH);

        if (mode == Mode.SKIN) {
            addOption(options, "在线皮肤（LittleSkin 皮肤库）",
                    () -> Controllers.dialog(new LittleSkinLibraryPane(
                            LittleSkinLibraryPane.Filter.SKIN, dataDir,
                            file -> {
                                pendingSkinFile = file;
                                loadSkinPreview(file);
                            })));
            addOption(options, "自定义皮肤（选择本地文件）", () -> pickLocal(true));
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
                if (account instanceof OfflineAccount)
                    addOption(options, "跟随 LittleSkin 皮肤源（皮肤与披风均跟随）",
                            () -> SkinApplier.applyLittleSkinSource((OfflineAccount) account,
                                    this::closeAndRefresh, SkinCapeDialog::showError));
            }
        }

        // ---- 形态切换 ----
        HBox formButtons = new HBox(8);
        formButtons.setAlignment(Pos.CENTER);
        if (mode == Mode.SKIN) {
            formButtons.getChildren().setAll(
                    formToggle("史蒂夫形态", false, true),
                    formToggle("艾利克斯形态", true, false));
        } else {
            formButtons.getChildren().setAll(
                    formToggle("普通形态", false, true),
                    formToggle("鞘翅形态", true, false));
        }

        // ---- 曾使用过 ----
        Label historyTitle = new Label("曾使用过");
        historyTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");

        historyView.setPrefSize(HISTORY_WIDTH, HISTORY_HEIGHT);
        historyView.setMinSize(HISTORY_WIDTH, HISTORY_HEIGHT);
        historyView.setMaxSize(HISTORY_WIDTH, HISTORY_HEIGHT);
        historyView.setCellFactory(view -> new HistoryCell());
        historyView.setPlaceholder(placeholder());
        historyView.getSelectionModel().selectedItemProperty().addListener((obs, ov, nv) -> {
            if (nv != null && nv.entry != null)
                previewHistory(nv.entry);
        });

        historyBox.getChildren().setAll(historyTitle, historyView);

        // ---- 应用 / 取消 ----
        JFXButton applyButton = new JFXButton("应用");
        applyButton.getStyleClass().add("dialog-accept");
        applyButton.setOnAction(e -> applySelection());
        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        VBox right = new VBox(10, options, formButtons, historyBox);
        right.setPrefWidth(HISTORY_WIDTH);
        right.setMinWidth(HISTORY_WIDTH);
        right.setMaxWidth(HISTORY_WIDTH);

        HBox body = new HBox(18, previewPane, right);
        body.setPadding(new Insets(8));
        body.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(options, Priority.NEVER);
        layout.setBody(body);
        layout.setActions(applyButton, cancelButton);

        formGroup.selectedToggleProperty().addListener((obs, ov, nv) -> {
            if (ov != null)
                styleToggle((ToggleButton) ov, false);
            if (nv != null)
                styleToggle((ToggleButton) nv, true);
            refreshPreview();
        });

        reloadHistory();
        refreshPreview();
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

    // ==== 形态 ====

    private ToggleButton formToggle(String text, boolean secondForm, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(formGroup);
        button.setUserData(secondForm);
        button.setSelected(selected);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        styleToggle(button, selected);
        return button;
    }

    private void styleToggle(ToggleButton button, boolean selected) {
        button.setStyle(selected
                ? "-fx-background-color: -monet-primary; -fx-text-fill: -monet-on-primary;"
                + " -fx-background-radius: 18; -fx-padding: 4 10 4 10; -fx-font-size: 12px; -fx-cursor: hand;"
                : "-fx-background-color: -monet-surface-container-high; -fx-text-fill: -monet-on-surface-variant;"
                + " -fx-background-radius: 18; -fx-padding: 4 10 4 10; -fx-font-size: 12px; -fx-cursor: hand;");
    }

    private boolean secondForm() {
        Toggle toggle = formGroup.getSelectedToggle();
        return toggle != null && Boolean.TRUE.equals(toggle.getUserData());
    }

    private void selectForm(boolean second) {
        for (Toggle toggle : formGroup.getToggles()) {
            if (Boolean.valueOf(second).equals(toggle.getUserData())) {
                toggle.setSelected(true);
                return;
            }
        }
    }

    // ==== 预览 ====

    /// 按当前皮肤、披风与形态刷新左侧预览。
    private void refreshPreview() {
        boolean second = secondForm();
        if (mode == Mode.SKIN) {
            canvas.setModel(previewSkin, second, previewCape, false);
        } else {
            // 披风分区：形态按钮在普通/鞘翅之间切换，皮肤保持账户当前那张。
            if (second)
                canvas.setModel(previewSkin, isCurrentSlim(), previewCape, true);
            else
                canvas.setModel(previewSkin, isCurrentSlim(), previewCape, false);
        }
    }

    /// 账户当前的手臂模型。披风预览要穿在用户自己的角色上，所以跟随账户而不是按钮。
    private boolean isCurrentSlim() {
        try {
            if (account instanceof OfflineAccount offline) {
                return offline.getSkin() != null
                        && offline.getSkin().textureModel() == org.jackhuang.hmcl.auth.yggdrasil.TextureModel.SLIM;
            }
            TexturesLoader.LoadedTexture texture = TexturesLoader.skinBinding(account).getValue();
            return texture != null && "slim".equals(texture.metadata().get("model"));
        } catch (Throwable t) {
            return false;
        }
    }

    /// 读本地皮肤文件并显示预览。读不出来就在预览上方给一句提示。
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

    /// 选中历史条目时预览它，并按当时记下的模型/形态恢复按钮。
    private void previewHistory(SkinHistory.Entry entry) {
        try (var in = Files.newInputStream(Path.of(entry.path))) {
            Image image = new Image(in);
            if (image.isError())
                throw new IllegalStateException("不是有效的图片");
            if (mode == Mode.SKIN) {
                pendingSkinFile = Path.of(entry.path);
                previewSkin = image;
                selectForm(entry.slim);
            } else {
                pendingCapeFile = Path.of(entry.path);
                previewCape = image;
                selectForm(entry.elytra);
            }
            refreshPreview();
        } catch (Throwable e) {
            showError("这件材质读不出来了：" + e.getMessage());
        }
    }

    // ==== 应用 ====

    private void applySelection() {
        if (mode == Mode.SKIN) {
            Path file = pendingSkinFile;
            if (file == null) {
                // 没选新皮肤：只把模型改动应用上去。
                applyModelOnly();
                return;
            }
            if (!Files.isRegularFile(file)) {
                showError("这个皮肤文件已经不在了，请重新选择");
                return;
            }
            boolean slim = secondForm();
            record(file, slim, false);
            SkinApplier.applySkin(account, file, slim, this::closeAndRefresh, SkinCapeDialog::showError);
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
            record(file, isCurrentSlim(), secondForm());
            SkinApplier.applyCape(account, file, isCurrentSlim(), this::closeAndRefresh, SkinCapeDialog::showError);
        }
    }

    /// 只改手臂模型，不换皮肤文件。
    ///
    /// <p>离线账户可以就地改 `Skin.textureModel`；在线账户必须重新上传同一张图并带上
    /// 新的 variant，所以这里只对离线账户开放，其余给一句说明。</p>
    private void applyModelOnly() {
        boolean slim = secondForm();
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

    // ==== 历史列表 ====

    private void reloadHistory() {
        String kind = mode == Mode.SKIN ? "skin" : "cape";
        List<SkinHistory.Entry> entries = SkinHistory.get().entries(owner, kind);
        historyView.getItems().clear();
        for (SkinHistory.Entry entry : entries) {
            Image thumb = null;
            try {
                thumb = new Image(Path.of(entry.path).toUri().toString());
            } catch (Throwable ignored) {
            }
            historyView.getItems().add(new Row(entry, thumb, false));
        }
        boolean empty = historyView.getItems().isEmpty();
        historyEmpty.setText(mode == Mode.SKIN
                ? "还没有换过皮肤。\n换过之后会记在这里，点一下就能换回去。"
                : "还没有换过披风。\n换过之后会记在这里，点一下就能换回去。");
        historyEmpty.setVisible(empty);
        historyEmpty.setManaged(empty);
        historyView.setVisible(!empty);
        historyView.setManaged(!empty);
    }

    private Label placeholder() {
        Label label = new Label("还没有换过");
        label.setStyle("-fx-font-size: 11px;");
        label.setWrapText(true);
        label.setMaxWidth(HISTORY_WIDTH - 40);
        return label;
    }

    /// 历史行：缩略图 + 名称 + 上次使用的模型/形态与时间，右侧一个删除按钮。
    private final class HistoryCell extends ListCell<Row> {
        private final Canvas thumb = new Canvas(36, 36);
        private final Label titleLabel = new Label();
        private final Label subtitleLabel = new Label();
        private final JFXButton deleteButton = new JFXButton();
        private final HBox box;

        HistoryCell() {
            titleLabel.getStyleClass().add("title");
            titleLabel.setStyle("-fx-font-size: 12px;");
            subtitleLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -monet-on-surface-variant;");
            VBox text = new VBox(1, titleLabel, subtitleLabel);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setMaxWidth(HISTORY_WIDTH - 90);

            deleteButton.setGraphic(org.jackhuang.hmcl.ui.SVG.DELETE.createIcon());
            deleteButton.getStyleClass().add("round-button");
            deleteButton.setStyle("-fx-padding: 4;");
            FXUtils.installFastTooltip(deleteButton, "从曾使用过里删除");
            deleteButton.setOnAction(e -> {
                Row row = getItem();
                if (row == null || row.entry == null)
                    return;
                SkinHistory.get().forget(row.entry);
                reloadHistory();
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box = new HBox(8, thumb, text, spacer, deleteButton);
            box.setAlignment(Pos.CENTER_LEFT);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPrefHeight(50);

            // 选中态必须跟着 selection 走，而不是在 updateItem 里读一次 isSelected()：
            // 点击只会改变选中状态、不会重建单元格，那样边框要等滚动一下才出现。
            selectedProperty().addListener((obs, wasSelected, isSelected) ->
                    applySelectionStyle(isSelected));
        }

        private void applySelectionStyle(boolean selected) {
            setStyle(selected ? ROW_SELECTED : ROW_UNSELECTED);
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                applySelectionStyle(false);
                return;
            }
            SkinHistory.Entry entry = row.entry;
            Thumbnails.draw(thumb, row.thumbnail, mode == Mode.CAPE);

            titleLabel.setText(entry.name != null ? entry.name : "(未命名)");
            StringBuilder detail = new StringBuilder();
            if (mode == Mode.SKIN)
                detail.append(entry.slim ? "艾利克斯" : "史蒂夫");
            else
                detail.append(entry.elytra ? "鞘翅" : "普通披风");
            if (entry.author != null && !entry.author.isEmpty())
                detail.append(" · ").append(entry.author);
            detail.append(" · ").append(describeAge(entry.usedAt));
            subtitleLabel.setText(detail.toString());

            setGraphic(box);
            applySelectionStyle(isSelected());
        }
    }

    /// “刚刚 / 3 小时前 / 2 天前”这样的相对时间，比绝对时间戳好读。
    static String describeAge(long usedAt) {
        long delta = System.currentTimeMillis() - usedAt;
        if (delta < 0)
            return "刚刚";
        long minutes = delta / 60000;
        if (minutes < 1)
            return "刚刚";
        if (minutes < 60)
            return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24)
            return hours + " 小时前";
        long days = hours / 24;
        if (days < 30)
            return days + " 天前";
        long months = days / 30;
        if (months < 12)
            return months + " 个月前";
        return (months / 12) + " 年前";
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
}
