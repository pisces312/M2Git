package ts.realms.m2git.ui.components.fragments;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.StatusTask;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.ui.components.adapters.StatusFilesAdapter;
import ts.realms.m2git.ui.screens.commitDiff.CommitDiffActivity;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity.OnBackClickListener;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailFragment;
import ts.realms.m2git.utils.FsUtils;

/**
 * Created by sheimi on 8/5/13.
 */
public class StatusFragment extends RepoDetailFragment {

    private static final int SWIPE_MIN_DISTANCE = 200; // 最小滑动距离
    private static final int SWIPE_MIN_VELOCITY = 200; // 最小滑动速度
    /** 拖动分割条时上下两栏各不许压到的高度下限。 */
    private static final int MIN_PANE_HEIGHT_DP = 60;
    private static final float DEFAULT_TOP_RATIO = 0.5f;
    /** 读回来的占比只做「别离谱」的兜底，拖动区间另按屏幕高度算。 */
    private static final float SAVED_RATIO_FLOOR = 0.05f;

    private Repo mRepo;
    private ProgressBar mLoadding;
    private TextView mStatus;
    private Button mUnstagedDiff;
    private Button mStagedDiff;
    private GestureDetector gestureDetector;

    private View mTopPane;
    private View mSplitHandle;
    private View mFilesPane;
    private TextView mFilesHeader;
    private ListView mFilesList;
    private StatusFilesAdapter mFilesAdapter;
    /** 上半部占两栏总高度的比例，分割条拖动改它、落盘到 repo_view_state。 */
    private float mTopRatio = DEFAULT_TOP_RATIO;
    private float mDragStartY;
    private float mDragStartRatio;
    /**
     * 最后一次状态结果。{@link #onCreateView} 重建 view 后先按它画下半部，新 view 首帧就不是
     * 「空白 → 又填上」；真正的刷新时机在
     * {@link ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity.TabItemPagerAdapter#onPageSelected}
     * ——实测切 tab 不会让本页走 onResume（状态页在 offscreenPageLimit=1 之内，既不重建也不降级
     * 生命周期），而且从「看文件 / 看 diff」返回时本来就不该重取（文件内容变了不代表索引变了，
     * 重取还会把列表滚动位置打掉）。
     */
    private List<StatusTask.StatusFile> mLastFiles = Collections.emptyList();

    public static StatusFragment newInstance(Repo mRepo) {
        StatusFragment fragment = new StatusFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable(Repo.TAG, mRepo);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, container, false);
        getRawActivity().setStatusFragment(this);

        Bundle bundle = getArguments();
        mRepo = (Repo) bundle.getSerializable(Repo.TAG);
        if (mRepo == null && savedInstanceState != null) {
            mRepo = (Repo) savedInstanceState.getSerializable(Repo.TAG);
        }
        if (mRepo == null) {
            return v;
        }
        mTopPane = v.findViewById(R.id.status_text);
        mSplitHandle = v.findViewById(R.id.status_split_handle);
        mFilesPane = v.findViewById(R.id.status_files_pane);
        mFilesHeader = v.findViewById(R.id.status_files_header);
        mLoadding = v.findViewById(R.id.loading);
        mStatus = v.findViewById(R.id.status);
        mStatus.setMovementMethod(ScrollingMovementMethod.getInstance());
        mStagedDiff = v.findViewById(R.id.button_staged_diff);
        mUnstagedDiff = v.findViewById(R.id.button_unstaged_diff);
        mStagedDiff.setOnClickListener(v1 -> showDiff("HEAD", "dircache"));
        mUnstagedDiff.setOnClickListener(v2 -> showDiff("dircache", "filetree"));

        mFilesList = v.findViewById(R.id.statusFilesList);
        mFilesAdapter = new StatusFilesAdapter(getActivity());
        mFilesList.setAdapter(mFilesAdapter);
        mFilesList.setOnItemClickListener((parent, view, position, id) ->
            openFile(mFilesAdapter.getItem(position)));
        mFilesList.setOnItemLongClickListener((parent, view, position, id) -> {
            showDiff(mFilesAdapter.getItem(position));
            return true;
        });

