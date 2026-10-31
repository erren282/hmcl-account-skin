package org.dsh.accountskin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the 曾使用过 bookkeeping.
 *
 * <h2>What is worth testing here</h2>
 * <p>The history is the only state this plugin keeps between launches, so its rules are
 * the ones a user notices when they go wrong: the same skin applied twice must not
 * appear twice, skin and cape must not mix, entries whose file disappeared must not be
 * offered, and the list must survive a restart. All of that is plain data handling, so
 * it is checked directly rather than through the dialog.</p>
 */
public final class HistoryTest {

    /// The account these checks operate on.
    private static final String OWNER = "account:test-owner";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("account-skin-history");
        try {
            Path store = dir.resolve("history.json");
            SkinHistory.initialize(store);
            SkinHistory history = SkinHistory.get();

            System.out.println("=== 记录与去重 ===");
            Path skinFile = write(dir.resolve("a.png"));
            Path capeFile = write(dir.resolve("b.png"));

            history.record(entry("skin", 11, "Steve skin", skinFile));
            check("a recorded skin is listed", history.count(OWNER, "skin") == 1);

            // Applying the same library entry again must move it, not duplicate it.
            history.record(entry("skin", 11, "Steve skin", skinFile));
            check("the same entry is not recorded twice", history.count(OWNER, "skin") == 1);

            Path otherSkin = write(dir.resolve("c.png"));
            history.record(entry("skin", 12, "Alex skin", otherSkin));
            check("a different entry is recorded", history.count(OWNER, "skin") == 2);
            check("the newest is first",
                    "Alex skin".equals(history.entries(OWNER, "skin").get(0).name));

            System.out.println();
            System.out.println("=== 皮肤与披风分区 ===");
            history.record(entry("cape", 21, "Cape", capeFile));
            check("a cape does not appear among skins", history.count(OWNER, "skin") == 2);
            check("a cape is listed among capes", history.count(OWNER, "cape") == 1);
            check("a skin does not appear among capes",
                    !"Alex skin".equals(history.entries(OWNER, "cape").get(0).name));

            System.out.println();
            System.out.println("=== 文件消失的条目不再提供 ===");
            Path doomed = write(dir.resolve("gone.png"));
            history.record(entry("skin", 13, "Doomed", doomed));
            check("it is listed while the file exists", history.count(OWNER, "skin") == 3);
            Files.delete(doomed);
            check("it disappears once the file is gone", history.count(OWNER, "skin") == 2);

            System.out.println();
            System.out.println("=== 重新应用会把它提到最前 ===");
            history.record(entry("skin", 11, "Steve skin", skinFile));
            check("the re-applied entry is first",
                    "Steve skin".equals(history.entries(OWNER, "skin").get(0).name));
            check("and it was not duplicated", history.count(OWNER, "skin") == 2);

            System.out.println();
            System.out.println("=== 重启后仍在 ===");
            String json = Files.readString(store);
            check("the store file was written", json.contains("Steve skin"));
            check("it records the kind", json.contains("\"skin\""));
            check("it records the local path", json.contains("a.png"));

            System.out.println();
            System.out.println("=== 上限 ===");
            for (int i = 0; i < SkinHistory.MAX_ENTRIES + 15; i++) {
                history.record(entry("cape", 1000 + i, "cape " + i, write(dir.resolve("cap" + i + ".png"))));
            }
            List<SkinHistory.Entry> capes = history.entries(OWNER, "cape");
            check("the cape list is capped at " + SkinHistory.MAX_ENTRIES,
                    capes.size() == SkinHistory.MAX_ENTRIES);
            check("the cap dropped the oldest, not the newest",
                    ("cape " + (SkinHistory.MAX_ENTRIES + 14)).equals(capes.get(0).name));
            check("skins were not trimmed by the cape overflow", history.count(OWNER, "skin") == 2);

            System.out.println();
            System.out.println("=== 相对时间 ===");
            long now = System.currentTimeMillis();
            check("a fresh entry reads 刚刚", "刚刚".equals(HistoryGridPage.describeAge(now)));
            check("five minutes ago", "5 分钟前".equals(
                    HistoryGridPage.describeAge(now - 5 * 60_000L)));
            check("three hours ago", "3 小时前".equals(
                    HistoryGridPage.describeAge(now - 3 * 3_600_000L)));
            check("two days ago", "2 天前".equals(
                    HistoryGridPage.describeAge(now - 2 * 86_400_000L)));

            System.out.println();
            System.out.println("=== 模型与形态随记录保存 ===");
            Path slimSkin = write(dir.resolve("slim.png"));
            SkinHistory.Entry slimEntry = entry("skin", 31, "Slim skin", slimSkin);
            slimEntry.slim = true;
            history.record(slimEntry);
            check("the slim flag is kept", history.entries(OWNER, "skin").get(0).slim);

            Path elytraCape = write(dir.resolve("ely.png"));
            SkinHistory.Entry elytraEntry = entry("cape", 32, "Elytra cape", elytraCape);
            elytraEntry.elytra = true;
            history.record(elytraEntry);
            check("the elytra flag is kept", history.entries(OWNER, "cape").get(0).elytra);

            // Re-recording the same texture with a different model must update the flag,
            // not add a second row: the user changed their mind, they did not gain a skin.
            int beforeSwitch = history.count(OWNER, "skin");
            SkinHistory.Entry switched = entry("skin", 31, "Slim skin", slimSkin);
            switched.slim = false;
            history.record(switched);
            check("switching the model does not add a row",
                    history.count(OWNER, "skin") == beforeSwitch);
            check("and it updates the stored model",
                    !history.entries(OWNER, "skin").get(0).slim);

            System.out.println();
            System.out.println("=== 删除 ===");
            int beforeDelete = history.count(OWNER, "skin");
            history.forget(history.entries(OWNER, "skin").get(0));
            check("deleting removes exactly one row",
                    history.count(OWNER, "skin") == beforeDelete - 1);
            check("the deleted name is gone",
                    history.entries(OWNER, "skin").stream().noneMatch(e -> "Slim skin".equals(e.name)));
            check("the cape list was not touched", history.count(OWNER, "cape") > 0);

            System.out.println();
            System.out.println("=== 每个账户各记各的 ===");
            String otherOwner = "account:someone-else";
            Path otherFile = write(dir.resolve("other.png"));

            int mineBefore = history.count(OWNER, "skin");
            SkinHistory.Entry theirs = entry("skin", 41, "Their skin", otherFile);
            theirs.owner = otherOwner;
            history.record(theirs);

            check("my list did not grow", history.count(OWNER, "skin") == mineBefore);
            check("their list has the entry", history.count(otherOwner, "skin") == 1);
            check("their entry is not visible in mine",
                    history.entries(OWNER, "skin").stream().noneMatch(e -> "Their skin".equals(e.name)));
            check("my entries are not visible in theirs",
                    history.entries(otherOwner, "skin").stream().noneMatch(e -> "Slim skin".equals(e.name)));

            // The same texture applied by two accounts must stay two rows: each account
            // has its own history, so "already used" is per account, not global.
            SkinHistory.Entry shared = entry("skin", 42, "Shared skin", write(dir.resolve("shared.png")));
            history.record(shared);
            SkinHistory.Entry sharedToo = entry("skin", 42, "Shared skin", Path.of(shared.path));
            sharedToo.owner = otherOwner;
            history.record(sharedToo);
            check("the same skin is kept once per account",
                    history.count(OWNER, "skin") == mineBefore + 1
                            && history.count(otherOwner, "skin") == 2);

            System.out.println();
            System.out.println("=== 删除只影响自己 ===");
            int theirBefore = history.count(otherOwner, "skin");
            history.forget(history.entries(OWNER, "skin").get(0));
            check("deleting mine leaves theirs alone",
                    history.count(otherOwner, "skin") == theirBefore);

            System.out.println();
            System.out.println("=== 老记录（没有 owner）不会丢 ===");
            SkinHistory.Entry legacy = new SkinHistory.Entry();
            legacy.kind = "skin";
            legacy.tid = 51;
            legacy.name = "Legacy skin";
            legacy.type = "steve";
            legacy.path = write(dir.resolve("legacy.png")).toString();
            history.record(legacy);
            check("a record without an owner is still stored",
                    history.entries(SkinHistory.LEGACY_OWNER, "skin").size() == 1);
            check("and it does not leak into a real account's list",
                    history.entries(OWNER, "skin").stream().noneMatch(e -> "Legacy skin".equals(e.name)));
        } finally {
            deleteRecursively(dir);
        }

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static SkinHistory.Entry entry(String kind, int tid, String name, Path file) {
        SkinHistory.Entry entry = new SkinHistory.Entry();
        entry.owner = OWNER;
        entry.kind = kind;
        entry.tid = tid;
        entry.name = name;
        entry.type = "cape".equals(kind) ? "cape" : "steve";
        entry.path = file.toString();
        return entry;
    }

    private static Path write(Path file) throws Exception {
        Files.write(file, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        return file;
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok]   " + what);
        } else {
            failed++;
            System.out.println("  [!!]   " + what);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir))
            return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
