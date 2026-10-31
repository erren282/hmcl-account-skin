package org.dsh.accountskin;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniRunning;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniWavingArms;

/**
 * 支持鞘翅形态的 3D 皮肤画布。
 *
 * <p>鞘翅按原版渲染方式制作：披风贴图直接充当鞘翅贴图，翅盒为
 * texOffs(22,0) 的 10x20x2（视觉上各向膨胀 1 体素，同原版
 * CubeDeformation），朝外的翼面取自贴图 (36,2)-(46,22) 区域。
 * 姿态与 skinview3d（SJMCL 等启动器使用的渲染库）的 ElytraObject
 * 一致：双翼枢轴 x=±5、翼盒再向中心偏移 ∓5，绕 X 轴前倾 15°、
 * 绕 Z 轴外张 15°（右翼镜像），并各带一点绕 Y 的微角错开两翼、
 * 防止共面深度冲突。</p>
 *
 * <p>通过 {@link #setModel(Image, boolean, Image, boolean)} 使用：
 * {@code elytra=true} 时隐藏背部的普通披风、展示鞘翅翅膀；展示披风时视角转到背面，
 * 仅预览皮肤（无披风）时为正面。</p>
 */
public class ElytraSkinCanvas extends SkinCanvas {

    /** 原版/skinview3d 的翼面倾角 0.2617994 rad ≈ 15°，JavaFX 为 y 轴向下，X/Z 旋转取负。 */
    private static final double WING_TILT_DEG = 15;
    /** 两翼绕 Y 轴错开的微角：skinview3d 用 ±0.01 rad，JavaFX 深度精度更低，取 ±0.05 rad 阻止共面闪烁。 */
    private static final double WING_SPREAD_RAD = 0.05;

    private final PhongMaterial wingMaterial = new PhongMaterial();
    private final Group elytraGroup = new Group();
    private final MeshView leftWing;
    private final MeshView rightWing;
    private boolean elytraMode = false;
    private Image capeTexture;

    public ElytraSkinCanvas(double width, double height) {
        super(TexturesLoader.getDefaultSkinImage(), width, height, true);

        leftWing = createWingMesh();
        rightWing = createWingMesh();
        leftWing.setMaterial(wingMaterial);
        rightWing.setMaterial(wingMaterial);
        leftWing.setCullFace(CullFace.BACK);
        rightWing.setCullFace(CullFace.BACK);

        // skinview3d ElytraObject（three.js，y 向上）换算到 JavaFX（y 向下、左臂在 -x）：
        // 位置取反 x/y，绕 X/Y 轴的旋转取负、绕 Z 轴不变。
        // 左翼：枢轴 (5,0,0) → (-5,0,0)，翼盒偏移 (-5,-10,-1) → (5,10,-1)，倾角 x=15°/z=15° → (-15°, +15°)。
        leftWing.setTranslateX(5);
        leftWing.setTranslateY(10);
        leftWing.setTranslateZ(-1);
        Group leftPivot = new Group(leftWing);
        leftPivot.setTranslateX(-5);
        leftPivot.getTransforms().addAll(
                new Rotate(-WING_TILT_DEG, Rotate.X_AXIS),
                new Rotate(-WING_SPREAD_RAD, Rotate.Y_AXIS),
                new Rotate(WING_TILT_DEG, Rotate.Z_AXIS));

        // 右翼：枢轴 (-5,0,0) → (5,0,0)，翼盒偏移 (5,-10,-1) → (-5,10,-1) 并镜像 x。
        rightWing.setTranslateX(-5);
        rightWing.setTranslateY(10);
        rightWing.setTranslateZ(-1);
        rightWing.setScaleX(-1);
        Group rightPivot = new Group(rightWing);
        rightPivot.setTranslateX(5);
        rightPivot.getTransforms().addAll(
                new Rotate(-WING_TILT_DEG, Rotate.X_AXIS),
                new Rotate(WING_SPREAD_RAD, Rotate.Y_AXIS),
                new Rotate(-WING_TILT_DEG, Rotate.Z_AXIS));

        // 翼盒整体挂在颈部高度（HMCL 模型 body 中心 y=0、颈部 y=-6）、体后 z=-2。
        elytraGroup.getChildren().setAll(leftPivot, rightPivot);
        elytraGroup.setTranslateY(-6);
        elytraGroup.setTranslateZ(-2);
        elytraGroup.setVisible(false);
        root.getChildren().add(elytraGroup); // protected root，子类可访问

        getAnimationPlayer().addSkinAnimation(
                new SkinAniWavingArms(100, 2000, 7.5, this),
                new SkinAniRunning(100, 100, 30, this));
        enableRotation(0.5);
    }

