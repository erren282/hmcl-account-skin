package org.dsh.accountskin;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 曾使用过：网格形式的皮肤/披风选择页。
 *
 * <h2>为什么是网格</h2>
 * <p>翻历史时人是在“认图”，不是在读名字：皮肤长什么样一眼就能看出来，名字往往记不住。
 * 所以这里铺成大缩略图网格，一屏能看十来个，而不是每行一条的列表。</p>
 *
 * <h2>删除</h2>
 * <p>每张卡片右上角有一个删除按钮，删掉只是从历史里移除，不会动已经穿在身上的皮肤。
 * 删除按钮在鼠标悬停时才显现——常驻的话网格里全是叉，反而看不清皮肤本身。</p>
 */
public final class HistoryGridPane extends StackPane {

    /// 卡片尺寸。宽度要能让缩略图看出人形，高度要装下名字和一行说明。
    private static final double CARD_WIDTH = 92;
    private static final double CARD_HEIGHT = 118;

    private final List<SkinHistory.Entry> entries;
    private final TilePane grid = new TilePane();
    private final Label countLabel = new Label();

    /// 用户选中的条目。取消时为 null。
    private SkinHistory.Entry picked;

    /**
     * @param owner 账户标识，历史按账户分开
     * @param kind  `skin` 或 `cape`
     * @param onPick 选中后进入确认页；为 null 时只做浏览
     */
    public HistoryGridPane(String owner, String kind, java.util.function.Consumer<SkinHistory.Entry> onPick) {
        this.entries = new ArrayList<>(SkinHistory.get().entries(owner, kind));
        boolean isCape = "cape".equals(kind);

        JFXDialogLayout layout = new JFXDialogLayout();
        getChildren().setAll(layout);

        layout.setHeading(new Label(isCape ? "曾使用过的披风" : "曾使用过的皮肤"));

        // ---- 网格 ----
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPrefColumns(5);
        grid.setPrefTileWidth(CARD_WIDTH);
        grid.setPrefTileHeight(CARD_HEIGHT);
        grid.setPadding(new Insets(4));

        for (SkinHistory.Entry entry : entries)
            grid.getChildren().add(new Card(entry, isCape, onPick));

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background-color: transparent;");
        scroll.setPrefViewportHeight(280);

        // 空状态：别留一个空框子让人猜。
        Label empty = new Label(isCape
                ? "还没有换过披风。\n换过之后会记在这里，点一下就能换回去。"
                : "还没有换过皮肤。\n换过之后会记在这里，点一下就能换回去。");
        empty.setWrapText(true);
        empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -monet-on-surface-variant;");

        StackPane content = new StackPane(entries.isEmpty() ? empty : scroll);
        content.setPrefSize(CARD_WIDTH * 5 + 40, entries.isEmpty() ? 160 : 288);
        content.setMinHeight(entries.isEmpty() ? 160 : 288);

        VBox body = new VBox(6, content);
        body.setPadding(new Insets(4));
        layout.setBody(body);

        countLabel.setText(entries.isEmpty() ? "" : "共 " + entries.size() + " 件");
        countLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -monet-on-surface-variant;");

        JFXButton closeButton = new JFXButton("关闭");
        closeButton.getStyleClass().add("dialog-cancel");
        closeButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        layout.setActions(countLabel, closeButton);
    }

    /// 一张卡片：缩略图 + 名字 + 形态与时间，右上角删除按钮。
    private final class Card extends StackPane {
        private final SkinHistory.Entry entry;
        private final boolean isCape;
        private final JFXButton deleteButton = new JFXButton();
        private final Region selectionBorder = new Region();

