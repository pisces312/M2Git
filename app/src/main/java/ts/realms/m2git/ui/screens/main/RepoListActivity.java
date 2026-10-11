package ts.realms.m2git.ui.screens.main;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.core.view.MenuItemCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;
import ts.realms.m2git.MainApplication;
import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.remote.CloneTask;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.core.models.Tag;
import ts.realms.m2git.core.network.ssh.PrivateKeyUtils;
import ts.realms.m2git.core.network.transport.MGitHttpConnectionFactory;
import ts.realms.m2git.databinding.ActivityMainBinding;
import ts.realms.m2git.local.database.RepoDbManager;
import ts.realms.m2git.local.preference.PreferenceHelper;
import ts.realms.m2git.ui.components.adapters.RepoListAdapter;
import ts.realms.m2git.ui.components.dialogs.DummyDialogListener;
import ts.realms.m2git.ui.components.dialogs.ImportLocalRepoDialog;
import ts.realms.m2git.ui.components.dialogs.SortOptionsDialog;
import ts.realms.m2git.ui.components.dialogs.TagFilterPopup;
import ts.realms.m2git.ui.components.fragments.ExploreFileActivity;
import ts.realms.m2git.ui.components.fragments.ImportRepositoryActivity;
import ts.realms.m2git.ui.components.views.TagChipRenderer;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity;
import ts.realms.m2git.ui.screens.settings.UserSettingsActivity;

public class RepoListActivity extends BaseCompatActivity {

    private static final int REQUEST_IMPORT_REPO = 0;
    private Context mContext;
    private RepoListAdapter mRepoListAdapter;
    private ActivityMainBinding activityMainBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RepoListViewModel viewModel = new ViewModelProvider(this).get(RepoListViewModel.class);
        CloneViewModel cloneViewModel = new ViewModelProvider(this).get(CloneViewModel.class);

        activityMainBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        activityMainBinding.setLifecycleOwner(this);
        activityMainBinding.setCloneViewModel(cloneViewModel);
        activityMainBinding.setViewModel(viewModel);
        activityMainBinding.setClickHandler(action -> {
            if (ClickActions.CLONE.name().equals(action)) {
                cloneRepo();
            } else {
                hideCloneView();
            }
        });

        PrivateKeyUtils.migratePrivateKeys();
        initUpdatedSSL();
        Repo.installLfs();

        mRepoListAdapter = new RepoListAdapter(this);
        activityMainBinding.repoList.setAdapter(mRepoListAdapter);
        mRepoListAdapter.queryAllRepo();
        activityMainBinding.repoList.setOnItemClickListener(mRepoListAdapter);
        activityMainBinding.repoList.setOnItemLongClickListener(mRepoListAdapter);
        activityMainBinding.repoList.setEmptyView(activityMainBinding.repoListEmpty);
        // 列表每次重算后同步顶部筛选条：标签在别处被改名/删除时，这里跟着变
        mRepoListAdapter.setOnListRefreshed(this::renderFilterBar);
        activityMainBinding.filterClear.setOnClickListener(v -> mRepoListAdapter.clearFilters());
        activityMainBinding.filterMatchMode.setOnClickListener(v ->
            mRepoListAdapter.setMatchAll(!mRepoListAdapter.isMatchAll()));
        renderFilterBar();
        mContext = getApplicationContext();