    /**
     * 构建单片翼面：12x22x4 的盒（10x20x2 膨胀 1 体素），逐面使用披风贴图
     * texOffs(22,0) 的六面展开 UV；每个三角形同时给出正反两组绕组，
     * 配合背面剔除后两侧都有正确朝向的可见面。
     */
    private static MeshView createWingMesh() {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().addAll(
                -6, -11, 2, -6, -11, -2, -6, 11, 2,
                -6, 11, -2, 6, -11, -2, 6, -11, 2,
                6, 11, -2, 6, 11, 2, 6, -11, -2,
                -6, -11, -2, 6, -11, 2, -6, -11, 2,
                6, 11, 2, -6, 11, 2, 6, 11, -2,
                -6, 11, -2, 6, -11, 2, -6, -11, 2,
                6, 11, 2, -6, 11, 2, -6, -11, -2,
                6, -11, -2, -6, 11, -2, 6, 11, -2);
        mesh.getTexCoords().addAll(
                0.53125f, 0.0625f, 0.5625f, 0.0625f, 0.53125f, 0.6875f, 0.5625f, 0.6875f,
                0.34375f, 0.0625f, 0.375f, 0.0625f, 0.34375f, 0.6875f, 0.375f, 0.6875f,
                0.375f, 0f, 0.53125f, 0f, 0.375f, 0.0625f, 0.53125f, 0.0625f,
                0.53125f, 0.0625f, 0.6875f, 0.0625f, 0.53125f, 0f, 0.6875f, 0f,
                0.375f, 0.0625f, 0.53125f, 0.0625f, 0.375f, 0.6875f, 0.53125f, 0.6875f,
                0.5625f, 0.0625f, 0.71875f, 0.0625f, 0.5625f, 0.6875f, 0.71875f, 0.6875f);
        mesh.getFaces().addAll(
                0, 0, 2, 2, 1, 1, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3, 1, 1,
                1, 1, 3, 3, 2, 2, 4, 4, 6, 6, 5, 5, 5, 5, 6, 6, 4, 4,
                6, 6, 7, 7, 5, 5, 5, 5, 7, 7, 6, 6, 8, 8, 10, 10, 9, 9,
                9, 9, 10, 10, 8, 8, 10, 10, 11, 11, 9, 9, 9, 9, 11, 11, 10, 10,
                12, 12, 14, 14, 13, 13, 13, 13, 14, 14, 12, 12, 14, 14, 15, 15, 13, 13,
                13, 13, 15, 15, 14, 14, 16, 16, 18, 18, 17, 17, 17, 17, 18, 18, 16, 16,
                18, 18, 19, 19, 17, 17, 17, 17, 19, 19, 18, 18, 20, 20, 22, 22, 21, 21,
                21, 21, 22, 22, 20, 20, 22, 22, 23, 23, 21, 21, 21, 21, 23, 23, 22, 22);
        return new MeshView(mesh);
    }

    /// Updates the model: `elytra=true` renders the cape as elytra wings instead of
    /// the normal cape quad on the back. The camera faces the back whenever a cape
    /// is involved (elytra: 背面偏侧 315°；普通披风: 正背面 0°), and faces the front
    /// only for pure skin previews (no cape).
    ///
    /// <h2>为什么不在每次刷新时重置相机</h2>
    /// <p>相机角度原来每次调用都被写死：皮肤→正面 180°，披风→背面 0°/315°。这在“点一个来源
    /// 按钮换一套材质”时是对的，但“来回切形态按钮”时就成了抽搐：用户刚把角色转到侧面看清
    /// 对比，每点一次形态就被拽回正面/背面。所以现在只在<b>材质换了</b>时才重新摆相机，
    /// 单纯改形态保持用户当前的视角。</p>
    public void setModel(Image skin, boolean isSlim, Image cape, boolean elytra) {
        boolean textureChanged = skin != lastSkin || cape != lastCape;
        boolean capePresenceChanged = (cape == null) != (lastCape == null);

        elytraMode = elytra;
        capeTexture = cape;
        lastSkin = skin;
        lastCape = cape;

        super.updateSkin(skin, isSlim, elytra ? null : cape);

        // 只在换了材质、或“有没有披风”这件事变了时才重新摆相机。
        if (textureChanged || capePresenceChanged || !cameraPlaced) {
            if (elytra) {
                yRotate.setAngle(315); // 背面偏侧，展示鞘翅
            } else if (cape != null) {
                yRotate.setAngle(0);   // 普通披风：背面
            } else {
                yRotate.setAngle(180); // 纯皮肤预览：正面
            }
            xRotate.setAngle(0);
            cameraPlaced = true;
        }
        refreshWings();
    }

