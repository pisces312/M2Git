package ts.realms.m2git.core.command.tasks.local;

import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

import ts.realms.m2git.ui.common.errors.StopTaskException;
import ts.realms.m2git.core.command.MAsyncTask;
import ts.realms.m2git.core.command.tasks.RepoOpTask;
import ts.realms.m2git.core.models.Repo;

public class GetCommitTask extends RepoOpTask implements MAsyncTask.AsyncTaskDoCallback {

    /** {@link #setMaxCount} 传这个值 = 不限条数，一次拿完剩余历史（原有语义）。 */
    public static final int UNLIMITED = -1;

    private final GetCommitCallback mCallback;
    private final String mFile;
    private final int mSkip;
    private final int mMaxCount;
    private List<RevCommit> mResult;

    /** 原有语义：一次拿完整历史。需要全量（搜索）时用这个构造。 */
    public GetCommitTask(Repo repo, String file, GetCommitCallback callback) {
        this(repo, file, 0, UNLIMITED, callback);
    }

    /**
     * 分页取历史。{@code skip} / {@code maxCount} 直接落到 JGit 的
     * {@link org.eclipse.jgit.api.LogCommand#setSkip(int)} /
     * {@link org.eclipse.jgit.api.LogCommand#setMaxCount(int)}。
     *
     * 语义在 jgit 7.8.0（就是 app/libs 里那个 jar）+ 本仓库 1045 条真实历史上实测确认：
     * 顺序是新的在前；page0[0]==全量[0]、page1[0]==全量[20]、两页零重叠；
     * 尾部不足一页时短返回（不抛）；带 {@code file} 时限制作用在**按路径过滤之后**的结果上
     * （本仓库 AndroidManifest.xml 全量 37 条，maxCount=2 就返回最近改动它的 2 条）。
     */
    public GetCommitTask(Repo repo, String file, int skip, int maxCount, GetCommitCallback callback) {
        super(repo, true);
        mFile = file;
        mSkip = skip;
        mMaxCount = maxCount;
        mCallback = callback;
    }

    @Override
    public boolean doInBackground(Void... params) {
        return getCommitsList();
    }

    @Override
    public void onPostExecute(Boolean isSuccess) {
        super.onPostExecute(isSuccess);
        if (mCallback != null) {
            mCallback.postCommits(mResult);
        }
    }

    public boolean getCommitsList() {
        try {
            LogCommand cmd = mRepo.getGit().log();
            if (mFile != null)
                cmd.addPath(mFile);
            // 只在真的设限时调用：这两个 setter 没有取值校验，-1 是「不限」的哨兵而不是合法参数
            if (mMaxCount != UNLIMITED)
                cmd.setMaxCount(mMaxCount);
            if (mSkip > 0)
                cmd.setSkip(mSkip);
            Iterable<RevCommit> commits = cmd.call();
            mResult = new ArrayList<RevCommit>();
            for (RevCommit commit : commits) {
                mResult.add(commit);
            }
        } catch (StopTaskException e) {
            return false;
        } catch (Throwable e) {
            setException(e);
            return false;
        }
        return true;
    }

    public interface GetCommitCallback {
        void postCommits(List<RevCommit> commits);
    }

}
