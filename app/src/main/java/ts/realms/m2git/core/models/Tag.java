package ts.realms.m2git.core.models;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ts.realms.m2git.local.database.RepoContract;

/**
 * 仓库标签。取代原来的「分组」：一个仓库可以挂多个标签，列表用筛选而不是用桶。
 * 设计背景见 docs/repo-tags-design.md。
 */
public class Tag {

    /** 「无标签」在筛选里是一个伪标签，用这个 id 表示（真实 tag._id 从 1 起）。 */
    public static final int UNTAGGED_ID = 0;

    private final int mId;
    private final String mName;
    private final int mSortOrder;
    private final int mRepoCount;

    public Tag(int id, String name, int sortOrder, int repoCount) {
        mId = id;
        mName = name;
        mSortOrder = sortOrder;
        mRepoCount = repoCount;
    }

    public Tag(Cursor cursor) {
        mId = cursor.getInt(cursor.getColumnIndexOrThrow(RepoContract.TagEntry._ID));
        mName = cursor.getString(cursor.getColumnIndexOrThrow(RepoContract.TagEntry.COLUMN_NAME));
        int orderIdx = cursor.getColumnIndex(RepoContract.TagEntry.COLUMN_SORT_ORDER);
        mSortOrder = orderIdx >= 0 && !cursor.isNull(orderIdx) ? cursor.getInt(orderIdx) : 0;
        int countIdx = cursor.getColumnIndex(RepoContract.TagEntry.COLUMN_REPO_COUNT);
        mRepoCount = countIdx >= 0 && !cursor.isNull(countIdx) ? cursor.getInt(countIdx) : 0;
    }

    public static List<Tag> getTagList(Cursor cursor) {
        List<Tag> tags = new ArrayList<>();
        if (cursor == null) return tags;
        try {
            if (cursor.moveToFirst()) {
                while (!cursor.isAfterLast()) {
                    tags.add(new Tag(cursor));
                    cursor.moveToNext();
                }
            }
        } finally {
            cursor.close();
        }
        return tags;
    }

    public int getId() {
        return mId;
    }

    public String getName() {
        return mName;
    }

    public int getSortOrder() {
        return mSortOrder;
    }

    /** 挂了该标签的仓库数，来自 queryAllTags 的子查询。 */
    public int getRepoCount() {
        return mRepoCount;
    }

    public boolean isUntagged() {
        return mId == UNTAGGED_ID;
    }

    /**
     * 标签名 -&gt; id。只认注册表里存在的名字：库里没有的标签名不该凭空变成筛选条件。
     * 顺序跟随 registry，不跟随 names。
     */
    public static Set<Integer> idsOfNames(List<Tag> registry, Collection<String> names) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (registry == null || names == null) return ids;
        for (Tag tag : registry) {
            if (names.contains(tag.getName())) {
                ids.add(tag.getId());
            }
        }
        return ids;
    }

    /**
     * id -&gt; 标签名。registry 要现查，不能翻对话框打开时的快照 ——
     * 面板里就地新建的标签那时还不存在，用快照会把它们丢掉。
     */
    public static List<String> namesOfIds(List<Tag> registry, Collection<Integer> ids) {
        List<String> names = new ArrayList<>();
        if (registry == null || ids == null) return names;
        for (Tag tag : registry) {
            if (ids.contains(tag.getId())) {
                names.add(tag.getName());
            }
        }
        return names;
    }
}
