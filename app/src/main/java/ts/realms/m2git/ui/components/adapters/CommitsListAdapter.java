package ts.realms.m2git.ui.components.adapters;

import android.content.Context;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;

import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.GetCommitTask;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.utils.BasicFunctions;


/**
 * Created by sheimi on 8/18/13.
 */
public class CommitsListAdapter extends BaseAdapter {

    /**
     * 首屏只取这么多条。理由：以前 {@code resetCommit()} 一次把整个历史走一遍灌进
     * {@link #mAll}，而 JGit 那一趟的代价随历史长度线性增长（本仓库 1045 条，随便一个
     * fork 的上游就是几万条）。100 行远超一屏可见数，滚到底再续取。
     */
    private static final int PAGE_SIZE = 100;

    private final Repo mRepo;
    private final DateFormat mCommitDateFormatter;
    private final Set<Integer> mChosenItems;
    private final Context mContext;
    private final String mFile;
    private final Object mProgressLock = new Object();
    private String mFilter;
    private ArrayList<RevCommit> mAll;
    private ArrayList<Integer> mFiltered;
    private BackgroundUpdate mUpdate;
    private int mPosted;
    private boolean mIsIncomplete;
    private int mProgressCursor;
    private long mPostAtTime;
    /** 上一批返回短于请求条数 => 历史已经取完，不用再发分页请求。 */
    private boolean mHistoryExhausted;
    /** 有一批分页在飞，防滚到底连点时重复请求同一页。 */
    private boolean mLoadingMore;
    /**
     * 第几代列表。{@link #clear()} 递增它，也就是「作废所有在飞的请求」。
     * 分页是追加语义，所以在飞的那一批必须能认出自己属于哪一代：pull 之后 {@code reset()}
     * 会立刻再发一次 page 0，若上一代的旧结果晚到并追加，就会出现重复条目 —— 以前的代码是
     * 整体替换 mAll，天然没有这个问题，改成追加就得把这道闸补上。
     */
    private int mGeneration;

    public CommitsListAdapter(Context context, Set<Integer> chosenItems,
                              Repo repo, String file) {
        super();
        mFile = file;
        mContext = context;
        mChosenItems = chosenItems;
        mRepo = repo;
        mAll = new ArrayList<RevCommit>();
        mFiltered = null;
        mFilter = null;
        mCommitDateFormatter = android.text.format.DateFormat.getDateFormat(mContext);
    }

    private boolean isAccepted(RevCommit in) {
        if (mFilter == null) {
            return true;
        }
        if (in.getId().toString().startsWith("commit " + mFilter.toLowerCase())) {
            return true;
        }
        /* Search in raw buffer is fast but it may find the string in
         * e.g. parents field or as part of keyword. So first search in
         * raw buffer and then look in parsed components if raw buffer
         * contains needle. */
        if (!new String(in.getRawBuffer()).contains(mFilter)) {
            return false;
        }
        return (in.getAuthorIdent().getName().contains(mFilter)
            || in.getAuthorIdent().getEmailAddress().contains(mFilter)
            || in.getCommitterIdent().getName().contains(mFilter)
            || in.getCommitterIdent().getEmailAddress().contains(mFilter)
            || in.getFullMessage().contains(mFilter));
    }

    private void stopFiltering() {
        try {
            mUpdate.cancel(true);
            mUpdate = null;
        } catch (Exception e) {
        }
    }

    private void doFiltering() {
        mFiltered = null;
        if (mFilter != null) {
            mUpdate = new BackgroundUpdate();
            mPosted = 0;
            mIsIncomplete = true;
            mFiltered = new ArrayList<>();
            mProgressCursor = 0;
            // Show first result after 100 ms
            mPostAtTime = System.nanoTime() + 100000000;
            mUpdate.execute();
        } else {
            notifyDataSetChanged();
        }
    }

    public void setFilter(String query) {
        synchronized (mProgressLock) {
            stopFiltering();
            if (query == null || query.isEmpty()) {
                mFilter = null;
            } else {
                mFilter = query;
            }
            doFiltering();
            // 搜索历来是「把 mAll 全扫一遍」，不是数据库查询。窗口没装满就开始过滤，命中的
            // 只有前 PAGE_SIZE 条，用户会看到「明明有的提交搜不到」。所以过滤一挂上就把剩余
            // 历史补齐（一次不限条数的请求）；补齐那批落地时回调里会再 doFiltering() 一次，
            // 从 cursor 0 重扫完整历史。先出窗口内的结果，不空等。
            maybeLoadRestOfHistory();
        }
    }

