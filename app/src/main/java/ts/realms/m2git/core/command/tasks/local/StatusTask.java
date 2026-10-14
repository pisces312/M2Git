package ts.realms.m2git.core.command.tasks.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import ts.realms.m2git.ui.common.errors.StopTaskException;
import ts.realms.m2git.core.command.MAsyncTask;
import ts.realms.m2git.core.command.tasks.RepoOpTask;
import ts.realms.m2git.core.models.Repo;

public class StatusTask extends RepoOpTask implements MAsyncTask.AsyncTaskDoCallback {

    /**
     * 一组变更等于「JGit 拿哪两棵树比出来的」，所以它顺带就是看这组文件 diff 时该用的两侧：
     * added/changed/removed 比的是 HEAD ↔ index，missing/modified 比的是 index ↔ 工作区。
     * CommitDiffTask 认 "dircache" / "filetree" 这两个哨兵名，与状态页原有的两个整仓 diff
     * 按钮用的是同一套。
     */
    public enum Group {
        ADDED("A", "HEAD", "dircache"),
        CHANGED("C", "HEAD", "dircache"),
        REMOVED("D", "HEAD", "dircache"),
        MISSING("!", "dircache", "filetree"),
        MODIFIED("M", "dircache", "filetree"),
        /**
         * 未合并的文件 index 里有 stage 1/2/3 三份，单看哪一侧都不对，取 HEAD ↔ 工作区：
         * 这是相对上次提交的整体改动。
         */
        CONFLICTING("U", "HEAD", "filetree"),
        /** 既不在 HEAD 也不在 index，没有可 diff 的对象。 */
        UNTRACKED("?", null, null);

        public final String badge;
        public final String oldCommit;
        public final String newCommit;

        Group(String badge, String oldCommit, String newCommit) {
            this.badge = badge;
            this.oldCommit = oldCommit;
            this.newCommit = newCommit;
        }

        public boolean hasDiff() {
            return newCommit != null;
        }
    }

    /** 一条变更：仓库相对路径（JGit 的 '/' 分隔，不带前导斜杠）+ 所属分组。 */
    public static class StatusFile implements Comparable<StatusFile> {
        public final String path;
        public final Group group;

        public StatusFile(String path, Group group) {
            this.path = path;
            this.group = group;
        }

        /**
         * 同一路径可以合法地出现在两个分组里（先 add 再改 = changed + modified），所以这里
         * 不去重：一行恰好对应一个 diff 配对，列表也就和上半部的文本条数一致。
         */
        @Override
        public int compareTo(StatusFile other) {
            int cmp = path.compareTo(other.path);
            return cmp != 0 ? cmp : group.ordinal() - other.group.ordinal();
        }
    }

    private final GetStatusCallback mCallback;
    private final StringBuffer mResult = new StringBuffer();
    private final List<StatusFile> mFiles = new ArrayList<>();

    public StatusTask(Repo repo, GetStatusCallback callback) {
        super(repo, true);
        mCallback = callback;
    }

    @Override
    public boolean doInBackground(Void... params) {
        return status();
    }

    @Override
    public void onPostExecute(Boolean isSuccess) {
        super.onPostExecute(isSuccess);
        if (mCallback != null && isSuccess) {
            Collections.sort(mFiles);
            mCallback.postStatus(mResult.toString(), mFiles);
        }
    }

    private boolean status() {
        try {
            org.eclipse.jgit.api.Status status = mRepo.getGit().status().call();
            convertStatus(status);
        } catch (StopTaskException e) {
            return false;
        } catch (Throwable e) {
            setException(e);
            return false;
        }
        return true;
    }

    private void convertStatus(org.eclipse.jgit.api.Status status) {
        if (!status.hasUncommittedChanges() && status.isClean()) {
            mResult.append("Nothing to commit, working directory clean");
            return;
        }
        // TODO if working dir not clean
        convertStatusSet("Added files:", status.getAdded(), Group.ADDED);
        convertStatusSet("Changed files:", status.getChanged(), Group.CHANGED);
        convertStatusSet("Removed files:", status.getRemoved(), Group.REMOVED);
        convertStatusSet("Missing files:", status.getMissing(), Group.MISSING);
        convertStatusSet("Modified files:", status.getModified(), Group.MODIFIED);
        convertStatusSet("Conflicting files:", status.getConflicting(), Group.CONFLICTING);
        convertStatusSet("Untracked files:", status.getUntracked(), Group.UNTRACKED);

    }

    private void convertStatusSet(String type, Set<String> status, Group group) {
        if (status.isEmpty())
            return;
        mResult.append(type);
        mResult.append("\n\n");
        for (String s : status) {
            mResult.append('\t');
            mResult.append(s);
            mResult.append('\n');
            mFiles.add(new StatusFile(s, group));
        }
        mResult.append("\n");
    }

    public interface GetStatusCallback {
        void postStatus(String result, List<StatusFile> files);
    }

}
