package ts.realms.m2git.ui.components.fragments;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.ui.components.adapters.FilesListAdapter;
import ts.realms.m2git.ui.components.dialogs.RepoFileOperationDialog;
import ts.realms.m2git.ui.components.dialogs.SortOptionsDialog;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity.OnBackClickListener;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailFragment;
import ts.realms.m2git.utils.FsUtils;

/**
 * Created by sheimi on 8/5/13.
 */
public class FilesFragment extends RepoDetailFragment {

    private static final String CURRENT_DIR = "current_dir";

    private ListView mFilesList;
    private FilesListAdapter mFilesListAdapter;

    private File mCurrentDir;
    private File mRootDir;

    private Repo mRepo;

    public static FilesFragment newInstance(Repo mRepo) {
        FilesFragment fragment = new FilesFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable(Repo.TAG, mRepo);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_files, container, false);
        getRawActivity().setFilesFragment(this);

        Bundle bundle = getArguments();
        mRepo = (Repo) bundle.getSerializable(Repo.TAG);
        if (mRepo == null && savedInstanceState != null) {
            mRepo = (Repo) savedInstanceState.getSerializable(Repo.TAG);
        }
        if (mRepo == null) {
            return v;
        }
        mRootDir = mRepo.getDir();

        mFilesList = v.findViewById(R.id.filesList);

        mFilesListAdapter = new FilesListAdapter(getActivity(),
            file -> {
                String name = file.getName();
                return !name.equals(".git");
            });
        mFilesList.setAdapter(mFilesListAdapter);

        mFilesList
            .setOnItemClickListener((adapterView, view, position, id) -> {
                File file = mFilesListAdapter.getItem(position);
                if (file.isDirectory()) {
                    setCurrentDir(file);
                    return;
                }
                String fileName = file.getName().toLowerCase();
                boolean isMdFile = fileName.endsWith(".md");
                SharedPreferences prefs = getActivity().getSharedPreferences(
                    getString(R.string.preference_file_key), 0);

                if (isMdFile) {
                    String mode = prefs.getString(getString(R.string.pref_key_markdown_open_mode), "system");
                    if ("system".equals(mode)) {
                        try {
                            Uri uri = FileProvider.getUriForFile(
                                getActivity(), getActivity().getPackageName() + ".fileprovider", file);
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(uri, "text/markdown");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            PackageManager pm = getActivity().getPackageManager();
                            if (!pm.queryIntentActivities(intent, 0).isEmpty()) {
                                getActivity().startActivity(intent);
                                return;
                            }
                        } catch (Exception e) {
                            Timber.e(e, "Failed to open .md with system app, falling back to built-in preview");
                        }
                    }
                }

                String mime = FsUtils.getMimeType(file);
                if (FsUtils.isTextMimeType(mime) || isMdFile) {
                    Intent intent = new Intent(getActivity(),
                        ViewFileActivity.class);
                    intent.putExtra(ViewFileActivity.TAG_FILE_NAME,
                        file.getAbsolutePath());
                    intent.putExtra(Repo.TAG, mRepo);
                    getRawActivity().startActivity(intent);
                    return;
                }
                try {
                    FsUtils.openFile(((BaseCompatActivity) getActivity()), file);
                } catch (ActivityNotFoundException e) {
                    Timber.e(e);
                    ((BaseCompatActivity) getActivity()).showMessageDialog(R.string.dialog_error_title,
                        getString(R.string.error_can_not_open_file));
                }
            });

        mFilesList
            .setOnItemLongClickListener((adapterView, view, position, id) -> {
                File file = mFilesListAdapter.getItem(position);
                RepoFileOperationDialog dialog = new RepoFileOperationDialog();
                Bundle args = new Bundle();
                args.putString(RepoFileOperationDialog.FILE_PATH,
                    file.getAbsolutePath());
                args.putString(RepoFileOperationDialog.REPO_PATH,
                    mRootDir.getAbsolutePath());
                dialog.setArguments(args);
                dialog.show(getFragmentManager(), "repo-file-op-dialog");
                return true;
            });

