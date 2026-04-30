package ts.realms.m2git.core.models;

import android.database.Cursor;

import java.io.Serializable;

import ts.realms.m2git.local.database.RepoContract;

/**
 * Model for a repo group
 */
public class RepoGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    private int mId;
    private String mName;
    private int mSortOrder;

    public RepoGroup(Cursor cursor) {
        mId = cursor.getInt(cursor.getColumnIndex(RepoContract.RepoGroupEntry._ID));
        mName = cursor.getString(cursor.getColumnIndex(RepoContract.RepoGroupEntry.COLUMN_NAME));
        mSortOrder = cursor.getInt(cursor.getColumnIndex(RepoContract.RepoGroupEntry.COLUMN_SORT_ORDER));
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

    @Override
    public String toString() {
        return mName;
    }
}
