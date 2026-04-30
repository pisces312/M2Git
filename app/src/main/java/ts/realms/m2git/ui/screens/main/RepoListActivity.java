package ts.realms.m2git.ui.screens.main;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
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
import ts.realms.m2git.core.models.RepoGroup;
import ts.realms.m2git.core.network.ssh.PrivateKeyUtils;
import ts.realms.m2git.core.network.transport.MGitHttpConnectionFactory;
import ts.realms.m2git.databinding.ActivityMainBinding;
import ts.realms.m2git.local.database.RepoDbManager;
import ts.realms.m2git.local.preference.PreferenceHelper;
import ts.realms.m2git.ui.components.adapters.RepoListAdapter;
import ts.realms.m2git.ui.components.dialogs.DummyDialogListener;
import ts.realms.m2git.ui.components.dialogs.ImportLocalRepoDialog;
import ts.realms.m2git.ui.components.fragments.ExploreFileActivity;
import ts.realms.m2git.ui.components.fragments.ImportRepositoryActivity;
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
        } else if (itemId == R.id.action_groups) {
            showManageGroupsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] sortOptions = {
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc),
            getString(R.string.sort_date_asc),
            getString(R.string.sort_date_desc)
        };
        int currentSort = mRepoListAdapter.getSortMode();
        showOptionsDialog(R.string.action_sort, sortOptions, new onOptionDialogClicked[]{
            () -> mRepoListAdapter.setSortMode(RepoListAdapter.SORT_BY_NAME_ASC),
            () -> mRepoListAdapter.setSortMode(RepoListAdapter.SORT_BY_NAME_DESC),
            () -> mRepoListAdapter.setSortMode(RepoListAdapter.SORT_BY_DATE_ASC),
            () -> mRepoListAdapter.setSortMode(RepoListAdapter.SORT_BY_DATE_DESC)
        });
    }

    private void showManageGroupsDialog() {
        Cursor cursor = RepoDbManager.queryAllGroups();
        final List<RepoGroup> groups = new ArrayList<>();
        if (cursor != null) {
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                groups.add(new RepoGroup(cursor));
                cursor.moveToNext();
            }
            cursor.close();
        }

        List<String> options = new ArrayList<>();
        options.add(getString(R.string.label_create));
        for (RepoGroup group : groups) {
            options.add(group.getName());
        }

        onOptionDialogClicked[] listeners = new onOptionDialogClicked[options.size()];
        listeners[0] = () -> showNewGroupDialog();
        for (int i = 0; i < groups.size(); i++) {
            final RepoGroup group = groups.get(i);
            listeners[i + 1] = () -> showGroupActionsDialog(group);
        }

        showOptionsDialog(R.string.dialog_manage_groups_title,
            options.toArray(new String[0]), listeners);
    }

    private void showNewGroupDialog() {
        showEditTextDialog(R.string.dialog_new_group_title,
            R.string.dialog_new_group_hint, R.string.label_create, name -> {
                if (!name.trim().isEmpty()) {
                    RepoDbManager.createGroup(name.trim());
                }
            });
    }

    private void showGroupActionsDialog(final RepoGroup group) {
        String[] options = {
            getString(R.string.label_rename),
            getString(R.string.label_delete)
        };
        onOptionDialogClicked[] listeners = new onOptionDialogClicked[]{
            () -> showRenameGroupDialog(group),
            () -> showDeleteGroupDialog(group)
        };
        showOptionsDialog(R.string.dialog_choose_option, options, listeners);
    }

    private void showRenameGroupDialog(final RepoGroup group) {
        showEditTextDialog(R.string.dialog_rename_group_title,
            R.string.dialog_new_group_hint, R.string.label_rename, name -> {
                if (!name.trim().isEmpty()) {
                    RepoDbManager.updateGroup(group.getId(), name.trim());
                }
            });
    }

    private void showDeleteGroupDialog(final RepoGroup group) {
        showMessageDialog(R.string.dialog_delete_group_title,
            getString(R.string.dialog_delete_group_msg), R.string.label_delete,
            (dialogInterface, i) -> RepoDbManager.deleteGroup(group.getId()));
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