        mTopRatio = clampRatio(readSavedTopRatio(), SAVED_RATIO_FLOOR);
        applyTopRatio();
        initSplitHandle();
        initGestureDetector();
        showFiles(mLastFiles);
        reset();
        return v;
    }

    /**
     * 初始化手势检测器。挂在状态文本上而不是根布局：根布局只会收到没被子 view 吃掉的触摸，
     * 而现在下半部是列表、中间是分割条，都会吃事件；挂到文本上还顺带修了原来「只有文字
     * 下方那块空白能触发刷新」的问题。监听返回 false，事件照旧交给 ScrollingMovementMethod。
     */
    private void initGestureDetector() {
        GestureDetector.SimpleOnGestureListener listener = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                                   float velocityX, float velocityY) {
                // 计算垂直滑动距离
                float dy = e2.getY() - e1.getY();

                // 判断是否为下滑刷新手势
                // dy > 0 表示向下滑动
                if (dy > 0 &&
                    Math.abs(dy) > SWIPE_MIN_DISTANCE &&
                    Math.abs(velocityY) > SWIPE_MIN_VELOCITY) {

                    // 检查TextView是否在顶部，避免与滚动冲突
                    if (mStatus.getScrollY() == 0) {
                        // 执行下滑刷新
                        performSwipeRefresh();
                        return true; // 消费事件
                    }
                }
                return false;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                // 必须返回true，否则onFling不会触发
                return true;
            }
        };

        // 初始化手势检测器
        gestureDetector = new GestureDetector(requireContext(), listener);

        mStatus.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            // 返回 false：让 TextView 自己接着处理滚动
            return false;
        });
    }

    /**
     * 分割条拖动。位移换算成占比（分母是两栏当前高度之和，重分配权重时它不变），
     * 上下各留 {@link #MIN_PANE_HEIGHT_DP} 的下限，抬手时落盘。
     */
    private void initSplitHandle() {
        mSplitHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mDragStartY = event.getRawY();
                    mDragStartRatio = mTopRatio;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int totalHeight = mTopPane.getHeight() + mFilesPane.getHeight();
                    if (totalHeight <= 0) {
                        return true;
                    }
                    float minRatio = MIN_PANE_HEIGHT_DP
                        * getResources().getDisplayMetrics().density / totalHeight;
                    float dy = event.getRawY() - mDragStartY;
                    mTopRatio = clampRatio(mDragStartRatio + dy / totalHeight, minRatio);
                    applyTopRatio();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    saveTopRatio();
                    v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    private static float clampRatio(float ratio, float min) {
        float lo = Math.min(min, 1f - min);
        float hi = Math.max(min, 1f - min);
        return ratio < lo ? lo : (ratio > hi ? hi : ratio);
    }

    private void applyTopRatio() {
        setWeight(mTopPane, mTopRatio);
        setWeight(mFilesPane, 1f - mTopRatio);
    }

    /** 两栏是根 LinearLayout 的直接子 view，权重之和恒为 1，所以上半的权重就是它的占比。 */
    private static void setWeight(View pane, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pane.getLayoutParams();
        lp.weight = weight;
        pane.setLayoutParams(lp);
    }

    private float readSavedTopRatio() {
        return getViewStatePrefs().getFloat(
            getString(R.string.pref_key_status_split_ratio), DEFAULT_TOP_RATIO);
    }

    private void saveTopRatio() {
        getViewStatePrefs().edit()
            .putFloat(getString(R.string.pref_key_status_split_ratio), mTopRatio)
            .apply();
    }

    /**
     * 视图态而不是设置：和文件页的排序偏好同放 repo_view_state，理由见
     * {@link ts.realms.m2git.ui.components.adapters.FilesListAdapter}。
     */
    private SharedPreferences getViewStatePrefs() {
        return getRawActivity().getSharedPreferences(
            getString(R.string.repo_view_state_file), Context.MODE_PRIVATE);
    }

    /**
     * 装下半部的变更文件列表；仓库干净时列表和分割条一起收起，上半部占满。
     */
    private void showFiles(List<StatusTask.StatusFile> files) {
        mLastFiles = files;
        mFilesAdapter.clear();
        if (files.isEmpty()) {
            mFilesPane.setVisibility(View.GONE);
            mSplitHandle.setVisibility(View.GONE);
            return;
        }
        mFilesAdapter.addAll(files);
        mFilesAdapter.notifyDataSetChanged();
        mFilesHeader.setText(getString(R.string.status_changed_files_count, files.size()));
        mFilesPane.setVisibility(View.VISIBLE);
        mSplitHandle.setVisibility(View.VISIBLE);
    }

    /**
     * 短按看内容。文件不在磁盘上（Missing / Removed）就没有「内容」可看：内容只剩 index 或
     * HEAD 里的那一份，所以直接走单文件 diff —— diff 里的减行就是它删除前的原文。
     */
    private void openFile(StatusTask.StatusFile statusFile) {
        File file = FsUtils.joinPath(mRepo.getDir(), statusFile.path);
        if (!file.exists() || file.isDirectory()) {
            showDiff(statusFile);
            return;
        }
        String mime = FsUtils.getMimeType(file);
        if (FsUtils.isTextMimeType(mime)) {
            Intent intent = new Intent(getActivity(), ViewFileActivity.class);
            intent.putExtra(ViewFileActivity.TAG_FILE_NAME, file.getAbsolutePath());
            intent.putExtra(Repo.TAG, mRepo);
            getRawActivity().startActivity(intent);
            return;
        }
        try {
            FsUtils.openFile(getRawActivity(), file);
        } catch (ActivityNotFoundException e) {
            Timber.e(e);
            getRawActivity().showMessageDialog(R.string.dialog_error_title,
                getString(R.string.error_can_not_open_file));
        }
    }

    private void showDiff(String oldCommit, String newCommit) {
        showDiff(oldCommit, newCommit, null);
    }

    /**
     * 长按列表里的一行：只看这一个文件的 diff。比哪两侧由它所属的分组决定
     * （{@link StatusTask.Group}），和上半部文本里它出现在哪个小标题下是同一件事。
     */
    private void showDiff(StatusTask.StatusFile statusFile) {
        StatusTask.Group group = statusFile.group;
        if (!group.hasDiff()) {
            showToastMessage(R.string.toast_status_untracked_no_diff);
            return;
        }
        showDiff(group.oldCommit, group.newCommit, statusFile.path);
    }

    private void showDiff(String oldCommit, String newCommit, String diffPath) {
        Intent intent = new Intent(getRawActivity(),
            CommitDiffActivity.class);
        intent.putExtra(CommitDiffActivity.OLD_COMMIT, oldCommit);
        intent.putExtra(CommitDiffActivity.NEW_COMMIT, newCommit);
        intent.putExtra(CommitDiffActivity.SHOW_DESCRIPTION, false);
        if (diffPath != null) {
            intent.putExtra(CommitDiffActivity.DIFF_PATH, diffPath);
        }
        intent.putExtra(Repo.TAG, mRepo);
        getRawActivity().startActivity(intent);
    }

    /**
     * 执行下滑刷新操作
     */
    private void performSwipeRefresh() {
        // 检查是否正在加载，避免重复刷新
        if (mLoadding != null && mLoadding.getVisibility() != View.VISIBLE) {
            // 调用reset方法刷新状态
            reset();
        }
    }

    @Override
    public void reset() {
        if (mLoadding == null || mStatus == null)
            return;
        mLoadding.setVisibility(View.VISIBLE);
        mStatus.setVisibility(View.GONE);
        StatusTask task = new StatusTask(mRepo, (result, files) -> {
            mStatus.setText(result);
            showFiles(files);
            mLoadding.setVisibility(View.GONE);
            mStatus.setVisibility(View.VISIBLE);
        });
        task.executeTask();
    }

    @Override
    public OnBackClickListener getOnBackClickListener() {
        return null;
    }

    // 保存状态
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mRepo != null) {
            outState.putSerializable(Repo.TAG, mRepo);
        }
    }
}
