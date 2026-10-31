package org.dsh.accountskin;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.animation.Motion;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 皮肤插件的页面容器：在「来源 / 网格 / 确认」之间带方向地切换。
 *
 * <h2>为什么不直接用启动器的 TransitionPane</h2>
 * <p>{@code TransitionPane.setContent} 依赖容器已经有宽高（`getWidth() > 0`），
 * 而这里的对话框是刚构造出来、还没上屏的，量到的宽度是 0，动画会被直接跳过。
 * 所以这里自己算位移量：拿不到宽度时按内容宽度估算，保证动画总能跑起来。</p>
 *
 * <h2>方向</h2>
 * <p>往里走（来源 → 网格 → 确认）用 {@link Direction#FORWARD}，往回走用
 * {@link Direction#BACKWARD}。方向反了会让人分不清自己是进是退，
 * 所以每一层都明确说自己往哪走，而不是统一淡入淡出。</p>
 */
final class PageHost extends StackPane {

    /// 切换方向。
    enum Direction {
        /// 进入下一层：新页面从右侧滑入，旧页面向左退出。
        FORWARD,
        /// 回到上一层：新页面从左侧滑入，旧页面向右退出。
        BACKWARD,
        /// 同级切换（比如同一个页面换了内容）：只淡入淡出，不做位移。
        FADE
    }

    private static final Duration DURATION = Motion.SHORT4;
    private static final Interpolator EASE = Motion.EASE;

    /// 位移距离，取自启动器原生页面切换（`DecoratorSkin.NavBarAnimations`）的 50 px。
    ///
    /// <p>跟着原生参数走，插件里的切换和启动器自己的页面切换手感才一致。
    /// 之前用整页宽度做位移，视觉上像被甩出去，和原生动画明显不是一回事。</p>
    private static final double OFFSET = 50;

    /// 页面栈。返回时用它决定回到哪一页。
    private final Deque<Node> stack = new ArrayDeque<>();

    private Node current;
    private boolean animating;

    /// 动画是否可用。
    ///
    /// <p>{@code AnimationUtils} 的静态初始化要读启动器设置，在没有初始化设置的环境里
    /// （自检程序就是这样跑的）会抛 {@code ExceptionInInitializerError}。那种情况下
    /// 退回“直接换页”：宁可没有动画，也不能让对话框打不开。</p>
    private static boolean animationEnabled() {
        if (FORCE_ANIMATION_FOR_TEST != null)
            return FORCE_ANIMATION_FOR_TEST;
        try {
            return AnimationUtils.isAnimationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /// 自检程序用：强制开/关动画，绕开对启动器设置的依赖。生产路径永远是 null。
    static Boolean FORCE_ANIMATION_FOR_TEST;

    /// 把节点复位到“可见、无位移”。
    ///
    /// <p>同理，不直接调 {@code AnimationUtils.reset}：它所在的类初始化可能失败。
    /// 这里只做和它一样的三件事，不依赖那个类。</p>
    private static void reset(Node node, boolean opaque) {
        if (node == null)
            return;
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1);
        node.setScaleY(1);
        node.setOpacity(opaque ? 1 : 0);
    }

    PageHost() {
        setAlignment(Pos.CENTER);
        // 页面切换时新页面会从容器外滑进来，超出部分要裁掉，否则会在对话框外露出边缘。
        setStyle("-fx-background-color: transparent;");
    }

    /// 首次进入：不放动画，直接显示。
    void setRoot(Node page) {
        stack.clear();
        stack.push(page);
        current = page;
        reset(page, true);
        getChildren().setAll(page);
    }

    /// 进入下一页。
    void push(Node page) {
        stack.push(page);
        transition(page, Direction.FORWARD);
    }

    /// 回到上一页。栈里没有上一页时返回 false，调用方可以据此决定是否关闭对话框。
    boolean pop() {
        if (stack.size() < 2)
            return false;
        stack.pop();
        transition(stack.peek(), Direction.BACKWARD);
        return true;
    }

    /// 是否还有上一页。
    boolean canGoBack() {
        return stack.size() >= 2;
    }

    private void transition(Node next, Direction direction) {
        Node previous = current;
        current = next;

        // 动画关了、或正在动画中（连点两下）时直接换页：
        // 半路插入第二段动画会让两段互相拉扯，最后停在一个奇怪的透明度上。
        if (previous == null || previous == next || animating || !animationEnabled()) {
            reset(next, true);
            getChildren().setAll(next);
            return;
        }

        // 位移量与启动器原生页面切换一致：不是整页滑过，而是新页从 ±OFFSET 处
        // 淡入、旧页向反方向移动同样的距离。整页滑过在这里会显得过重——
        // 对话框比主窗口小得多，一整屏的位移看起来像被甩出去。
        double offset = direction == Direction.FORWARD ? OFFSET
                : direction == Direction.BACKWARD ? -OFFSET : 0;

        reset(previous, true);
        reset(next, true);
        next.setTranslateX(offset);
        next.setOpacity(direction == Direction.FADE ? 0 : 1);

        getChildren().setAll(previous, next);
        animating = true;

        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(previous.translateXProperty(), 0, EASE),
                        new KeyValue(previous.opacityProperty(), 1, EASE),
                        new KeyValue(next.translateXProperty(), offset, EASE),
                        new KeyValue(next.opacityProperty(), direction == Direction.FADE ? 0 : 1, EASE)),
                new KeyFrame(DURATION,
                        new KeyValue(previous.translateXProperty(), -offset, EASE),
                        new KeyValue(previous.opacityProperty(), 0, EASE),
                        new KeyValue(next.translateXProperty(), 0, EASE),
                        new KeyValue(next.opacityProperty(), 1, EASE)));

        timeline.setOnFinished(e -> {
            animating = false;
            reset(previous, true);
            getChildren().remove(previous);
            reset(next, true);
        });
        timeline.play();
    }

}
