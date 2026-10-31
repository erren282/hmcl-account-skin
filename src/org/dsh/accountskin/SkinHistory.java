package org.dsh.accountskin;

import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 曾使用过的皮肤与披风记录。
 *
 * <h2>为什么记在插件自己的目录里</h2>
 * <p>启动器的账户数据里只有“当前生效的那一份”皮肤，换掉就没了。想回到用过的上一个，
 * 用户唯一的办法是回皮肤库里重新找一遍——而皮肤库的搜索对未登录客户端是关掉的
 * （见 {@link LocalSearch}），找回来并不容易。所以这里单独记一份历史。</p>
 *
 * <p>记录的是<b>本地材质文件路径</b>而不是皮肤库的 tid：应用时只需要这个文件，
 * 记 tid 的话每次重应用都要再联网取一次材质，而皮肤库慢的时候那一步很痛。
 * 材质本来就已经下载到插件缓存目录里了，留着它就是。</p>
 *
 * <h2>分区</h2>
 * <p>皮肤与披风分成两个列表各自记录，互不干扰：披风的贴图区域和皮肤完全不同，
 * 混在一起既没法预览也没法应用。</p>
 *
 * <h2>记下模型</h2>
 * <p>每条同时记下当时用的手臂模型（宽臂/纤细）与披风形态（普通/鞘翅），
 * 再次使用时按原样恢复，而不是回到默认值——用户当初怎么穿的，就该怎么回来。</p>
 */
public final class SkinHistory {

    /// 历史记录里的一条。字段名就是 JSON 里的键名，改名会让老记录读不出来。
    public static class Entry {
        /// 这条记录属于哪个账户（`AccountID` 的字符串形式）。
        ///
        /// <p>皮肤是账户自己的属性：同一个启动器里两个离线账户各用各的皮肤，
        /// 混在一个列表里既没法辨认也没法应用（点一条就等于把别人的皮肤换到自己身上）。
        /// 老记录没有这个字段，读出来是 null，会被归到 {@link #LEGACY_OWNER}。</p>
        public String owner;
        /// `skin` 或 `cape`。
        public String kind;
        /// 皮肤库条目 id；本地文件为 0。
        public int tid;
        public String name;
        public String author;
        /// 皮肤库的原始类型（`steve` / `alex` / `cape`），用于缩略图按宽臂/纤细/披风绘制。
        public String type;
        public boolean hd;
        /// 本地材质文件。历史条目靠它预览与应用，不依赖网络。
        public String path;
        /// 上次使用时的手臂模型是否为纤细（艾利克斯）。
        public boolean slim;
        /// 上次使用时的披风形态是否为鞘翅（仅披风条目有意义）。
        public boolean elytra;
        /// 最后一次使用的时刻，毫秒时间戳。
        public long usedAt;

        /// 同一件材质的判据：皮肤库条目看 tid，本地文件看路径。
        ///
        /// <p>两件材质算不算“同一件”，决定了重复应用是新增一条还是把旧条目提到最前。
        /// 用 tid 而不是路径，是因为同一个皮肤库条目每次下载都写同一个文件名，
        /// 但用户也可能换过缓存目录。</p>
        String identity() {
            return tid > 0 ? "t" + tid : "p" + path;
        }
    }

    /// 没有 `owner` 字段的老记录归到这个名下，这样升级后它们还看得见，
    /// 而不是无声消失。用户可以在列表里删掉它们。
    static final String LEGACY_OWNER = "";

    /// 落盘用的外层结构。带版本号，将来字段变动时还有得救。
    static class Store {
        int version = 1;
        List<Entry> entries = new ArrayList<>();
    }

    /// 每个分区最多保留多少条。材质文件只有几十 KB，但没必要无限长下去。
    static final int MAX_ENTRIES = 60;

    private static final SkinHistory INSTANCE = new SkinHistory();

    /// 插件启用时通过 {@link #initialize(Path)} 设置；为 null 时只在内存里记，
    /// 不落盘（构造面板的测试就是这样跑的）。
    private Path file;
    private final List<Entry> entries = new ArrayList<>();

    private SkinHistory() {
    }

    public static SkinHistory get() {
        return INSTANCE;
    }

