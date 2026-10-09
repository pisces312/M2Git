package ts.realms.m2git.local.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteConstraintException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import timber.log.Timber;
import ts.realms.m2git.utils.BasicFunctions;

/**
 * Manage entries in the persisted database tracking local repo metadata.
 * 管理持久化数据库中 跟踪本地仓库元数据 的条目，以及仓库关联的凭证。
 */
public class RepoDbManager {

    private static final Map<String, Set<RepoDbObserver>> mObservers = new HashMap<>();
    private static RepoDbManager mInstance;
    private final SQLiteDatabase mWritableDB;
    private final SQLiteDatabase mReadableDB;


    private RepoDbManager(Context context) {
        RepoDbHelper mDbHelper = new RepoDbHelper(context);
        mWritableDB = mDbHelper.getWritableDatabase();
        mReadableDB = mDbHelper.getReadableDatabase();
    }

    private static RepoDbManager getInstance() {
        if (mInstance == null) {
            mInstance = new RepoDbManager(BasicFunctions.getActiveActivity());
        }
        return mInstance;
    }

    public static void registerDbObserver(String table, RepoDbObserver observer) {
        Set<RepoDbObserver> set = mObservers.computeIfAbsent(table, k -> new HashSet<>());
        set.add(observer);
    }

    public static void unregisterDbObserver(String table, RepoDbObserver observer) {
        Set<RepoDbObserver> set = mObservers.get(table);
        if (set == null) return;
        set.remove(observer);
    }

    public static void notifyObservers(String table) {
        Set<RepoDbObserver> set = mObservers.get(table);
        if (set == null) return;
        for (RepoDbObserver observer : set) {
            observer.notifyChanged();
        }
    }

    public static void persistCredentials(long repoId, String username, String password) {
        ContentValues values = new ContentValues();
        if (username != null && password != null) {
            values.put(RepoContract.RepoEntry.COLUMN_NAME_USERNAME, username);
            values.put(RepoContract.RepoEntry.COLUMN_NAME_PASSWORD, password);
            relateRepoWithCredential(createCredential(username, password), String.valueOf(repoId));
        } else {
            values.put(RepoContract.RepoEntry.COLUMN_NAME_USERNAME, "");
            values.put(RepoContract.RepoEntry.COLUMN_NAME_PASSWORD, "");
        }
        updateRepo(repoId, values);
    }

    public static Cursor searchRepo(String query) {
        return getInstance()._searchRepo(query);
    }

    public static Cursor queryAllRepo() {
        return getInstance()._queryAllRepo();
    }

    public static Cursor getRepoById(long id) {
        return getInstance()._getRepoById(id);
    }

    public static long importRepo(String localPath, String status) {
        return createRepo(localPath, "", status);
    }

    public static void setLocalPath(long repoId, String path) {
        ContentValues values = new ContentValues();
        values.put(RepoContract.RepoEntry.COLUMN_NAME_LOCAL_PATH, path);
        updateRepo(repoId, values);
    }

    // ---- Tag CRUD（标签取代分组，设计见 docs/repo-tags-design.md）----

    /** 全部标签，按展示顺序（sort_order 即创建序）再按名字，附带每个标签的命中仓库数。 */
    public static Cursor queryAllTags() {
        String tag = RepoContract.TagEntry.TABLE_NAME;
        String repoTag = RepoContract.RepoTagEntry.TABLE_NAME;
        String sql = "SELECT t." + RepoContract.TagEntry._ID + " AS " + RepoContract.TagEntry._ID
            + ", t." + RepoContract.TagEntry.COLUMN_NAME + " AS " + RepoContract.TagEntry.COLUMN_NAME
            + ", t." + RepoContract.TagEntry.COLUMN_SORT_ORDER + " AS "
            + RepoContract.TagEntry.COLUMN_SORT_ORDER
            + ", (SELECT COUNT(*) FROM " + repoTag + " rt WHERE rt."
            + RepoContract.RepoTagEntry.COLUMN_TAG_ID + " = t." + RepoContract.TagEntry._ID
            + ") AS " + RepoContract.TagEntry.COLUMN_REPO_COUNT
            + " FROM " + tag + " t ORDER BY t." + RepoContract.TagEntry.COLUMN_SORT_ORDER
            + " ASC, t." + RepoContract.TagEntry.COLUMN_NAME + " ASC";
        return getInstance().mReadableDB.rawQuery(sql, null);
    }

