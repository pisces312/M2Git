package ts.realms.m2git.local.database;

import android.database.Cursor;
import android.provider.BaseColumns;

import java.util.Date;

/**
 * Created by sheimi on 8/6/13.
 * 数据库常量类
 */
public final class RepoContract {

    public static final String REPO_STATUS_NULL = "";
    public static final String REPO_ENTRY_DROP = "DROP TABLE IF EXISTS " + RepoEntry.TABLE_NAME;
    public static final String REPO_CREDENTIALS_DROP = "DROP TABLE IF EXISTS " + RepoCredential.TABLE_NAME;
    public static final String REPO_GROUP_DROP = "DROP TABLE IF EXISTS " + RepoGroupEntry.TABLE_NAME;
    private static final String TEXT_TYPE = " TEXT ";
    private static final String INT_TYPE = " INTEGER ";
    private static final String PRIMARY_KEY_TYPE = INT_TYPE + "PRIMARY KEY " + "AUTOINCREMENT ";
    private static final String COMMA_SEP = ",";
    public static final String REPO_ENTRY_CREATE =
        "CREATE TABLE " + RepoEntry.TABLE_NAME + " ("
            + RepoEntry._ID + PRIMARY_KEY_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_LOCAL_PATH + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_REMOTE_URL + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_USERNAME + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_PASSWORD + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_REPO_STATUS + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_LATEST_COMMITTER_UNAME + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_LATEST_COMMITTER_EMAIL + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_LATEST_COMMIT_DATE + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_LATEST_COMMIT_MSG + TEXT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_GROUP_ID + INT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_SORT_ORDER + INT_TYPE + COMMA_SEP
            + RepoEntry.COLUMN_NAME_TIME_ADDED + INT_TYPE
            + " )";
    public static final String REPO_CREDENTIALS_CREATE =
        "CREATE TABLE " + RepoCredential.TABLE_NAME + " ("
            + RepoCredential._ID + PRIMARY_KEY_TYPE + COMMA_SEP
            + RepoCredential.COLUMN_TOKEN_ACCOUNT + TEXT_TYPE + COMMA_SEP
            + RepoCredential.COLUMN_TOKEN_SECRET + TEXT_TYPE + COMMA_SEP
            + RepoCredential.COLUMN_REL_REPO + TEXT_TYPE
            + ")";
    public static final String REPO_GROUP_CREATE =
        "CREATE TABLE " + RepoGroupEntry.TABLE_NAME + " ("
            + RepoGroupEntry._ID + PRIMARY_KEY_TYPE + COMMA_SEP
            + RepoGroupEntry.COLUMN_NAME + TEXT_TYPE + COMMA_SEP
            + RepoGroupEntry.COLUMN_SORT_ORDER + INT_TYPE
            + ")";

