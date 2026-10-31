package org.dsh.accountskin;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.game.TexturesLoader;

/**
 * 在 40×40 的小画布上画皮肤头像或披风缩略图。
 *
 * <p>皮肤库列表和“曾使用过”列表都要画同样的缩略图，所以放在一处：两处各画一遍的话，
 * 改了一边忘了另一边，同一个材质在两个列表里会长得不一样。</p>
 */
final class Thumbnails {

    private Thumbnails() {
    }

    /// 画一张缩略图。`isCape` 决定取披风区域还是头部正面。
    static void draw(Canvas canvas, Image image, boolean isCape) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (image == null || image.isError() || image.getProgress() < 1.0 || image.getWidth() == 0)
            return;

        g.setImageSmoothing(false);
        if (isCape) {
            // 披风正面区域约占 (1,1)-(11,17)，按原图比例缩放绘制
            double scale = image.getWidth() / 64.0;
            double w = 10 * scale, h = 16 * scale;
            double fit = Math.min((canvas.getWidth() - 4) / w, (canvas.getHeight() - 4) / h);
            g.drawImage(image, scale, scale, w, h,
                    (canvas.getWidth() - w * fit) / 2, (canvas.getHeight() - h * fit) / 2,
                    w * fit, h * fit);
        } else {
            // 皮肤：绘制头部正面（含帽层）
            TexturesLoader.drawAvatar(canvas, image);
        }
    }

    /// 从列表项的 `type` 判断是不是披风。
    static boolean isCape(String type) {
        return "cape".equals(type);
    }
}