    /**
     * 需要完整历史（搜索中）且还没拿全、并且当前没有在飞的那一批时，发一次不限条数的请求。
     *
     * 「没有在飞的批次」这个条件是这条路径唯一的并发闸门：滚动续取和搜索补齐共用同一个 mAll，
     * 两个请求同时在飞时，第二个的 skip 是发请求那一刻的 mAll.size()，等第一个先落地它就已经
     * 过期了 —— 结果是从中间某条重复往后再取一遍。所以谁都可以表达「我需要剩余历史」，但只有
     * 这一处发请求，且每个出口都会再问一次（{@link #requestCommits} 的回调末尾）。
     */
    private void maybeLoadRestOfHistory() {
        if (mFilter == null || mHistoryExhausted || mLoadingMore) return;
        mLoadingMore = true;
        requestCommits(mAll.size(), GetCommitTask.UNLIMITED);
    }

    @Override
    public int getCount() {
        if (mFilter == null)
            return mAll.size();
        if (mIsIncomplete)
            return mPosted + 1;
        return mFiltered.size();
    }

    @Override
    public long getItemId(int position) {
        if (mIsIncomplete && position >= mPosted) {
            return -1;
        }
        if (mFilter == null) {
            return position;
        } else {
            try {
                return mFiltered.get(position);
            } catch (Exception e) {
                return -1;
            }
        }
    }