    // ===== v4：标签（多对多），分组概念的替代物 =====
    public static final String TAG_CREATE =
        "CREATE TABLE IF NOT EXISTS " + TagEntry.TABLE_NAME + " ("
            + TagEntry._ID + PRIMARY_KEY_TYPE + COMMA_SEP
            + TagEntry.COLUMN_NAME + TEXT_TYPE + "NOT NULL UNIQUE" + COMMA_SEP
            + TagEntry.COLUMN_SORT_ORDER + INT_TYPE + "DEFAULT 0"
            + ")";
    public static final String REPO_TAG_CREATE =
        "CREATE TABLE IF NOT EXISTS " + RepoTagEntry.TABLE_NAME + " ("
            + RepoTagEntry.COLUMN_REPO_ID + INT_TYPE + "NOT NULL" + COMMA_SEP
            + RepoTagEntry.COLUMN_TAG_ID + INT_TYPE + "NOT NULL" + COMMA_SEP
            + "PRIMARY KEY (" + RepoTagEntry.COLUMN_REPO_ID + "," + RepoTagEntry.COLUMN_TAG_ID + ")"
            + ")";
    public static final String REPO_TAG_INDEX_BY_TAG =
        "CREATE INDEX IF NOT EXISTS idx_repo_tag_tag ON " + RepoTagEntry.TABLE_NAME
            + "(" + RepoTagEntry.COLUMN_TAG_ID + ")";
    public static final String REPO_TAG_INDEX_BY_REPO =
        "CREATE INDEX IF NOT EXISTS idx_repo_tag_repo ON " + RepoTagEntry.TABLE_NAME
            + "(" + RepoTagEntry.COLUMN_REPO_ID + ")";
    /**
     * v4 迁移：把每个分组平移成同名标签（同名分组合并，sort_order 取最小值），再把
     * repo.group_id 的关系平移成 repo_tag。名字比较是 BINARY，所以 "Work" 与 "work"
     * 是两个标签 —— 这是刻意选择（用户建了两个就当他们有区别），不要"顺手修好"。
     */
    public static final String TAG_MIGRATE_FROM_GROUPS =
        "INSERT OR IGNORE INTO " + TagEntry.TABLE_NAME
            + "(" + TagEntry.COLUMN_NAME + "," + TagEntry.COLUMN_SORT_ORDER + ") "
            + "SELECT g." + RepoGroupEntry.COLUMN_NAME + ", MIN(g." + RepoGroupEntry.COLUMN_SORT_ORDER + ") "
            + "FROM " + RepoGroupEntry.TABLE_NAME + " g GROUP BY g." + RepoGroupEntry.COLUMN_NAME;
    public static final String REPO_TAG_MIGRATE_FROM_GROUPS =
        "INSERT OR IGNORE INTO " + RepoTagEntry.TABLE_NAME
            + "(" + RepoTagEntry.COLUMN_REPO_ID + "," + RepoTagEntry.COLUMN_TAG_ID + ") "
            + "SELECT r." + RepoEntry._ID + ", t." + TagEntry._ID + " "
            + "FROM " + RepoEntry.TABLE_NAME + " r "
            + "JOIN " + RepoGroupEntry.TABLE_NAME + " g ON g." + RepoGroupEntry._ID + " = r."
            + RepoEntry.COLUMN_NAME_GROUP_ID + " "
            + "JOIN " + TagEntry.TABLE_NAME + " t ON t." + TagEntry.COLUMN_NAME + " = g."
            + RepoGroupEntry.COLUMN_NAME;

    public RepoContract() {
    }