        Card(SkinHistory.Entry entry, boolean isCape,
             java.util.function.Consumer<SkinHistory.Entry> onPick) {
            this.entry = entry;
            this.isCape = isCape;

            setPrefSize(CARD_WIDTH, CARD_HEIGHT);
            setMinSize(CARD_WIDTH, CARD_HEIGHT);
            setMaxSize(CARD_WIDTH, CARD_HEIGHT);

            Canvas thumb = new Canvas(64, 64);
            Image image = null;
            try {
                image = new Image(Path.of(entry.path).toUri().toString());
            } catch (Throwable ignored) {
            }
            Thumbnails.draw(thumb, image, isCape);

            Label name = new Label(entry.name != null ? entry.name : "(未命名)");
            name.setStyle("-fx-font-size: 11px;");
            name.setMaxWidth(CARD_WIDTH - 8);
            name.setWrapText(false);

            Label detail = new Label((isCape ? (entry.elytra ? "鞘翅" : "普通") : (entry.slim ? "艾利克斯" : "史蒂夫"))
                    + " · " + describeAge(entry.usedAt));
            detail.setStyle("-fx-font-size: 10px; -fx-text-fill: -monet-on-surface-variant;");
            detail.setMaxWidth(CARD_WIDTH - 8);

            VBox box = new VBox(2, thumb, name, detail);
            box.setAlignment(Pos.CENTER);
            box.setPadding(new Insets(4));

            // 选中边框：画在卡片最上层，不占布局空间，选中时不会让内容跳一下。
            selectionBorder.setStyle(
                    "-fx-border-color: -monet-primary; -fx-border-width: 2; -fx-border-radius: 8;"
                            + " -fx-background-color: derive(-monet-primary, 90%); -fx-background-radius: 8;");
            selectionBorder.setVisible(false);
            selectionBorder.setMouseTransparent(true);

            deleteButton.setGraphic(org.jackhuang.hmcl.ui.SVG.DELETE.createIcon());
            deleteButton.setStyle("-fx-padding: 2; -fx-background-radius: 10;"
                    + " -fx-background-color: -monet-surface-container-high;");
            deleteButton.setVisible(false);
            FXUtils.installFastTooltip(deleteButton, "从曾使用过里删除");
            deleteButton.setOnAction(e -> {
                e.consume();
                SkinHistory.get().forget(entry);
                // 就地移除，不重开整个页面：重开会让用户丢掉刚才滚到的位置。
                grid.getChildren().remove(this);
                int left = grid.getChildren().size();
                countLabel.setText(left == 0 ? "" : "共 " + left + " 件");
                if (left == 0)
                    fireEvent(new DialogCloseEvent());
            });
            StackPane.setAlignment(deleteButton, Pos.TOP_RIGHT);
            StackPane.setMargin(deleteButton, new Insets(2, 2, 0, 0));

            getChildren().setAll(box, selectionBorder, deleteButton);

            // 删除按钮只在悬停时出现。
            setOnMouseEntered(e -> deleteButton.setVisible(true));
            setOnMouseExited(e -> deleteButton.setVisible(false));

            setOnMouseClicked(e -> {
                if (onPick == null)
                    return;
                picked = entry;
                fireEvent(new DialogCloseEvent());
                onPick.accept(entry);
            });

            // 悬停反馈：卡片底色变浅，让人知道这里可以点。
            setOnMouseEntered(e -> {
                deleteButton.setVisible(true);
                if (!selectionBorder.isVisible())
                    setStyle("-fx-background-color: -monet-surface-container-high; -fx-background-radius: 8;");
            });
            setOnMouseExited(e -> {
                deleteButton.setVisible(false);
                if (!selectionBorder.isVisible())
                    setStyle("-fx-background-color: transparent;");
            });
        }
    }

    /// 用户选中的条目，取消时为 null。
    SkinHistory.Entry pickedEntry() {
        return picked;
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

    /// 打开网格页；用户选中后回调。没有可选项时给一句提示而不是开一个空页面。
    static void open(String owner, String kind,
                     java.util.function.Consumer<SkinHistory.Entry> onPick) {
        if (SkinHistory.get().entries(owner, kind).isEmpty()) {
            Controllers.dialog("还没有用过的" + ("cape".equals(kind) ? "披风" : "皮肤")
                    + "。\n先用一次，之后就能在这里找回来。");
            return;
        }
        Controllers.dialog(new HistoryGridPane(owner, kind, onPick));
    }
}