    public RevCommit getItem(int position) {
        if (mIsIncomplete && position >= mPosted) {
            return null;
        }
        try {
            return (mFilter == null) ? mAll.get(position) : mAll.get(mFiltered.get(position));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isProgressBar(int position) {
        if (mFilter == null)
            return position >= mAll.size();
        if (mIsIncomplete)
            return position >= mPosted;
        return position >= mFiltered.size();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        LayoutInflater inflater = LayoutInflater.from(mContext);
        if (isProgressBar(position)) {
            return new ProgressBar(mContext, null, android.R.attr.progressBarStyleLarge);
        }
        CommitsListItemHolder holder = null;
        if (convertView != null) {
            holder = (CommitsListItemHolder) convertView.getTag();
        }
        if (holder == null) {
            convertView = inflater.inflate(R.layout.list_item_commits, parent,
                false);
            holder = new CommitsListItemHolder();
            holder.commitsTitle = convertView
                .findViewById(R.id.commitTitle);
            holder.commitsIcon = convertView
                .findViewById(R.id.commitIcon);
            holder.commitAuthor = convertView
                .findViewById(R.id.commitAuthor);
            holder.commitsMsg = convertView
                .findViewById(R.id.commitMsg);
            holder.commitTime = convertView
                .findViewById(R.id.commitTime);
            convertView.setTag(holder);
        }
        RevCommit commit = getItem(position);
        PersonIdent person = commit.getAuthorIdent();
        Date date = person.getWhen();
        String email = person.getEmailAddress();

        holder.commitsTitle
            .setText(Repo.getCommitDisplayName(commit.getName()));
        holder.commitAuthor.setText(person.getName());
        holder.commitsMsg.setText(commit.getShortMessage());
        holder.commitTime.setText(mCommitDateFormatter.format(date));

        BasicFunctions.setAvatarImage(holder.commitsIcon, email);

        int color, colorResId;
        if (mChosenItems.contains(position)) {
            colorResId = R.color.pressed_sgit;
        } else {
            colorResId = android.R.color.transparent;
        }
        if (mContext instanceof BaseCompatActivity) {
            color = mContext.getResources().getColor(colorResId);
            convertView.setBackgroundColor(color);
        }
        return convertView;
    }

    public void clear() {
        synchronized (mProgressLock) {
            stopFiltering();
            mGeneration++;
            mAll = new ArrayList<>();
            mHistoryExhausted = false;
            mLoadingMore = false;
            if (mFilter == null) {
                mFiltered = null;
            } else {
                mFiltered = new ArrayList<>();
            }
        }
    }

    public void resetCommit() {
        clear();
        synchronized (mProgressLock) {
            mLoadingMore = true;
            requestCommits(0, PAGE_SIZE);
        }
    }

    /**
     * 滚到底时由 {@code CommitsFragment} 调用，取下一页。
     * 正在过滤时不动作：那一趟已经把剩余历史整个取走了（见 {@link #setFilter(String)}），
     * 再叠一个分页只会让两批结果抢同一个 mAll。
     */
    public void loadNextPage() {
        synchronized (mProgressLock) {
            if (mFilter != null || mHistoryExhausted || mLoadingMore) return;
            mLoadingMore = true;
            requestCommits(mAll.size(), PAGE_SIZE);
        }
    }

    /** 还有没有下一页可取（给滚动监听做阈值判断用，避免每次都空转一次加锁）。 */
    public boolean hasNextPage() {
        return !mHistoryExhausted && !mLoadingMore && mFilter == null;
    }

    /**
     * 取一批历史并追加到 mAll。
     *
     * 追加用「复制一份再加」而不是原地 addAll：{@link BackgroundUpdate} 的扫描线程不持锁地
     * 读 mAll（{@code mAll.size()} / {@code mAll.get(i)}），原地加长会让它读到一半新一半旧。
     * 换新数组后，在跑的那一趟仍抱着旧快照走完；旧 mFiltered 存的是下标，而新条目只追加在尾部，
     * 下标不会错位 —— 随后再 {@link #doFiltering()} 一次得到完整结果。
     *
     * 顺序前提已在 jgit 7.8.0 + 本仓库 1045 条历史上实测：新的在前、skip/maxCount 无重叠无空洞、
     * 尾部不足一页是短返回（因此 {@code 返回条数 < 请求条数} 就是「到底了」的判据）。
     */
    private void requestCommits(int skip, int maxCount) {
        final int requested = maxCount;
        final int generation = mGeneration;
        GetCommitTask getCommitTask = new GetCommitTask(mRepo, mFile, skip, maxCount,
            commits -> {
                synchronized (mProgressLock) {
                    // 这一批属于已经被作废的那一代列表，丢掉（不要动 flag，也不要追加）
                    if (generation != mGeneration) return;
                    if (commits == null) {
                        // 任务失败或仓库打不开：不能把「这批没拿到」当成「历史到此为止」，
                        // 否则一次瞬时失败会把后面的分页永久锁死。
                        mLoadingMore = false;
                        return;
                    }
                    stopFiltering();
                    ArrayList<RevCommit> next = new ArrayList<>(mAll);
                    next.addAll(commits);
                    mAll = next;
                    if (requested == GetCommitTask.UNLIMITED) {
                        mHistoryExhausted = true;
                    } else {
                        mHistoryExhausted = commits.size() < requested;
                    }
                    mLoadingMore = false;
                    if (mFilter != null) {
                        doFiltering();
                    } else {
                        notifyDataSetChanged();
                    }
                    // 这一批落地后 mLoadingMore 已经放开，之前被它挡住的「补齐剩余历史」在这里
                    // 补发（滚动分页先落地、用户紧接着开始搜索的那条路径就靠这一句接上）。
                    maybeLoadRestOfHistory();
                }
            });
        getCommitTask.executeTask();
    }

    private static class CommitsListItemHolder {
        public ImageView commitsIcon;
        public TextView commitsTitle;
        public TextView commitsMsg;
        public TextView commitAuthor;
        public TextView commitTime;
    }

    private class BackgroundUpdate extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            int i;
            for (i = mProgressCursor; i < mAll.size(); i++) {
                if (mFiltered.size() != mPosted && System.nanoTime() > mPostAtTime) {
                    synchronized (mProgressLock) {
                        mProgressCursor = i;
                        mPosted = mFiltered.size();
                        return null;
                    }
                }
                if (isCancelled()) {
                    return null;
                }
                if (isAccepted(mAll.get(i)))
                    mFiltered.add(i);
            }
            synchronized (mProgressLock) {
                mPosted = mFiltered.size();
                mIsIncomplete = false;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (isCancelled()) {
                return;
            }
            synchronized (mProgressLock) {
                notifyDataSetChanged();
                if (mIsIncomplete) {
                    // Updates after 1 s
                    mPostAtTime = System.nanoTime() + 1000000000;
                    mUpdate = new BackgroundUpdate();
                    mUpdate.execute();
                }
            }
        }
    }
}