    public static int getRepoID(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry._ID);
        return cursor.getInt(columnIndex);
    }

    public static String getLocalPath(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_LOCAL_PATH);
        return cursor.getString(columnIndex);
    }

    public static String getRemoteURL(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_REMOTE_URL);
        return cursor.getString(columnIndex);
    }

    public static String getRepoStatus(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_REPO_STATUS);
        return cursor.getString(columnIndex);
    }

    public static String getLatestCommitterName(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_UNAME);
        return cursor.getString(columnIndex);
    }

    public static String getLatestCommitterEmail(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_EMAIL);
        return cursor.getString(columnIndex);
    }

    public static Date getLatestCommitDate(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_DATE);
        String longStr = cursor.getString(columnIndex);
        if (longStr == null || longStr.isEmpty()) {
            return null;
        }
        long time = Long.parseLong(longStr);
        return new Date(time);
    }

    public static String getLatestCommitMsg(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_MSG);
        return cursor.getString(columnIndex);
    }

    public static String getUsername(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_USERNAME);
        return cursor.getString(columnIndex);
    }

    public static String getPassword(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_PASSWORD);
        return cursor.getString(columnIndex);
    }

    /**
     * 仓库入库（克隆/导入/新建）时间。v3 之前的老数据为 NULL，返回 null，
     * 排序时恒定沉底 —— 不要用目录 lastModified 伪造。
     */
    public static Date getTimeAdded(Cursor cursor) {
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoEntry.COLUMN_NAME_TIME_ADDED);
        if (columnIndex < 0 || cursor.isNull(columnIndex)) {
            return null;
        }
        return new Date(cursor.getLong(columnIndex));
    }

    public static int getOneCredentialId(Cursor cursor) {
        if (cursor.isBeforeFirst()) {
            cursor.moveToFirst();
        }
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoCredential._ID);
        return cursor.getInt(columnIndex);
    }

    public static String getOneTokenAccount(Cursor cursor) {
        if (cursor.isBeforeFirst()) {
            cursor.moveToFirst();
        }
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_TOKEN_ACCOUNT);
        return cursor.getString(columnIndex);
    }

    public static String getOneTokenSecret(Cursor cursor) {
        if (cursor.isBeforeFirst()) {
            cursor.moveToFirst();
        }
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_TOKEN_SECRET);
        return cursor.getString(columnIndex);
    }

    public static String[] getOneRelReop(Cursor cursor) {
        if (cursor.isBeforeFirst()) {
            cursor.moveToFirst();
        }
        int columnIndex = cursor.getColumnIndex(RepoContract.RepoCredential.COLUMN_REL_REPO);
        String relRepoString = cursor.getString(columnIndex);
        if (relRepoString == null) return null;
        return relRepoString.split(",");
    }

    public static abstract class RepoEntry implements BaseColumns {
        public static final String TABLE_NAME = "repo";
        public static final String COLUMN_NAME_LOCAL_PATH = "local_path";
        public static final String COLUMN_NAME_REMOTE_URL = "remote_url";
        public static final String COLUMN_NAME_REPO_STATUS = "repo_status";
        public static final String COLUMN_NAME_USERNAME = "username";
        public static final String COLUMN_NAME_PASSWORD = "password";
        // latest commit's committer name
        public static final String COLUMN_NAME_LATEST_COMMITTER_UNAME = "latest_committer_uname";
        public static final String COLUMN_NAME_LATEST_COMMITTER_EMAIL = "latest_committer_email";
        public static final String COLUMN_NAME_LATEST_COMMIT_DATE = "latest_commit_date";
        public static final String COLUMN_NAME_LATEST_COMMIT_MSG = "latest_commit_msg";
        public static final String COLUMN_NAME_GROUP_ID = "group_id";
        public static final String COLUMN_NAME_SORT_ORDER = "sort_order";
        public static final String COLUMN_NAME_TIME_ADDED = "time_added";
        public static final String[] ALL_COLUMNS = {
            _ID,
            COLUMN_NAME_LOCAL_PATH,
            COLUMN_NAME_REMOTE_URL,
            COLUMN_NAME_USERNAME,
            COLUMN_NAME_PASSWORD,
            COLUMN_NAME_REPO_STATUS,
            COLUMN_NAME_LATEST_COMMITTER_UNAME,
            COLUMN_NAME_LATEST_COMMITTER_EMAIL,
            COLUMN_NAME_LATEST_COMMIT_DATE,
            COLUMN_NAME_LATEST_COMMIT_MSG,
            COLUMN_NAME_GROUP_ID,
            COLUMN_NAME_SORT_ORDER,
            COLUMN_NAME_TIME_ADDED,
        };
    }

    /**
     * 遗留表：分组已被标签取代（v4）。表与列保留是为了承载迁移数据，代码不再读写。
     */
    public static abstract class RepoGroupEntry implements BaseColumns {
        public static final String TABLE_NAME = "repo_group";
        public static final String COLUMN_NAME = "name";
        public static final String COLUMN_SORT_ORDER = "sort_order";
        public static final String[] ALL_COLUMNS = {
            _ID,
            COLUMN_NAME,
            COLUMN_SORT_ORDER,
        };
    }

    public static abstract class TagEntry implements BaseColumns {
        public static final String TABLE_NAME = "tag";
        public static final String COLUMN_NAME = "name";
        public static final String COLUMN_SORT_ORDER = "sort_order";
        /** queryAllTags() 里子查询出来的每标签命中仓库数 */
        public static final String COLUMN_REPO_COUNT = "repo_count";
        public static final String[] ALL_COLUMNS = {
            _ID,
            COLUMN_NAME,
            COLUMN_SORT_ORDER,
        };
    }

    public static abstract class RepoTagEntry implements BaseColumns {
        public static final String TABLE_NAME = "repo_tag";
        public static final String COLUMN_REPO_ID = "repo_id";
        public static final String COLUMN_TAG_ID = "tag_id";
    }

    public static abstract class RepoCredential implements BaseColumns {
        public static final String TABLE_NAME = "credentials";
        public static final String COLUMN_TOKEN_ACCOUNT = "token_account";
        public static final String COLUMN_TOKEN_SECRET = "token_secret";
        public static final String COLUMN_REL_REPO = "rel_repo";
        public static final String[] ALL_COLUMNS = {
            _ID,
            COLUMN_TOKEN_ACCOUNT,
            COLUMN_TOKEN_SECRET,
            COLUMN_REL_REPO
        };
    }

}
