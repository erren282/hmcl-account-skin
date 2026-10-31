package org.dsh.accountskin;

import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * 形态切换按钮（皮肤：史蒂夫/艾利克斯；披风：普通/鞘翅）。
 *
 * <p>主对话框和确认页都要这一组按钮，所以样式与行为放在一处：
 * 两处各写一遍的话，改了一边忘了另一边，同一个开关在两个界面里会长得不一样。</p>
 *
 * <p>按钮的 {@code userData} 标记它代表的形态：`false` 是第一种（史蒂夫 / 普通披风），
 * `true` 是第二种（艾利克斯 / 鞘翅）。</p>
 */
final class FormToggles {

    private static final String SELECTED =
            "-fx-background-color: -monet-primary; -fx-text-fill: -monet-on-primary;"
                    + " -fx-background-radius: 18; -fx-padding: 4 10 4 10; -fx-font-size: 12px; -fx-cursor: hand;";
    private static final String UNSELECTED =
            "-fx-background-color: -monet-surface-container-high; -fx-text-fill: -monet-on-surface-variant;"
                    + " -fx-background-radius: 18; -fx-padding: 4 10 4 10; -fx-font-size: 12px; -fx-cursor: hand;";

    private FormToggles() {
    }

    /// 建一个形态按钮并加入组。
    static ToggleButton create(ToggleGroup group, String text, boolean secondForm, boolean selected) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setUserData(secondForm);
        button.setSelected(selected);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        style(button, selected);
        return button;
    }

    static void style(ToggleButton button, boolean selected) {
        button.setStyle(selected ? SELECTED : UNSELECTED);
    }

    /// 当前选中的是不是第二种形态。
    static boolean isSecond(ToggleGroup group) {
        Toggle toggle = group.getSelectedToggle();
        return toggle != null && Boolean.TRUE.equals(toggle.getUserData());
    }

    /// 切到指定形态。找不到对应按钮时什么也不做。
    static void select(ToggleGroup group, boolean second) {
        for (Toggle toggle : group.getToggles()) {
            if (Boolean.valueOf(second).equals(toggle.getUserData())) {
                toggle.setSelected(true);
                return;
            }
        }
    }

    /// 接上“选中态跟着换样式”的监听。构造完按钮后调用一次。
    static void installStyleListener(ToggleGroup group, Runnable onChange) {
        group.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (oldToggle != null)
                style((ToggleButton) oldToggle, false);
            if (newToggle != null)
                style((ToggleButton) newToggle, true);
            onChange.run();
        });
    }
}
