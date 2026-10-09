# 仓库标签方案（标签完全取代分组）+ 标签备份设计

状态：**待评审，未实现**。
决策记录（2026-10-09，用户定）：

1. **只保留标签一个概念**，分组连同组头 UI 全部去掉。**不做「按主标签分桶」**——理由已经给得很明确：分组当前的价值就是「先看到几块，再点进去看」，而标签 + 筛选是**一次操作直接得到目标子集、整屏铺开后过滤**，层级更浅。分桶属于把刚扔掉的缺点重新发明一遍。
2. 实现**从本地 `main`（7fdc865）新开分支**，不基于 `feature/repo-list-sort`。那条分支保留作参考（已提交、不 merge、不 push），本方案里凡是它已经验证过的东西都标了「可平移」。
3. 本版方案已按决策 1 删掉上一稿的桶状视图；筛选的默认匹配方式**改成了 AND**，见第 7 节的说明（这一条我反转了自己先前的倾向，理由写在那里，可以驳回）。

## 4. 数据模型（DB 到 v4）

新分支从 main 起，`RepoDbHelper` 是 `DATABASE_VERSION = 2`（main :18）。**分两步升，不要合并成一步 v3**：

- **v3 = `repo.time_added INTEGER`**（入库时间）。这一号已经被 `feature/repo-list-sort` 的构建占用过：本机 emulator-5554 上的 `repo.db` 现在就是 v3、带 `time_added` 列。若新分支把「标签建表」也编成 v3，`onUpgrade` 会因 `oldVersion` 已是 3 而**静默跳过建表**，SQLiteDatabaseOpenHelper 只在版本号为 0 时调 `onCreate` —— 现象就是崩溃在 `no such table: tag`。两步走对 v2 干净库和 v3 残留库都正确。
- **v4 = `tag` + `repo_tag` 两张表 + 分组→标签的数据迁移**。

```sql
CREATE TABLE tag (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,          -- trim 后唯一，大小写敏感
    sort_order INTEGER DEFAULT 0        -- 创建顺序；chips 与筛选面板共用同一顺序
);
CREATE TABLE repo_tag (
    repo_id INTEGER NOT NULL,
    tag_id INTEGER NOT NULL,
    PRIMARY KEY (repo_id, tag_id)
);
CREATE INDEX idx_repo_tag_tag ON repo_tag(tag_id);   -- 按标签筛选 / 计数
CREATE INDEX idx_repo_tag_repo ON repo_tag(repo_id); -- 删仓库、渲染 chips
```

**为什么不是 repo 行上一个 CSV 列**：本项目有先例（`credentials.rel_repo` 就是逗号拼的 TEXT），CSV 确实更省，但「重命名一个标签」要改写所有仓库行、「筛选面板显示每个标签的命中数」要先把全表读进内存再切字符串 —— 而这两个都是标签功能的核心动作。真要那么做，还是得再加一张标签注册表，CSV 就成了纯冗余。

**迁移 SQL（v4 内执行，分组平移成同名标签）**：

```sql
INSERT INTO tag(name, sort_order) SELECT name, MIN(sort_order) FROM repo_group GROUP BY name;

INSERT INTO repo_tag(repo_id, tag_id)
  SELECT r._id, t._id
    FROM repo r
    JOIN repo_group g ON g._id = r.group_id
    JOIN tag t ON t.name = g.name;
```

- `repo_group.name` 无 UNIQUE，`createGroup`（main :115）也不查重 ⇒ 真实库里可能有同名分组，上面的 `GROUP BY name` 就是为此存在。
- 名字比较是 BINARY：`"Work"` 与 `"work"` 迁成两个标签。**建议不归一**（用户既然建了两个就当他们有区别），但要在注释里写明，否则以后一定有人当 bug 提。
- `group_id` NULL 或指向已删分组的行插不进去 ⇒ 天然成为「无标签」，无需特判。
- **`repo_group` 表和 `repo.group_id` 列都保留、不再读写**。`DROP COLUMN` 要 SQLite ≥ 3.35（实测 pixel6 模拟器 `sqlite3 --version` = 3.39.2 支持），但设备 SQLite 随 ROM 变，为删一死列做表重建（CREATE 新表 + INSERT SELECT + DROP + RENAME）风险不抵收益。留一列整数没有代价。
- 分组专用的 `RepoDbManager.queryReposByGroup`（main :157）、`moveGroup`、`setRepoGroup` 一并删掉，不给它们留兼容层。

## 5. 相对 feature 分支：什么平移、什么重写、什么丢弃