    /**
     * repo_id -&gt; 标签名列表（按标签展示顺序）。一次 JOIN 取全，避免每个仓库一条查询。
     */
    public static Map<Long, List<String>> queryRepoTagMap() {
        Map<Long, List<String>> map = new HashMap<>();
        Cursor cursor = getInstance().mReadableDB.rawQuery(
            "SELECT rt." + RepoContract.RepoTagEntry.COLUMN_REPO_ID + ", t."
                + RepoContract.TagEntry.COLUMN_NAME
                + " FROM " + RepoContract.RepoTagEntry.TABLE_NAME + " rt JOIN "
                + RepoContract.TagEntry.TABLE_NAME + " t ON t." + RepoContract.TagEntry._ID
                + " = rt." + RepoContract.RepoTagEntry.COLUMN_TAG_ID
                + " ORDER BY t." + RepoContract.TagEntry.COLUMN_SORT_ORDER + " ASC, t."
                + RepoContract.TagEntry.COLUMN_NAME + " ASC", null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                while (!cursor.isAfterLast()) {
                    long repoId = cursor.getLong(0);
                    List<String> names = map.get(repoId);
                    if (names == null) {
                        names = new ArrayList<>();
                        map.put(repoId, names);
                    }
                    names.add(cursor.getString(1));
                    cursor.moveToNext();
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return map;
    }

    /** 一个标签都没挂的仓库数，给筛选面板里的「无标签」选项用。 */
    public static int countReposWithoutTags() {
        Cursor cursor = getInstance().mReadableDB.rawQuery(
            "SELECT COUNT(*) FROM " + RepoContract.RepoEntry.TABLE_NAME + " r WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + RepoContract.RepoTagEntry.TABLE_NAME + " rt WHERE rt."
                + RepoContract.RepoTagEntry.COLUMN_REPO_ID + " = r." + RepoContract.RepoEntry._ID
                + ")", null);
        int count = 0;
        try {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                count = cursor.getInt(0);
            }
        } finally {
            cursor.close();
        }
        return count;
    }

    /** 名字 trim 后复用已有标签，不存在则新建。返回 tag_id，空名字返回 -1。 */
    public static long createTagIfAbsent(String rawName) {
        long id = findOrCreateTagId(getInstance().mWritableDB, rawName);
        if (id >= 0) {
            notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
        }
        return id;
    }

    /** 重命名；目标名已被占用（UNIQUE）时返回 false，由 UI 提示。 */
    public static boolean renameTag(long tagId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return false;
        try {
            ContentValues values = new ContentValues();
            values.put(RepoContract.TagEntry.COLUMN_NAME, name);
            getInstance().mWritableDB.update(RepoContract.TagEntry.TABLE_NAME, values,
                RepoContract.TagEntry._ID + " = ?", new String[]{String.valueOf(tagId)});
        } catch (SQLiteConstraintException e) {
            Timber.d(e, "rename tag %d -> %s rejected: name in use", tagId, name);
            return false;
        }
        notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
        return true;
    }

    /** 删标签同时清掉它与仓库的关系（表间无外键强制，只能代码层保证）。 */
    public static void deleteTag(long tagId) {
        String[] args = {String.valueOf(tagId)};
        SQLiteDatabase db = getInstance().mWritableDB;
        db.delete(RepoContract.RepoTagEntry.TABLE_NAME,
            RepoContract.RepoTagEntry.COLUMN_TAG_ID + " = ?", args);
        db.delete(RepoContract.TagEntry.TABLE_NAME, RepoContract.TagEntry._ID + " = ?", args);
        notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
    }

    /** 全量覆盖一个仓库的标签：清旧关系，再按名字复用/新建。 */
    public static void setRepoTags(long repoId, List<String> tagNames) {
        SQLiteDatabase db = getInstance().mWritableDB;
        db.beginTransaction();
        try {
            db.delete(RepoContract.RepoTagEntry.TABLE_NAME,
                RepoContract.RepoTagEntry.COLUMN_REPO_ID + " = ?",
                new String[]{String.valueOf(repoId)});
            ContentValues values = new ContentValues();
            values.put(RepoContract.RepoTagEntry.COLUMN_REPO_ID, repoId);
            if (tagNames != null) {
                for (String name : tagNames) {
                    // 事务内不 notify：否则中途的 requery 会读到未提交的标签集
                    long tagId = findOrCreateTagId(db, name);
                    if (tagId < 0) continue;
                    values.put(RepoContract.RepoTagEntry.COLUMN_TAG_ID, tagId);
                    db.insertWithOnConflict(RepoContract.RepoTagEntry.TABLE_NAME, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
        }
    }

    /** 删仓库时清理关系行，避免 repo_tag 积累孤儿。 */
    public static void deleteRepoTags(long repoId) {
        getInstance().mWritableDB.delete(RepoContract.RepoTagEntry.TABLE_NAME,
            RepoContract.RepoTagEntry.COLUMN_REPO_ID + " = ?",
            new String[]{String.valueOf(repoId)});
    }

    /** 用给定的 db 句柄（事务内必须用同一个句柄读），不做 observer 通知。 */
    private static long findOrCreateTagId(SQLiteDatabase db, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return -1;
        long existing = findTagId(db, name);
        if (existing >= 0) return existing;
        ContentValues values = new ContentValues();
        values.put(RepoContract.TagEntry.COLUMN_NAME, name);
        values.put(RepoContract.TagEntry.COLUMN_SORT_ORDER, getNextTagSortOrder(db));
        try {
            return db.insertOrThrow(RepoContract.TagEntry.TABLE_NAME, null, values);
        } catch (SQLiteConstraintException e) {
            // UNIQUE 竞争（同名并发创建）：回查已有 id
            return findTagId(db, name);
        }
    }

    private static long findTagId(SQLiteDatabase db, String name) {
        Cursor cursor = db.query(RepoContract.TagEntry.TABLE_NAME,
            new String[]{RepoContract.TagEntry._ID},
            RepoContract.TagEntry.COLUMN_NAME + " = ?", new String[]{name}, null, null, null);
        long id = -1;
        try {
            if (cursor.moveToFirst()) id = cursor.getLong(0);
        } finally {
            cursor.close();
        }
        return id;
    }

    private static int getNextTagSortOrder(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery(
            "SELECT MAX(" + RepoContract.TagEntry.COLUMN_SORT_ORDER + ") FROM "
                + RepoContract.TagEntry.TABLE_NAME, null);
        int max = 0;
        if (cursor.moveToFirst() && !cursor.isNull(0)) {
            max = cursor.getInt(0);
        }
        cursor.close();
        return max + 1;
    }

    // ---- Credential CRUD ----

    public static long createRepo(String localPath, String remoteURL, String status) {
        ContentValues values = new ContentValues();
        values.put(RepoContract.RepoEntry.COLUMN_NAME_LOCAL_PATH, localPath);
        values.put(RepoContract.RepoEntry.COLUMN_NAME_REMOTE_URL, remoteURL);
        values.put(RepoContract.RepoEntry.COLUMN_NAME_REPO_STATUS, status);
        // 入库时间：克隆/导入/新建都走这里，是唯一写入点。
        // 唯一例外是备份导入，它必须把 time_added 覆盖回备份时的值。
        values.put(RepoContract.RepoEntry.COLUMN_NAME_TIME_ADDED, System.currentTimeMillis());
        long id = getInstance().mWritableDB.insert(RepoContract.RepoEntry.TABLE_NAME, null, values);
        notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
        return id;
    }

    public static void updateRepo(long id, ContentValues values) {
        String whereClause = RepoContract.RepoEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        getInstance().mWritableDB.update(RepoContract.RepoEntry.TABLE_NAME, values, whereClause,
            whereArgs);
        notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
    }

    public static void deleteRepo(long id) {
        getInstance()._deleteRepo(id);
    }

    public static long createCredential(String token_account, String token_secret) {
        long credentialId = -1;
        try (Cursor cursor = getInstance()._queryAllCredential()) {
            if (cursor != null) {
                cursor.moveToFirst();
                while (!cursor.isAfterLast()) {
                    int columnTokenAccountIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_TOKEN_ACCOUNT);
                    int columnCredentialIdIndex = cursor.getColumnIndex(RepoContract.RepoCredential._ID);
                    String curr_token_account = cursor.getString(columnTokenAccountIndex);
                    if (token_account.equals(curr_token_account)) {
                        credentialId = cursor.getInt(columnCredentialIdIndex);
                        break;
                    }
                    cursor.moveToNext();
                }
            }
            if (credentialId == -1) {
                ContentValues values = new ContentValues();
                values.put(RepoContract.RepoCredential.COLUMN_TOKEN_ACCOUNT, token_account);
                values.put(RepoContract.RepoCredential.COLUMN_TOKEN_SECRET, token_secret);
                credentialId = getInstance()
                    .mWritableDB
                    .insert(RepoContract.RepoCredential.TABLE_NAME, null, values);
            }
        } catch (Exception e) {
            e.fillInStackTrace();
        }
        return credentialId;
    }

    public static void relateRepoWithCredential(long credentialId, String repo) {
        Cursor credential = getCredentialById(credentialId);
        if (credential == null || !credential.moveToFirst()) {
            return;
        }
        int columnIndex = credential.getColumnIndex(RepoContract.RepoCredential.COLUMN_REL_REPO);
        String relRepoString = credential.getString(columnIndex);
        String new_rel;
        ContentValues values = new ContentValues();
        if (relRepoString == null || relRepoString.isEmpty()) {
            new_rel = repo;
        } else {
            HashSet<String> rel_list = new HashSet<>(Arrays.asList(relRepoString.split(",")));
            rel_list.add(repo);
            new_rel = String.join(",", rel_list);
        }
        values.put(RepoContract.RepoCredential.COLUMN_REL_REPO, new_rel);
        updateCredential(credentialId, values);
    }

    public static void unrelateRepoWithCredential(long id, String repo) {
        Cursor credential = getCredentialById(id);
        if (credential == null || !credential.moveToFirst()) {
            return;
        }
        int columnIndex = credential.getColumnIndex(RepoContract.RepoCredential.COLUMN_REL_REPO);
        String relRepoString = credential.getString(columnIndex);
        if (relRepoString == null) return;
        HashSet<String> rel_list = new HashSet<>(Arrays.asList(relRepoString.split(",")));
        if (!rel_list.contains(repo)) return;
        rel_list.remove(repo);
        String new_rel = String.join(",", rel_list);
        ContentValues values = new ContentValues();
        values.put(RepoContract.RepoCredential.COLUMN_REL_REPO, new_rel);
        updateCredential(id, values);
    }

    public static void updateCredential(long id, ContentValues values) {
        String whereClause = RepoContract.RepoCredential._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        getInstance().mWritableDB.update(RepoContract.RepoCredential.TABLE_NAME, values,
            whereClause, whereArgs);
    }

    public static Cursor getCredentialById(long id) {
        return getInstance()._getCredentialById(id);
    }

    public static Cursor queryAllCredential() {
        return getInstance()._queryAllCredential();
    }

    public static void deleteCredential(long id) {
        getInstance()._deleteCredential(id);
    }

    public static Map<String, String> queryCredentialByRepoId(String repo_id) {
        try (Cursor cursor = RepoDbManager.queryAllCredential()) {
            Map<String, String> queryResult = Collections.emptyMap();
            if (cursor != null && cursor.moveToFirst()) {
                int rel_repoIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_REL_REPO);
                int secretIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_TOKEN_SECRET);
                int accountIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_TOKEN_ACCOUNT);
                do {
                    String rel_repo = cursor.getString(rel_repoIndex);
                    String[] rel_repo_list = rel_repo.split(",");
                    if (Arrays.asList(rel_repo_list).contains(repo_id)) {
                        queryResult = Map.of(
                            RepoContract.RepoCredential.COLUMN_TOKEN_SECRET, cursor.getString(secretIndex),
                            RepoContract.RepoCredential.COLUMN_TOKEN_ACCOUNT, cursor.getString(accountIndex)
                        );
                        break;
                    }

                } while (cursor.moveToNext());
            } else {
                Timber.tag("DB").d("No credentials found.");
            }
            return queryResult;
        }
    }