        Uri uri = this.getIntent().getData();
        if (uri != null) {
            URL mRemoteRepoUrl = null;
            try {
                mRemoteRepoUrl = new URL(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
            } catch (MalformedURLException e) {
                Toast.makeText(mContext, R.string.invalid_url, Toast.LENGTH_LONG).show();
                Timber.e(e);
            }

            if (mRemoteRepoUrl != null) {
                String remoteUrl = mRemoteRepoUrl.toString();
                String repoName = remoteUrl.substring(remoteUrl.lastIndexOf("/") + 1);
                StringBuilder repoUrlBuilder = new StringBuilder(remoteUrl);

                //need git extension to clone some repos
                if (!remoteUrl.toLowerCase().endsWith(getString(R.string.git_extension))) {
                    repoUrlBuilder.append(getString(R.string.git_extension));
                } else { //if has git extension remove it from repository name
                    repoName = repoName.substring(0, repoName.lastIndexOf('.'));
                }
                //Check if there are others repositories with same remote
                List<Repo> repositoriesWithSameRemote = Repo.getRepoList(RepoDbManager.searchRepo(remoteUrl));

                //if so, just open it
                if (!repositoriesWithSameRemote.isEmpty()) {
                    Toast.makeText(mContext, R.string.repository_already_present, Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(mContext, RepoDetailActivity.class);
                    intent.putExtra(Repo.TAG, repositoriesWithSameRemote.get(0));
                    startActivity(intent);
                } else if (Repo.getDir(PreferenceHelper.getInstance(MainApplication.getContext()), repoName).exists()) {
                    // Repository with name end already exists, see https://github.com/maks/MGit/issues/289
                    cloneViewModel.setRemoteUrl(repoUrlBuilder.toString());
                    showCloneView();
                } else {
                    final String cloningStatus = getString(R.string.cloning);
                    Repo mRepo = Repo.createRepo(repoName, repoUrlBuilder.toString(), cloningStatus);
                    CloneTask task = new CloneTask(mRepo, true, 0, cloningStatus, null);
                    task.executeTask();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // check everytime on the repo list activity that we still have file access permission
        checkAndRequestRequiredPermissions(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem searchItem = menu.findItem(R.id.action_search);
        configSearchAction(searchItem);
        configTagFilterAction(menu.findItem(R.id.action_tag_filter));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        int itemId = item.getItemId();
        if (itemId == R.id.action_new) {
            showCloneView();
            return true;
        } else if (itemId == R.id.action_import_repo) {
            intent = new Intent(this, ImportRepositoryActivity.class);
            startActivityForResult(intent, REQUEST_IMPORT_REPO);
            forwardTransition();
            return true;
        } else if (itemId == R.id.action_settings) {
            intent = new Intent(this, UserSettingsActivity.class);
            startActivity(intent);
            return true;
        } else if (itemId == R.id.action_sort) {
            showSortDialog();
            return true;
        } else if (itemId == R.id.action_tags) {
            showManageTagsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- 顶部常驻筛选条 ----

    /**
     * 每次列表重算后调用。没筛选时整条隐藏，不占走一行列表高度；有筛选时选中的标签一直挂在顶上，
     * 往下翻仓库也随时看得到当前筛的是什么。已选标签用实底 chip（与条目里的浅底同色），点一下即取消。
     */
    private void renderFilterBar() {
        List<Tag> selected = mRepoListAdapter.getSelectedFilters();
        boolean active = !selected.isEmpty();
        activityMainBinding.repoFilterBar.setVisibility(active ? View.VISIBLE : View.GONE);
        activityMainBinding.repoFilterDivider.setVisibility(active ? View.VISIBLE : View.GONE);
        activityMainBinding.repoListEmpty.setText(getString(active
            ? R.string.repo_list_empty_filtered : R.string.repo_list_empty));
        if (!active) return;

        activityMainBinding.filterMatchMode.setText(getString(mRepoListAdapter.isMatchAll()
            ? R.string.filter_mode_all : R.string.filter_mode_any));
        activityMainBinding.filterMatchMode
            .setBackground(TagChipRenderer.neutralPill(this, true));

        activityMainBinding.filterChips.removeAllViews();
        for (Tag tag : selected) {
            final int tagId = tag.getId();
            boolean untagged = tag.isUntagged();
            String name = untagged ? getString(R.string.tag_untagged) : tag.getName();
            activityMainBinding.filterChips.addView(TagChipRenderer.createSelectedChip(this, name,
                untagged ? "untagged" : tag.getName(),
                v -> mRepoListAdapter.toggleFilter(tagId)));
        }
        // 「命中 / 总数」纯数字，无需文案
        activityMainBinding.filterCount.setText(
            mRepoListAdapter.getCount() + " / " + mRepoListAdapter.getTotalRepoCount());
    }

    // ---- 排序 ----

    /**
     * 面板本身抽到了 {@link SortOptionsDialog}：文件列表要用同一个面板，只是多一节「目录优先」
     * 和一行关于「创建时间」的说明。这里传的选项顺序就是 RepoListAdapter.SORT_KEY_* /
     * SORT_DIR_* 的常量顺序 —— 面板按**下标**回传，加排序键不会改掉老用户的设置。
     */
    private void showSortDialog() {
        SortOptionsDialog.show(this, R.string.dialog_sort_title,
            new int[]{R.string.sort_key_name, R.string.sort_key_last_commit,
                R.string.sort_key_time_added},
            mRepoListAdapter.getSortKey(), mRepoListAdapter.getSortDirection(),
            // 仓库列表没有「目录优先」这一说，第三个参数直接忽略
            (sortKey, sortDir, dirsFirst) -> mRepoListAdapter.setSortSettings(sortKey, sortDir));
    }

    // ---- 筛选 / 标签管理 ----

    /**
     * 工具栏上的「按标签筛选」：图标在放大镜与溢出三点之间，点它在图标正下方弹勾选列表。
     * 菜单项配了 app:actionLayout —— 默认 ActionBar 不暴露菜单项自己的 View，而弹窗要锚在点击
     * 位置，只能自己造一个 View 当锚点；代价是这个 item 的点击也由 action view 自己接管
     * （onOptionsItemSelected 收不到它）。
     */
    private void configTagFilterAction(MenuItem item) {
        if (item == null) return;
        final View anchor = MenuItemCompat.getActionView(item);
        if (anchor == null) return;
        anchor.setOnClickListener(v -> showTagFilterPopup(anchor));
    }

    /**
     * 弹窗里勾一个立刻筛一次，没有确定按钮 —— 与顶部筛选条点掉 chip 是同一条链路
     * （setFilterSelection → 重查 → requery → 筛选条自动重画）。
     */
    private void showTagFilterPopup(View anchor) {
        TagFilterPopup.show(this, anchor,
            mRepoListAdapter::getTagFilterOptions,
            mRepoListAdapter.getFilterTagIds(),
            // 「无标签」与具体标签在「全部满足」下无解，直接互斥掉，别让用户撞空结果；
            // 「任一满足」下两者是合法并集，不互斥。
            mRepoListAdapter.isMatchAll(),
            mRepoListAdapter::setFilterSelection);
    }

    private void showManageTagsDialog() {
        List<Tag> tags = mRepoListAdapter.getAssignableTags();
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.label_create));
        for (Tag tag : tags) {
            options.add(tag.getName() + " (" + tag.getRepoCount() + ")");
        }

        onOptionDialogClicked[] listeners = new onOptionDialogClicked[options.size()];
        listeners[0] = this::showNewTagDialog;
        for (int i = 0; i < tags.size(); i++) {
            final Tag tag = tags.get(i);
            listeners[i + 1] = () -> showTagActionsDialog(tag);
        }

        showOptionsDialog(R.string.dialog_manage_tags_title,
            options.toArray(new String[0]), listeners);
    }

    private void showNewTagDialog() {
        showEditTextDialog(R.string.dialog_new_tag_title,
            R.string.dialog_new_tag_hint, R.string.label_create,
            name -> RepoDbManager.createTagIfAbsent(name));
    }

    private void showTagActionsDialog(final Tag tag) {
        String[] options = {
            getString(R.string.label_rename),
            getString(R.string.label_delete)
        };
        onOptionDialogClicked[] listeners = new onOptionDialogClicked[]{
            () -> showRenameTagDialog(tag),
            () -> showDeleteTagDialog(tag)
        };
        showOptionsDialog(R.string.dialog_choose_option, options, listeners);
    }

    private void showRenameTagDialog(final Tag tag) {
        showEditTextDialog(R.string.dialog_rename_tag_title,
            R.string.dialog_new_tag_hint, R.string.label_rename, name -> {
                if (!RepoDbManager.renameTag(tag.getId(), name)) {
                    // UNIQUE 冲突：库里已有同名标签，改名被拒
                    showToastMessage(R.string.error_rename_tag_exists);
                }
            });
    }

    private void showDeleteTagDialog(final Tag tag) {
        showMessageDialog(R.string.dialog_delete_tag_title,
            getString(R.string.dialog_delete_tag_msg), R.string.label_delete,
            (dialogInterface, i) -> RepoDbManager.deleteTag(tag.getId()));
    }

    public void configSearchAction(MenuItem searchItem) {
        SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView == null) return;
        SearchListener searchListener = new SearchListener();
        MenuItemCompat.setOnActionExpandListener(searchItem, searchListener);
        searchView.setIconifiedByDefault(true);
        searchView.setOnQueryTextListener(searchListener);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;
        if (requestCode == REQUEST_IMPORT_REPO) {
            final String path = data.getExtras().getString(ExploreFileActivity.RESULT_PATH);
            File file = new File(path);
            File dotGit = new File(file, Repo.DOT_GIT_DIR);
            if (!dotGit.exists()) {
                showToastMessage(getString(R.string.error_no_repository));
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.dialog_comfirm_import_repo_title);
            builder.setMessage(R.string.dialog_comfirm_import_repo_msg);
            builder.setNegativeButton(R.string.label_cancel, new DummyDialogListener());
            builder.setPositiveButton(R.string.label_import, (dialogInterface, i) -> {
                Bundle args = new Bundle();
                args.putString(ImportLocalRepoDialog.FROM_PATH, path);
                ImportLocalRepoDialog rld = new ImportLocalRepoDialog();
                rld.setArguments(args);
                rld.show(getSupportFragmentManager(), "import-local-dialog");
            });
            builder.show();
        }
    }

    public void finish() {
        rawfinish();
    }

    private void initUpdatedSSL() {
        MGitHttpConnectionFactory.install();
        Timber.i("Installed custom HTTPS factory");
    }

    private void cloneRepo() {
        if (activityMainBinding.getCloneViewModel().validate()) {
            hideCloneView();
            activityMainBinding.getCloneViewModel().cloneRepo();
        }
    }

    private void showCloneView() {
        activityMainBinding.getCloneViewModel().show(true);
    }

    private void hideCloneView() {
        activityMainBinding.getCloneViewModel().show(false);
        // hideKeyboard
        InputMethodManager inputMethodManager = (InputMethodManager) this.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (this.getCurrentFocus() != null) {
            inputMethodManager.hideSoftInputFromWindow(this.getCurrentFocus().getWindowToken(), 0);
        }
    }

    public enum ClickActions {
        CLONE, CANCEL
    }

    public class SearchListener implements SearchView.OnQueryTextListener,
        MenuItemCompat.OnActionExpandListener {

        @Override
        public boolean onQueryTextSubmit(String s) {
            return false;
        }

        @Override
        public boolean onQueryTextChange(String s) {
            mRepoListAdapter.searchRepo(s);
            return false;
        }

        @Override
        public boolean onMenuItemActionExpand(MenuItem menuItem) {
            return true;
        }

        @Override
        public boolean onMenuItemActionCollapse(MenuItem menuItem) {
            mRepoListAdapter.queryAllRepo();
            return true;
        }

    }
}