| | 内容 | 说明 |
|---|---|---|
| **可平移**（已实测过，直接搬） | DB v3 迁移块 + `RepoContract` 的 `COLUMN_NAME_TIME_ADDED`/`getTimeAdded` + `Repo.mTimeAdded`（含 `writeObject`/`readObject` 两处）+ `createRepo` 单点写时间戳 + `SORT_KEY_*`/`SORT_DIR_*` 偏好读写 + `dialog_repo_sort.xml`（删掉分组顺序那一段）+ 排序/方向双语文案 + `activity_main.xml` 的 `ListView` 宽度 `wrap_content→match_parent` | 这些与分组无关，验证结论仍有效 |
| **要重写** | `requery()`：main 版是 `switch (mSortMode)`（main :153）+ `buildGroupedList`。AGP 9 下 **R 字段非 final，禁止 `case R.xxx`/`switch(getId())`**，改成两个独立比较器（键 + 方向）再 `Collections.sort` | 平铺后排序作用域就是全表，不再有「组内/组间」两层 |
| **丢弃** | `ListItem` 包装类与 `TYPE_GROUP`/双 viewType、`getGroupView`、`mExpandedGroups`、`repo_collapsed_groups` 偏好、`pref_key_repo_group_order`、`moveGroup`、组头长按菜单、`repo_listitem_group.xml` 与三个组头 dimens、`group_count_empty` 等文案 | 没有组头了，`ArrayAdapter<ListItem>` 可退回 `ArrayAdapter<Repo>`，**adapter 会比 main 版更小** |

顺带被这批决策消解掉的历史难题：搜索态要不要平铺（永远是平铺了）、未分组虚拟头的哨兵 id、折叠状态持久化 —— 都不存在了。

## 6. 数据访问层（`RepoDbManager` 新增）

```
queryAllTags()                          // ORDER BY sort_order ASC, name ASC，带每标签命中数
createTagIfAbsent(String name) -> long  // trim + UNIQUE 冲突即回查 id
renameTag(long tagId, String name)      // 一条 UPDATE；撞名返回 false 由 UI toast
deleteTag(long tagId)                   // 先删 repo_tag 再删 tag（照搬 deleteGroup 两步写法 main :134）
setRepoTags(long repoId, List<String> tagNames)   // 全量覆盖：删旧关系 + INSERT OR IGNORE
deleteRepoTags(long repoId)             // 必须挂在 _deleteRepo 里，见下
queryRepoTagMap()                       // 一条 JOIN 出 Map<Long, List<String>>，避免 N+1
```

`_deleteRepo`（main 只有 `mWritableDB.delete(repo)` 一行）**必须补上 `repo_tag` 清理**，否则 join 表会积累孤儿行；`repo_tag` 没有外键约束（本项目不依赖 FK 强制），只能代码层保证。

刷新不用额外接线：现有 `notifyObservers` 对分组变更也一律广播 `RepoEntry.TABLE_NAME`（main :122 等），标签变更沿用同一 key，列表的 `requery()` 自动触发。

## 7. 模型层与视图层

`Repo`（main :91 Cursor 构造、:386 `writeObject`、:402 `readObject`）加 `List<String> mTagNames`。两个坑：自定义序列化 **两条钩子都要改**，漏一侧是编译期不报的字段错位；标签在另一张表，Cursor 构造拿不到 ⇒ 构造时置空列表、由 adapter 用 `queryRepoTagMap()` 批量填充，`getTags()` 返回空列表而不是 null。

UI 触点（都是单屏，不再有下钻）：

| 位置 | 改动 |
|---|---|
| `repo_listitem.xml` | 末尾加一行 chips：`HorizontalScrollView` > `LinearLayout`，单行不换行，溢出用 `+n` 计数，保证行高可预测。chip 底色由 `name.hashCode()` 取确定性色相，**不加 color 列**（代价：用户不能自定义颜色） |
| 长按仓库 | main :538 附近的「Move to group」→「Edit tags」：多选 checkbox 列表（`list_item_rel_repo_checkbox.xml` 样式可复用）+ 内联新建输入框 |
| overflow `action_groups`（main :162） | 改成「Manage tags」：列出标签 + 命中数，点进去 Rename / Delete。第一版**不做上移下移**，`sort_order` = 创建顺序已经给了稳定且合理的展示序 |
| overflow 新增 | 「Filter by tags」多选面板（每项带命中数 + 「无标签」这一项）+「清除筛选」；`action_sort`（main :159）沿用 feature 分支的 radio 对话框，但只剩「排序键 + 方向」两段 |
| 工具栏下方 | 激活筛选的 chip 条，点 chip 直接移除；无筛选时整条不占高度 |

**筛选 × 排序语义**（写进注释，别留给下一个人猜）：

- 默认 **AND（全部满足）**。这里我反转了上一稿的倾向，理由变了：桶状视图被砍掉后，标签唯一职责就是「收窄」，多选第二个标签应当让结果更少而不是更多 —— 这与你说的「精准过滤」一致。反例场景（「work 或 oss 的都想看」）靠面板里一个「任一满足 / 全部满足」开关覆盖，选择记进偏好。
- 叠加顺序：**先筛选裁剪集合 → 再按排序键排全表**，没有第二层桶顺序，`group_order` 那套偏好随之消失。
- 「无标签」是一个可筛选项，不是虚拟分组头 —— 它是 `NOT EXISTS` 条件，选中它时与其他标签在 AND 下互斥（一个仓库不可能既带标签又无标签），这种组合下结果集为空，UI 要给空结果提示而不是白屏。