    private Cursor _searchRepo(String query) {
        String whereClause =
            RepoContract.RepoEntry.COLUMN_NAME_LOCAL_PATH
                + " LIKE ? OR " + RepoContract.RepoEntry.COLUMN_NAME_REMOTE_URL
                + " LIKE ? OR " + RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_UNAME
                + " LIKE ? OR " + RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_MSG
                + " LIKE ?";
        query = "%" + query + "%";
        String[] whereArgs = {query, query, query, query};
        return mReadableDB.query(true, RepoContract.RepoEntry.TABLE_NAME,
            RepoContract.RepoEntry.ALL_COLUMNS, whereClause, whereArgs, null, null, null, null);
    }

    private Cursor _queryAllRepo() {
        return mReadableDB.query(true, RepoContract.RepoEntry.TABLE_NAME,
            RepoContract.RepoEntry.ALL_COLUMNS, null, null, null, null, null, null);
    }

    private Cursor _getRepoById(long id) {
        String whereClause = RepoContract.RepoEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        Cursor cursor = mReadableDB.query(true, RepoContract.RepoEntry.TABLE_NAME,
            RepoContract.RepoEntry.ALL_COLUMNS, whereClause, whereArgs, null, null, null, null);
        if (cursor.getCount() < 1) {
            cursor.close();
            return null;
        }
        return cursor;
    }

