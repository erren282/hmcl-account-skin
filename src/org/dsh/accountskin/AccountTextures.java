package org.dsh.accountskin;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.Skin;
import org.jackhuang.hmcl.auth.yggdrasil.Texture;
import org.jackhuang.hmcl.auth.yggdrasil.TextureModel;
import org.jackhuang.hmcl.auth.yggdrasil.TextureType;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.util.Lang;

import java.util.Map;
import java.util.Optional;

/**
 * 读取账户当前穿着的皮肤与披风。
 *
 * <h2>为什么要单独做</h2>
 * <p>{@code TexturesLoader.skinBinding(account)} 只返回<b>皮肤</b>，披风不在里面。
 * 结果就是披风预览一直画不出披风——账户明明穿着披风，预览里却只有光着背的角色。</p>
 *
 * <p>两条来源不一样：</p>
 * <ul>
 *   <li>离线账户：披风在本地 `Skin.localCapePath`，或随 LittleSkin 皮肤源按用户名取；</li>
 *   <li>在线账户（微软 / 皮肤站）：披风在 `Account.getTextures()` 的 `CAPE` 项里，
 *       要先联网取贴图，所以是异步的。</li>
 * </ul>
 */
final class AccountTextures {

    private AccountTextures() {
    }

    /// 账户当前的手臂模型。
    static boolean isSlim(Account account) {
        try {
            if (account instanceof OfflineAccount offline) {
                Skin skin = offline.getSkin();
                if (skin != null)
                    return skin.textureModel() == TextureModel.SLIM;
            }
            TexturesLoader.LoadedTexture texture = TexturesLoader.skinBinding(account).getValue();
            return texture != null && "slim".equals(texture.metadata().get("model"));
        } catch (Throwable t) {
            return false;
        }
    }

    /// 账户当前的皮肤贴图。取不到时用内置默认皮肤，保证预览里总有个人。
    static Image skin(Account account) {
        try {
            TexturesLoader.LoadedTexture texture = TexturesLoader.skinBinding(account).getValue();
            if (texture != null && texture.image() != null && !texture.image().isError())
                return texture.image();
        } catch (Throwable ignored) {
        }
        return TexturesLoader.getDefaultSkinImage();
    }

    /**
     * 取账户当前穿着的披风贴图；没有披风时回调 {@code null}。
     *
     * <p>在线账户要联网下载贴图，所以结果通过回调异步给出，且总在 JavaFX 线程上。
     * 离线账户是本地文件，同步就能拿到，但仍然走同一条回调路径，
     * 免得调用方要为两种账户写两套逻辑。</p>
     *
     * @param onLoaded 拿到披风（或确认没有披风）后调用，参数可能为 null
     */
    static void cape(Account account, java.util.function.Consumer<Image> onLoaded) {
        if (account instanceof OfflineAccount offline) {
            Skin skin = offline.getSkin();
            if (skin == null || skin.type() == Skin.Type.DEFAULT) {
                onLoaded.accept(null);
                return;
            }
            // 本地文件直接读；LittleSkin 皮肤源要联网，但它是 Task，放到后台跑。
            // Task.whenComplete 的回调是 FinalizedCallbackWithResult：(result, exception)，
            // 与 CompletableFuture 那一套同名但不同接口，所以这里显式写清类型。
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "account-skin-cape");
                thread.setDaemon(true);
                return thread;
            });
            org.jackhuang.hmcl.task.Task.FinalizedCallbackWithResult<Skin.LoadedSkin> callback =
                    (loaded, exception) -> Platform.runLater(() -> {
                        pool.shutdown();
                        if (exception != null || loaded == null || loaded.cape() == null) {
                            onLoaded.accept(null);
                        } else {
                            onLoaded.accept(loaded.cape().image());
                        }
                    });
            skin.load(offline.getProfileName()).setExecutor(pool).whenComplete(pool, callback).start();
            return;
        }

        // 在线账户：贴图在 getTextures() 里，要先联网取。
        try {
            var binding = account.getTextures();
            Optional<Map<TextureType, Texture>> value = binding.getValue();
            if (value != null && value.isPresent()) {
                offer(value.get().get(TextureType.CAPE), onLoaded);
                return;
            }
            // 还没取到就等它：绑定完成后再看一次。
            binding.addListener((obs, old, now) -> {
                if (now != null && now.isPresent())
                    offer(now.get().get(TextureType.CAPE), onLoaded);
            });
        } catch (Throwable t) {
            onLoaded.accept(null);
        }
    }

    /// 下载一张贴图并回调；`texture` 为 null 表示账户没有披风。
    private static void offer(Texture texture, java.util.function.Consumer<Image> onLoaded) {
        if (texture == null || texture.url() == null || texture.url().isBlank()) {
            onLoaded.accept(null);
            return;
        }
        Lang.thread(() -> {
            Image image = null;
            try {
                TexturesLoader.LoadedTexture loaded = TexturesLoader.loadTexture(texture);
                image = loaded.image();
            } catch (Throwable ignored) {
                // 披风下载失败就当作没有披风：预览里少一件披风，比整个对话框报错好。
            }
            Image result = image;
            Platform.runLater(() -> onLoaded.accept(result));
        }, "account-skin-cape-download", true);
    }
}