    /// 指定落盘文件并载入已有记录。可重复调用，只有第一次生效。
    public static synchronized void initialize(Path historyFile) {
        if (historyFile == null || INSTANCE.file != null)
            return;
        INSTANCE.file = historyFile;
        INSTANCE.load();
    }

    // ==== 读 ====

    /// 某个账户、某个分区（{@code skin} / {@code cape}）的记录，最近使用的在前。
    ///
    /// <p>材质文件已经被删掉的条目会被跳过：留着它只会得到一个点不动的死条目。</p>
    ///
    /// @param owner 账户标识，见 {@link #ownerOf}
    public synchronized List<Entry> entries(String owner, String kind) {
        List<Entry> result = new ArrayList<>();
        boolean pruned = false;
        for (Entry entry : entries) {
            if (!Objects.equals(entry.kind, kind) || !Objects.equals(normalizeOwner(entry.owner), normalizeOwner(owner)))
                continue;
            if (entry.path == null || !Files.isRegularFile(Path.of(entry.path))) {
                pruned = true;
                continue;
            }
            result.add(entry);
        }
        if (pruned) {
            entries.removeIf(entry -> entry.path == null || !Files.isRegularFile(Path.of(entry.path)));
            save();
        }
        return result;
    }

    /// 记录数量，用于切换按钮上的角标。
    public synchronized int count(String owner, String kind) {
        return entries(owner, kind).size();
    }

    // ==== 写 ====

    /// 记下一次使用。同一账户下已存在的同一件材质会被提到最前并更新时间，不会重复。
    public synchronized void record(Entry entry) {
        if (entry == null || entry.kind == null || entry.path == null)
            return;
        if (!Files.isRegularFile(Path.of(entry.path)))
            return;
        entry.usedAt = System.currentTimeMillis();
        entry.owner = normalizeOwner(entry.owner);

        String identity = entry.identity();
        String owner = entry.owner;
        entries.removeIf(existing -> Objects.equals(existing.owner, owner)
                && identity.equals(existing.identity()));
        entries.add(0, entry);

        // 按账户 + 分区分别限长，避免一个账户把另一个账户的记录挤掉。
        trim(owner, "skin");
        trim(owner, "cape");
        save();
    }

    /// 从历史里删掉一条（同一件材质）。
    public synchronized void forget(Entry entry) {
        if (entry == null || entry.kind == null)
            return;
        String identity = entry.identity();
        String owner = normalizeOwner(entry.owner);
        if (entries.removeIf(existing -> Objects.equals(existing.kind, entry.kind)
                && Objects.equals(normalizeOwner(existing.owner), owner)
                && identity.equals(existing.identity())))
            save();
    }

    private void trim(String owner, String kind) {
        int seen = 0;
        List<Entry> drop = new ArrayList<>();
        for (Entry entry : entries) {
            if (!Objects.equals(entry.kind, kind) || !Objects.equals(normalizeOwner(entry.owner), owner))
                continue;
            if (++seen > MAX_ENTRIES)
                drop.add(entry);
        }
        entries.removeAll(drop);
    }

    /// 老记录没有 owner 字段，统一归到 {@link #LEGACY_OWNER}。
    private static String normalizeOwner(String owner) {
        return owner == null ? LEGACY_OWNER : owner;
    }

    // ==== 落盘 ====

    private void load() {
        if (file == null || !Files.isRegularFile(file))
            return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Store store = JsonUtils.fromJson(json, Store.class);
            if (store != null && store.entries != null) {
                entries.clear();
                for (Entry entry : store.entries) {
                    if (entry != null && entry.kind != null)
                        entries.add(entry);
                }
            }
        } catch (Throwable e) {
            // 记录坏了不该拦住用户切皮肤：丢掉重来即可。
            entries.clear();
        }
    }

    private void save() {
        if (file == null)
            return;
        try {
            Store store = new Store();
            store.entries = new ArrayList<>(entries);
            Path parent = file.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            // 先写临时文件再替换：中途退出不会留下半截 JSON 把历史整个弄丢。
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, JsonUtils.UGLY_GSON.toJson(store), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 写不进去只影响历史记录，不影响当前这次应用。
        }
    }
}