    private void _deleteRepo(long id) {
        String whereClause = RepoContract.RepoEntry._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        // repo_tag 没有外键强制（本项目不开 foreign_keys pragma），关系行必须在这里显式清掉
        deleteRepoTags(id);
        mWritableDB.delete(RepoContract.RepoEntry.TABLE_NAME, whereClause, whereArgs);
        notifyObservers(RepoContract.RepoEntry.TABLE_NAME);
    }

    private Cursor _getCredentialById(long id) {
        String whereClause = RepoContract.RepoCredential._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        Cursor cursor = mReadableDB.query(true, RepoContract.RepoCredential.TABLE_NAME,
            RepoContract.RepoCredential.ALL_COLUMNS, whereClause, whereArgs, null, null, null,
            null);
        if (cursor.getCount() < 1) {
            cursor.close();
            return null;
        }
        return cursor;
    }

    private Cursor _queryAllCredential() {
        return mReadableDB.query(true, RepoContract.RepoCredential.TABLE_NAME,
            RepoContract.RepoCredential.ALL_COLUMNS, null, null, null, null, null, null);
    }

    private void _deleteCredential(long id) {
        String whereClause = RepoContract.RepoCredential._ID + " = ?";
        String[] whereArgs = {String.valueOf(id)};
        mWritableDB.delete(RepoContract.RepoCredential.TABLE_NAME, whereClause, whereArgs);
    }

    public interface RepoDbObserver {
        void notifyChanged();
    }

}