## 8. 标签备份（`BackupManager`，version 1 → 2）

现状（实测代码，`BackupManager` 没被 feature 分支动过，锚点对 main 也成立）：导出只写 9 个字段（:102-111），`sort_order`/`time_added` 全丢；导入调 `createRepo`（:179）⇒ 还原行的入库时间变成还原当天；偏好走 `sp.getAll()` 全量搬运（:147-150），所以排序/匹配方式这类设置**自动进备份，不用额外写**。

```json
{
  "version": 2,
  "export_time": 1760000000000,
  "tags":   [{"name": "work", "sort_order": 0}, {"name": "oss", "sort_order": 1}],
  "repos":  [{"local_path": "...", "...原有 9 个字段...": "",
              "sort_order": 3, "time_added": 1730000000000, "tags": ["work", "active"]}],
  "credentials": [...],
  "preferences": {...}
}
```

要点：

1. **身份用 name，不用 id**。跨设备 `_id` 必然不同。`tags` 数组是注册表（带 `sort_order`），每个 repo 只列自己标签的**名字**；导入用 `createTagIfAbsent` 解析成本地 id。名字比较用与库一致的 trim + 大小写敏感，别在导入路径上偷偷做归一。
2. **顺带修掉现存丢失**：`time_added` 与 `sort_order` 进导出。`group_id` 不再导出（分组概念已废）。
3. **「仓库已存在」不能整条跳过**。当前 :172-176 命中同 `local_path` 就 `SKIPPED`，标签会跟着丢。改成：已存在 ⇒ 仍然合并标签与 `time_added`（`INSERT OR IGNORE`，幂等、不重复插行），行状态报 `SKIPPED`、理由 `"exists, tags merged"`；新建 ⇒ `createRepo` 后 `setRepoTags`。
4. **还原必须显式覆盖 `time_added`**。v3 之后 `createRepo` 固定写 `System.currentTimeMillis()`，导入完要 `updateRepo` 回写备份值，否则「按导入时间排序」在还原后完全失真。这是「唯一写入点」设计的必然例外。
5. **兼容性**：v1 文件没有 `tags`/`time_added`，现有 `optJSONArray`/`optLong` 空值路径已覆盖 ⇒ 直接可用（标签为空、时间取还原当天）。v2 文件被旧版 app 打开时未知键被 `opt*` 忽略 ⇒ 不崩，只丢标签。读侧不需要版本分支，但要加 `version >= 2` 注释说明为什么不需要。
6. **不进备份的：当前激活的筛选**。只存内存。因为 `sp.getAll()` 会把 prefs 全量搬进备份，把筛选写进 prefs 等于间接备份了它，还原后面对一个过滤空列表是很糟的体验。反面：排序键/方向/匹配方式**该**持久化并因此自动进备份。
7. **结果上报**：`BackupResult` 加 tag 的成功/合并计数并进 `getSummary()`（:60），`SettingsBackupActivity` :142-144 的 toast 文案 + `values/` 与 `values-zh-rCN/` 两处同步（本项目约定：可见文案必须双语，`*_values` 结尾的存储键数组不翻译）。

## 9. 落地顺序（每步都能独立编译 + 装机验证）

1. `feat(db)`: v3 `time_added` + v4 `tag`/`repo_tag` + 分组→标签迁移 + tag API + `_deleteRepo` 清孤儿。
2. `feat(list)`: 去分组化（`ListItem`/组头/展开态全删，退回 `ArrayAdapter<Repo>`）+ 排序键×方向 + 偏好持久化 + radio 对话框（平移 feature 分支已验证的那部分）。
3. `feat(ui)`: item 底部 chips 行 + 「Edit tags」+「Manage tags」。
4. `feat(filter)`: 「Filter by tags」面板 + AND/OR + 顶部激活 chip 条 + 空结果提示。
5. `feat(backup)`: version 2 导出/导入（含第 8 节 1-7 全部要点）。

## 10. 未验证 / 风险

- 平铺 + 筛选在 30+ 仓库下的可扫读性没实测过；分组之所以存在，可能就是为了「不靠筛选也能定位」。真机上跑一周真实数据再确认默认视图是否要提供「按标签字母序把带同一标签的仓库聚拢」这类辅助（本方案刻意没做，与决策 1 一致）。
- 迁移后的同名/大小写行为要造数据实测（灌库路径见 `.zcode/skills/mgit-emulator`：`exec-out run-as` 取、shell 管道喂，且要在首次启动 app 之前写入）。
- chip 色相来自 `hashCode`，深浅色主题下的前景对比度未验证，可能要按亮度切文字色。
- 标签数量失控（把标签当分组用，建出 40 个只含一个仓库的标签）。第一版不设上限，靠「Edit tags」里优先展示已有标签 + 面板按创建序排列来抑制，实际效果待观察。
- v3 号已被 emulator 上的库占用这一事实，只在本机成立；若将来 `feature/repo-list-sort` 被合并或装机到别处，v3/v4 的分工需要重新确认。
