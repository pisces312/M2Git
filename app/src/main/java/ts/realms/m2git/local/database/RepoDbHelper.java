package ts.realms.m2git.local.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.StringCharacterIterator;

import timber.log.Timber;

/**
 * Created by sheimi on 8/6/13.
 * Modify by 霆枢 on 2025/12/04
 * 创建一个数据库repo.db，并包含两个表，repo表和credentials表。
 */
public class RepoDbHelper extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 4;
    private static final String DATABASE_NAME = "repo.db";

    public RepoDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    /**
     * 此方法暂未使用。
     */
    public static String addSlashes(String text) {
        final StringBuilder stringBuilder = new StringBuilder(text.length() * 2);
        final StringCharacterIterator iterator = new StringCharacterIterator(text);

        char character = iterator.current();

        while (character != StringCharacterIterator.DONE) {
            if (character == '"') stringBuilder.append("\\\"");
            else if (character == '\'') stringBuilder.append("''");
            else if (character == '\\') stringBuilder.append("\\\\");
            else if (character == '\n') stringBuilder.append("\\n");
            else if (character == '{') stringBuilder.append("\\{");
            else if (character == '}') stringBuilder.append("\\}");
            else stringBuilder.append(character);

            character = iterator.next();
        }

        return stringBuilder.toString();
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(RepoContract.REPO_ENTRY_CREATE);
        sqLiteDatabase.execSQL(RepoContract.REPO_CREDENTIALS_CREATE);
        sqLiteDatabase.execSQL(RepoContract.REPO_GROUP_CREATE);
        createTagTables(sqLiteDatabase);
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
        Timber.d("Upgrading database from v%d to v%d", oldVersion, newVersion);
        if (oldVersion < 2) {
            sqLiteDatabase.execSQL("ALTER TABLE " + RepoContract.RepoEntry.TABLE_NAME
                + " ADD COLUMN " + RepoContract.RepoEntry.COLUMN_NAME_GROUP_ID + " INTEGER DEFAULT NULL");
            sqLiteDatabase.execSQL("ALTER TABLE " + RepoContract.RepoEntry.TABLE_NAME
                + " ADD COLUMN " + RepoContract.RepoEntry.COLUMN_NAME_SORT_ORDER + " INTEGER DEFAULT 0");
            sqLiteDatabase.execSQL(RepoContract.REPO_GROUP_CREATE);
        }
        if (oldVersion < 3) {
            // v3: 仓库入库（克隆/导入/新建）时间。老数据留 NULL，排序时沉底，不伪造时间。
            sqLiteDatabase.execSQL("ALTER TABLE " + RepoContract.RepoEntry.TABLE_NAME
                + " ADD COLUMN " + RepoContract.RepoEntry.COLUMN_NAME_TIME_ADDED + " INTEGER DEFAULT NULL");
        }
        if (oldVersion < 4) {
            // v4: 标签取代分组。号段说明见 docs/repo-tags-design.md —— v3 已被带分组的构建
            // 占用过（有的设备库已经是 v3），把建表并进 v3 会被 onUpgrade 静默跳过。
            createTagTables(sqLiteDatabase);
            sqLiteDatabase.execSQL(RepoContract.TAG_MIGRATE_FROM_GROUPS);
            sqLiteDatabase.execSQL(RepoContract.REPO_TAG_MIGRATE_FROM_GROUPS);
        }
    }

    private void createTagTables(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL(RepoContract.TAG_CREATE);
        sqLiteDatabase.execSQL(RepoContract.REPO_TAG_CREATE);
        sqLiteDatabase.execSQL(RepoContract.REPO_TAG_INDEX_BY_TAG);
        sqLiteDatabase.execSQL(RepoContract.REPO_TAG_INDEX_BY_REPO);
    }
}
