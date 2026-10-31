# 账户皮肤切换

[![HMCL Plugin](https://img.shields.io/badge/HMCL-plugin-blue)](https://github.com/topics/erren282s-launcher-plugins)
[![Version](https://img.shields.io/badge/version-1.1.0-green)]()
[![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)](LICENSE)

切换与管理账户皮肤，隐藏原生上传按钮

- **插件 ID**：`org.dsh.account-skin`
- **适用启动器**：HMCL 3.16.4 定制版（需内建插件系统）
- **安装**：把 `org.dsh.account-skin_1.1.0.jar` 放入启动器目录的 `.hmcl/plugins/`，重启或在「设置 → 插件」里重新加载

> 本插件依赖 [erren282-lauuncher](https://github.com/erren282/erren282-lauuncher) 的插件系统。
> 上游 HMCL 没有该机制，无法直接装到官方版上。

---
在**账户列表**的每个账户上注入“切换皮肤”“切换披风”两个按钮，
支持三种账户类型的皮肤与披风切换：

| 账户类型 | 在线皮肤（LittleSkin） | 自定义皮肤（本地文件） | 披风 |
| --- | --- | --- | --- |
| 微软账户 | 从 LittleSkin 皮肤库挑选 → 上传到 Mojang（**全局生效**） | 本地文件 → 上传到 Mojang | 切换账户**已拥有的官方披风**（Mojang 不支持上传披风） |
| 皮肤站账户（外置登录） | 从 LittleSkin 皮肤库挑选 → 上传到皮肤站（**站内生效**，需皮肤站支持材质上传，LittleSkin 支持） | 本地文件 → 上传到皮肤站 | 同皮肤（Yggdrasil 披风上传接口） |
| 离线账户 | “跟随 LittleSkin 皮肤源”（皮肤+披风按用户名跟随）或从皮肤库挑选下载 | 本地文件（**游戏内生效**，经 HMCL 本地皮肤源注入） | 本地披风文件 / 跟随 LittleSkin |

对话框内含**整体 3D 实时预览**（拖动旋转 / 滚轮缩放）：

- 从皮肤库或本地文件选中皮肤/披风时，左侧即时渲染穿戴后的整体效果；
- 皮肤可切换**史蒂夫形态 / 艾利克斯形态**（宽臂 / 纤细手臂）；
- 披风可切换**普通形态**（穿在史蒂夫背上的披风）与**鞘翅形态**（按原版方式渲染：
  原版玩家鞘翅直接使用玩家披风贴图，翼面 UV 为披风贴图 (36,4)-(46,24) 区域
  （texOffs(34,2) 10x20x2 双翼的原版姿态 xRot=15°、zRot=∓15°），切换时自动转到背面视角）；
- 皮肤库列表中选中的行以蓝色圆角方框高亮。

## 曾使用过

主对话框里有一个「曾使用过（N）」按钮，点进去是**网格形式的皮肤/披风选择页**。

分成两页而不是挤在一屏：

1. **网格页**——挑哪一件。铺成大缩略图，一屏十来个。翻历史时人是在“认图”，
   不是在读名字，皮肤长什么样一眼就能看出来。
   每张卡片右上角有删除按钮（悬停时显现），删掉只是从历史里移除，
   不会动已经穿在身上的皮肤。
2. **确认页**——怎么穿。左侧 3D 预览，右侧切换形态，点「确认装备」才生效。
   进入时按历史里记下的形态初始化，用户当初怎么穿的，就先给回什么。

皮肤与披风各自独立记录（披风的贴图区域和皮肤完全不同，混在一起既没法预览也没法应用）。

**每个账户各记各的**：历史按账户（`AccountID`）分开存，两个离线账户互不可见。
皮肤是账户自己的属性，混在一起既没法辨认，点一条就等于把别人的皮肤换到自己身上。

- 每次应用都会记一条：皮肤库下载、手动选本地文件、从历史里再点一次都算；
- 同一账户下同一件材质重复应用只会**提到最前**，不会重复出现；
- 记录的是**本地材质文件路径**而不是皮肤库 id：再应用时直接读文件，不联网、
  不重新下载，皮肤站慢的时候这一点很重要；
- 每条同时记下当时的**手臂模型**（史蒂夫/艾利克斯）或**披风形态**（普通/鞘翅）；
- 材质文件被删掉的条目会自动从列表里消失，不会留下点不动的死条目；
- 每个账户的每个分区最多保留 60 条，超出后淘汰最旧的。

记录存在插件数据目录：`.hmcl/plugins/data/org.dsh.account-skin/history.json`。
1.0 版留下的、没有账户标记的老记录会归到一个单独的“旧记录”名下而不是被丢弃。

## 自定义皮肤/披风的预览

选择「自定义皮肤（选择本地文件）」或「自定义披风（选择本地文件）」后，
左侧会立刻按选中的文件渲染，**在真正上传之前**就能看到效果：

- 皮肤：可以先用形态按钮切到艾利克斯/史蒂夫，看两种手臂模型下的样子，再决定应用；
- 披风：可以切普通形态/鞘翅形态，看披风穿在背上和当作鞘翅展开的样子。

只有点了「应用」才会真正上传/生效，取消不会有任何改动。

## 使用

把 `dist/org.dsh.account-skin_1.1.0.jar` 放入 `.hmcl\plugins\`，重启启动器。
账户列表里每个账户行会多出两个按钮：

- 🎨 **切换皮肤** → 选择“在线皮肤（LittleSkin 皮肤库）”或“自定义皮肤（本地文件）”；离线账户额外有“跟随 LittleSkin 皮肤源”；
- 🚪 **切换披风** → 同上；微软账户改为“选择已拥有的官方披风”。

## 实现说明

- 账户行按钮通过启动器的 `PluginMenuService.registerAccountAction` 扩展点注册（`onEnable` 注册，`onDisable` 注销）；
- 微软：`MicrosoftAccount.uploadSkin`（Mojang `POST /minecraft/profile/skins`）+ 披风 `GET/PUT /minecraft/profile/capes`；
- 皮肤站：`YggdrasilAccount.uploadSkin`（authlib-injector 材质上传接口），披风为同构的 `/cape` 端点；
- 离线：`OfflineAccount.setSkin`（`Skin.Type.LOCAL_FILE` / `LITTLE_SKIN`）；
- 皮肤库：`GET https://littleskin.cn/skinlib/list?filter={skin|cape}`（Laravel 简单分页，按 `next_page_url`/`prev_page_url` 翻页），列表项带材质缩略图（`/skinlib/info/{tid}` 取 hash → `/textures/{hash}`）；
- 历史记录：`SkinHistory` 存在 `getContext().getDataFile("history.json")`，先写 `.tmp` 再替换，中途退出不会留下半截 JSON；读取时跳过文件已不存在的条目并顺手清理。

## 测试

`tools/` 下是可独立运行的自检程序（编译到 `out-test`，用 `java -cp` 直接跑）：

| 测试 | 覆盖内容 |
| --- | --- |
| `HistoryTest` | 去重、账户隔离、分区隔离、文件消失后剔除、上限淘汰、相对时间文案、模型/形态记忆、删除 |
| `DialogSizeTest` | 弹窗尺寸不随状态文字长度/列表空满变化 |
| `MinimumWindowTest` | 最小窗口（492 px 高）下各弹窗仍完整可见 |
| `StatusWidthTest` | 状态标签固定宽度，不会把对话框撑大 |
| `LocalSearchTest` | 本地搜索的匹配、去重、分页、错误文案 |
| `AccountSupportTest` | 账户类型判据与原生按钮的隐藏/恢复 |
| `ActionRowTest` | 账户行按钮的布局 |
| `SelectionTest` | 曾使用过网格：卡片数、删除单张、点选回调、账户隔离 |

## 构建

```powershell
.\build.ps1 -HMCLJar <HMCL-3.16.4.jar 路径>
```

需要 JDK 17+；JavaFX 依赖会自动从 Gradle 缓存发现（或用 `-JavaFXDir` 指定）。
---

## 说明：本项目为 Vibe Coding 产物

**本项目的全部代码均由 AI（大语言模型）生成**，通过对话式编程（vibe coding）完成——
人类只提出需求、给出反馈与验收，不手写实现代码。

- 代码可能包含未经验证的边界情况、不一致的风格，或与常规工程实践不同的设计取舍
- 请在生产环境使用前自行审查、测试与评估
- 欢迎提交 issue 或 PR 改进