        // 恢复「上次看到哪个目录」。切 tab 会把本页 view 销毁再重建：ViewPager 默认
        // offscreenPageLimit=1，滑到 status 页时 Files 页已出界，FragmentPagerAdapter 走
        // detach —— fragment 实例还在（mCurrentDir 字段保留），只是 view 重建；activity
        // 整体重建（旋转 / 进程被回收）时则是新实例 + savedInstanceState。两条路都不能
        // 一律 reset()：那正是「切到 status 看一眼、切回来却回到顶层目录」的根因 ——
        // 恢复出来的目录会被紧随其后的 reset() 覆盖成仓库根。
        if (mCurrentDir == null && savedInstanceState != null) {
            String currentDirPath = savedInstanceState.getString(CURRENT_DIR);
            if (currentDirPath != null) {
                mCurrentDir = new File(currentDirPath);
            }
        }
        // 目录可能在离开期间被删掉 / 改名（例如在提交页检出了旧版本、或在别处删了它），
        // 指不到就放弃恢复，落回仓库根。
        if (mCurrentDir != null && mCurrentDir.isDirectory()) {
            // view 重建后 adapter 是新的，必须重列一次；排序设置由 adapter 构造时读回
            setCurrentDir(mCurrentDir);
        } else {
            reset();
        }
        return v;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(Repo.TAG, mRepo);
        if (mCurrentDir != null) {
            outState.putString(CURRENT_DIR, mCurrentDir.getAbsolutePath());
        }
    }

    /**
     * Set the directory listing currently being displayed
     *
     * @param dir
     */
    public void setCurrentDir(File dir) {
        mCurrentDir = dir;
        if (mFilesListAdapter != null) {
            mFilesListAdapter.setDir(mCurrentDir);
        }
    }

    /**
     * If the root dir has previously been set, set the root dir to be the currently displayed
     * directory listing.
     */
    public void resetCurrentDir() {
        if (mRootDir == null)
            return;
        setCurrentDir(mRootDir);
    }

    @Override
    public void reset() {
        resetCurrentDir();
    }

    /**
     * 右上角排序图标调它。确定之后只重排**当前目录**（走 {@link #setCurrentDir(File)}）而不调
     * {@link #reset()} —— 后者会退回仓库根，用户在子目录里换个排序凭被弹回顶层是不可接受的。
     * 排序键与目录优先开关都存进 repo_view_state，见 {@link FilesListAdapter#setSortSettings}。
     */
    public void showSortDialog() {
        if (mFilesListAdapter == null) return;
        SortOptionsDialog.showWithDirsFirst(getRawActivity(), R.string.dialog_sort_files_title,
            new int[]{R.string.sort_key_name, R.string.sort_key_modified, R.string.sort_key_size},
            mFilesListAdapter.getSortKey(), mFilesListAdapter.getSortDirection(),
            mFilesListAdapter.isDirsFirst(),
            (sortKey, sortDir, dirsFirst) ->
                mFilesListAdapter.setSortSettings(sortKey, sortDir, dirsFirst));
    }

    public void newDir(String name) {
        File file = new File(mCurrentDir, name);
        if (file.exists()) {
            showToastMessage(R.string.alert_file_exists);
            return;
        }
        file.mkdir();
        setCurrentDir(mCurrentDir);
    }

    /**
     * Create a new file within the currently displayed directory
     *
     * @param name
     */
    public void newFile(String name) throws IOException {
        File file = new File(mCurrentDir, name);
        if (file.exists()) {
            showToastMessage(R.string.alert_file_exists);
            return;
        }
        file.createNewFile();
        setCurrentDir(mCurrentDir);
    }

    @Override
    public OnBackClickListener getOnBackClickListener() {
        return () -> {
            if (mRootDir == null || mCurrentDir == null)
                return false;
            if (mRootDir.equals(mCurrentDir))
                return false;
            File parent = mCurrentDir.getParentFile();
            setCurrentDir(parent);
            return true;
        };
    }
}