    private Image lastSkin, lastCape;

    /// 相机是否已经摆过一次。第一次必须摆，否则会停在父类默认的 180°。
    private boolean cameraPlaced;

    private void refreshWings() {
        if (elytraGroup == null)
            return; // super 构造期间会虚调用 updateSkin，此时翅膀尚未初始化

        elytraGroup.setVisible(elytraMode && capeTexture != null);
        if (capeTexture != null && capeTexture != enlargedCapeSource) {
            // 放大是一张 256x256 的逐像素拷贝，每帧做一次是纯浪费；
            // 披风贴图换来换去就那几张，记住上一次的结果就够了。
            Image texture = capeTexture.getWidth() < 256
                    ? SkinHelperEnlarge.enlarge(capeTexture)
                    : capeTexture;
            enlargedCapeSource = capeTexture;
            wingMaterial.setDiffuseMap(texture);
        }
        // 父类 updateSkin -> bindMaterial 会把所有几何体重新指到皮肤材质，
        // 这里必须把翅膀的材质重新指回披风材质。
        leftWing.setMaterial(wingMaterial);
        rightWing.setMaterial(wingMaterial);
    }

    /// 上一次放大过的披风贴图，避免每帧重复放大。
    private Image enlargedCapeSource;

    @Override
    public void updateSkin(Image skin, boolean isSlim, Image cape) {
        // 材质没变、只有手臂模型变时，只重算几何，不重建整棵模型。
        //
        // 父类的 updateSkin -> bindMaterial 会为外层装饰（帽子、外套、袖子…）逐个像素
        // 建 Box 节点：帽子一层就是 8x8 个，加外套和四肢一共几百上千个节点，每次重建
        // 都要几十毫秒，期间画面停住再跳到新状态——来回点形态按钮看到的就是这个抽搐。
        //
        // 而“手臂变宽变窄”只需要更新手臂那块几何，装饰件完全不用动。所以分开处理：
        // 材质真变了才走父类的完整重绑，只改模型就自己调 updateSkinModel。
        boolean textureChanged = skin != appliedSkin || cape != appliedCape;
        boolean slimChanged = isSlim != appliedSlim;

        if (appliedOnce && !textureChanged) {
            if (slimChanged) {
                updateSkinModel(isSlim, cape != null);
                appliedSlim = isSlim;
                refreshWings();
            }
            return;
        }

        appliedSkin = skin;
        appliedCape = cape;
        appliedSlim = isSlim;
        appliedOnce = true;

        super.updateSkin(skin, isSlim, cape);
        refreshWings();
    }

    private Image appliedSkin, appliedCape;
    private boolean appliedSlim;

    /// 是否已经应用过一次。第一次必须让父类跑一遍，否则模型没有材质。
    private boolean appliedOnce;

    /// 独立的放大工具（避免直接依赖 SkinHelper 的包私有细节）。
    private static final class SkinHelperEnlarge {
        static Image enlarge(Image source) {
            int mx = Math.max((int) (256 / source.getWidth()), 1);
            int my = Math.max((int) (256 / source.getHeight()), 1);
            int w = (int) source.getWidth() * mx, h = (int) source.getHeight() * my;
            javafx.scene.image.WritableImage out = new javafx.scene.image.WritableImage(w, h);
            javafx.scene.image.PixelReader reader = source.getPixelReader();
            javafx.scene.image.PixelWriter writer = out.getPixelWriter();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    writer.setColor(x, y, reader.getColor(
                            Math.min((int) (x / (double) mx), (int) source.getWidth() - 1),
                            Math.min((int) (y / (double) my), (int) source.getHeight() - 1)));
            return out;
        }
    }
}
