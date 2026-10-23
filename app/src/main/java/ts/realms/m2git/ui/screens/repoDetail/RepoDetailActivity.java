package ts.realms.m2git.ui.screens.repoDetail;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import java.util.ArrayList;
import java.util.List;

import ts.realms.m2git.R;
import ts.realms.m2git.core.command.MAsyncTask;
import ts.realms.m2git.core.command.RepoOperationDelegate;
import ts.realms.m2git.core.command.SyncHost;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.core.models.Tag;
import ts.realms.m2git.local.database.RepoDbManager;
import ts.realms.m2git.local.preference.Profile;
import ts.realms.m2git.ui.components.adapters.RepoOperationsAdapter;
import ts.realms.m2git.ui.components.dialogs.TagPickerDialog;
import ts.realms.m2git.ui.components.fragments.BranchChooserActivity;
import ts.realms.m2git.ui.components.fragments.FilesFragment;
import ts.realms.m2git.ui.components.fragments.StatusFragment;
import ts.realms.m2git.ui.screens.commitDiff.CommitsFragment;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.viewModels.BaseFragment;

public class RepoDetailActivity extends BaseCompatActivity implements SyncHost {

    private static final int FILES_FRAGMENT_INDEX = 0;
    private static final int COMMITS_FRAGMENT_INDEX = 1;
    private static final int STATUS_FRAGMENT_INDEX = 2;
    private static final int BRANCH_CHOOSE_ACTIVITY = 0;
    private static final int SEARCH_REPO_ACTIVITY = 1;
    private ActionBar mActionBar;
    private FilesFragment mFilesFragment;
    private CommitsFragment mCommitsFragment;
    private StatusFragment mStatusFragment;
    private RelativeLayout mRightDrawer;
    private ListView mRepoOperationList;
    private DrawerLayout mDrawerLayout;
    private RepoOperationsAdapter mDrawerAdapter;
    private TabItemPagerAdapter mTabItemPagerAdapter;
    private ViewPager mViewPager;
    private Button mCommitNameButton;
    private ImageView mCommitType;
    private MenuItem mSearchItem;
    private MenuItem mSortItem;
    private View mSearchBar;
    private EditText mSearchBarInput;
    private Repo mRepo;
    private View mPullProgressContainer;
    private ProgressBar mPullProgressBar;
    private TextView mPullMsg;
    private TextView mPullLeftHint;
    private TextView mPullRightHint;
    private RepoOperationDelegate mRepoDelegate;
    private int mSelectedTab;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case BRANCH_CHOOSE_ACTIVITY:
                String branchName = mRepo.getBranchName();
                if (branchName == null) {
                    showToastMessage(R.string.error_something_wrong);
                    return;
                }
                reset(branchName);
                break;
            case SEARCH_REPO_ACTIVITY:
                // 搜索结果页点目录回来：切到文件页并跳进那个目录
                if (resultCode == RESULT_OK && data != null) {
                    String dirPath = data.getStringExtra(SearchResultsActivity.EXTRA_RESULT_DIR);
                    if (dirPath != null) {
                        mViewPager.setCurrentItem(FILES_FRAGMENT_INDEX);
                        mFilesFragment.setCurrentDir(new java.io.File(dirPath));
                    }
                }
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepo = (Repo) getIntent().getSerializableExtra(Repo.TAG);
        if (mRepo == null && getIntent().hasExtra("repo_id")) {
            int repoId = getIntent().getIntExtra("repo_id", -1);
            if (repoId != -1) {
                mRepo = Repo.getRepoById(repoId);
            }
        }
        // aweful hack! workaround for null repo when returning from BranchChooser, but going to
        // shortly refactor passing in serialised repo, so not worth doing more to fix for now
        if (mRepo == null) {
            finish();
            return;
        }
        repoInit();
        setTitle(mRepo.getDisplayName());
        setContentView(R.layout.activity_repo_detail);
        setupActionBar();
        createFragments();
        setupViewPager();
        setupPullProgressView();
        setupSearchBar();
        setupDrawer();
        mCommitNameButton = findViewById(R.id.commitName);
        mCommitType = findViewById(R.id.commitType);
        mCommitNameButton.setOnClickListener(view -> {
            Intent intent = new Intent(RepoDetailActivity.this, BranchChooserActivity.class);
            intent.putExtra(Repo.TAG, mRepo);
            startActivityForResult(intent, BRANCH_CHOOSE_ACTIVITY);
        });
        String branchName = mRepo.getBranchName();
        if (branchName == null) {
            showToastMessage(R.string.error_something_wrong);
            return;
        }
        resetCommitButtonName(branchName);
    }

    public RepoOperationDelegate getRepoDelegate() {
        if (mRepoDelegate == null) {
            mRepoDelegate = new RepoOperationDelegate(mRepo, this);
        }
        return mRepoDelegate;
    }

    private void setupViewPager() {
        mViewPager = findViewById(R.id.pager);
        // R8 会剥离 PagerTitleStrip 的 @ViewPager$DecorView 运行时注解，ViewPager 反射
        // 识别 decor 落空后标签条按 widthFactor=0 测量且永不 layout（release 整行消失），
        // 故显式标记 isDecor
        ((ViewPager.LayoutParams) findViewById(R.id.pager_title_strip).getLayoutParams()).isDecor = true;
        mTabItemPagerAdapter = new TabItemPagerAdapter(getSupportFragmentManager());
        mViewPager.setAdapter(mTabItemPagerAdapter);
        mViewPager.setOnPageChangeListener(mTabItemPagerAdapter);
    }

    /**
     * 工具栏下方的全宽搜索条（activity_repo_detail_content.xml 的 searchBar）。
     * 曾经这里用的是 SearchView 折叠展开 —— 但它和排序/标签/抽屉图标挤同一条工具栏，
     * 输入框只剩两百多像素，标题也被吃光。现在搜索条独占一行，入口在溢出菜单第一项。
     *
     * 输入即过滤当前 tab（文件页 = 当前目录文件名，{@link FilesFragment#setFilter}；
     * 提交页 = 提交历史，{@link CommitsFragment#setFilter}），右侧按钮弹
     * {@link #showSearchRepoDialog()} 做全仓库递归搜索。设计见 docs/file-search-design.md。
     */
    private void setupSearchBar() {
        mSearchBar = findViewById(R.id.searchBar);
        mSearchBarInput = findViewById(R.id.searchBarInput);
        mSearchBarInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s.toString());
            }
        });
        findViewById(R.id.searchBarRepo).setOnClickListener(view -> showSearchRepoDialog());
        findViewById(R.id.searchBarClose).setOnClickListener(view -> hideSearchBar());
    }

    private void showSearchBar() {
        mSearchBar.setVisibility(View.VISIBLE);
        updateSearchBarHint();
        mSearchBarInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            // 入口在溢出菜单：点选后弹出窗当帧还在收起、焦点尚未回到 Activity 窗口，
            // 同帧 showSoftInput 有概率被忽略（搜索条滑出但键盘不弹）。挪到下一帧再弹。
            mSearchBarInput.post(() ->
                imm.showSoftInput(mSearchBarInput, InputMethodManager.SHOW_IMPLICIT));
        }
    }

    private void hideSearchBar() {
        // 先清词（触发 applyFilter(null) 恢复完整列表），再收条
        mSearchBarInput.setText("");
        mSearchBar.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchBarInput.getWindowToken(), 0);
        }
    }

    private boolean isSearchBarVisible() {
        return mSearchBar != null && mSearchBar.getVisibility() == View.VISIBLE;
    }

    /** 搜索条的输入转发给当前 tab 的过滤器；状态页没有过滤语义，忽略。 */
    private void applyFilter(String query) {
        if (mViewPager == null) return;
        switch (mViewPager.getCurrentItem()) {
            case FILES_FRAGMENT_INDEX:
                mFilesFragment.setFilter(query);
                break;
            case COMMITS_FRAGMENT_INDEX:
                mCommitsFragment.setFilter(query);
                break;
        }
    }

    /** 搜索条的提示词跟 tab 走：文件页过滤文件名，提交页过滤提交历史。 */
    private void updateSearchBarHint() {
        if (mSearchBarInput == null || mViewPager == null) return;
        mSearchBarInput.setHint(mViewPager.getCurrentItem() == COMMITS_FRAGMENT_INDEX
            ? R.string.search_hint_filter_commits : R.string.search_hint_filter_files);
    }

    private void setupDrawer() {
        mDrawerLayout = findViewById(R.id.drawer_layout);
        mRightDrawer = findViewById(R.id.right_drawer);
        mRepoOperationList = findViewById(R.id.repoOperationList);
        mDrawerAdapter = new RepoOperationsAdapter(this);
        mRepoOperationList.setAdapter(mDrawerAdapter);
        mRepoOperationList.setOnItemClickListener(mDrawerAdapter);
    }

    private void setupPullProgressView() {
        mPullProgressContainer = findViewById(R.id.pullProgressContainer);
        mPullProgressContainer.setVisibility(View.GONE);
        mPullProgressBar = mPullProgressContainer
            .findViewById(R.id.pullProgress);
        mPullMsg = mPullProgressContainer.findViewById(R.id.pullMsg);
        mPullLeftHint = mPullProgressContainer
            .findViewById(R.id.leftHint);
        mPullRightHint = mPullProgressContainer
            .findViewById(R.id.rightHint);
    }

    /**
     * 工具栏不显示返回箭头（2026-10-20 决议）：标题是仓库名（外部仓库还带 " (external)"
     * 后缀），411dp 宽的工具栏上箭头要吃掉约 57dp（箭头 48dp 触控区 + 标题左边距差），
     * 去掉后标题可用宽度从约 191dp 提到约 248dp；配合搜索收进溢出菜单可达约 296dp
     * （实测基线 1080×2400 @420dpi）。
     * 返回统一交给系统返回（手势 / 三键 / 键盘），走 {@link #onKeyUp(int, KeyEvent)}：
     * 搜索条开着先收条 → 文件页在子目录先上跳一级 → 最后才 finish。这比原来工具栏箭头
     * 直接 finish() 更符合层级语义（旧实现两条"返回"路径语义不一致，已随箭头一起去掉）。
     */
    private void setupActionBar() {
        mActionBar = getSupportActionBar();
        mActionBar.setDisplayShowTitleEnabled(true);
        mActionBar.setDisplayHomeAsUpEnabled(false);
        mActionBar.setDisplayShowHomeEnabled(false);
    }

    private void createFragments() {
        mFilesFragment = FilesFragment.newInstance(mRepo);
        mCommitsFragment = CommitsFragment.newInstance(mRepo, null);
        mStatusFragment = StatusFragment.newInstance(mRepo);
    }

    private void resetCommitButtonName(String commitName) {
        int commitType = Repo.getCommitType(commitName);
        switch (commitType) {
            case Repo.COMMIT_TYPE_REMOTE:
                // change the display name to local branch
                commitName = Repo.convertRemoteName(commitName);
            case Repo.COMMIT_TYPE_HEAD:
                mCommitType.setVisibility(View.VISIBLE);
                mCommitType.setImageResource(Profile.getStyledResource(this, R.attr.ic_branch_l));
                break;
            case Repo.COMMIT_TYPE_TAG:
                mCommitType.setVisibility(View.VISIBLE);
                mCommitType.setImageResource(Profile.getStyledResource(this, R.attr.ic_tag_l));
                break;
            case Repo.COMMIT_TYPE_TEMP:
                mCommitType.setVisibility(View.GONE);
                break;
        }
        String displayName = Repo.getCommitDisplayName(commitName);
        mCommitNameButton.setText(displayName);
    }

    public void reset(String commitName) {
        resetCommitButtonName(commitName);
        reset();
    }

    public void reset() {
        mFilesFragment.reset();
        mCommitsFragment.reset();
        mStatusFragment.reset();
    }

    public FilesFragment getFilesFragment() {
        return mFilesFragment;
    }

    public void setFilesFragment(FilesFragment filesFragment) {
        mFilesFragment = filesFragment;
    }

    public void setCommitsFragment(CommitsFragment commitsFragment) {
        mCommitsFragment = commitsFragment;
    }

    public void setStatusFragment(StatusFragment statusFragment) {
        mStatusFragment = statusFragment;
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.repo_detail, menu);
        // 搜索（溢出菜单项）对文件页（当前目录名称过滤）与提交页（提交历史过滤）
        // 都有意义，状态页没有
        mSearchItem = menu.findItem(R.id.action_search);
        mSearchItem.setVisible(mSelectedTab != STATUS_FRAGMENT_INDEX);
        // 排序只对文件页有意义。mSelectedTab 的初值就是 FILES_FRAGMENT_INDEX(0)，
        // 所以首次 inflate 时不必额外读 pager.getCurrentItem()。
        mSortItem = menu.findItem(R.id.action_sort_files);
        mSortItem.setVisible(mSelectedTab == FILES_FRAGMENT_INDEX);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                // 搜索条开着时返回先收它，而不是退出详情页
                if (isSearchBarVisible()) {
                    hideSearchBar();
                    return true;
                }
                // fall through：与 DEL 走同一条「返回/向上」路径
            case KeyEvent.KEYCODE_DEL:
                int position = mViewPager.getCurrentItem();
                OnBackClickListener onBackClickListener = mTabItemPagerAdapter
                    .getItem(position).getOnBackClickListener();
                if (onBackClickListener != null) {
                    if (onBackClickListener.onClick())
                        return true;
                }
                finish();
                return true;
            case KeyEvent.KEYCODE_F:
                mViewPager.setCurrentItem(FILES_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_C:
                mViewPager.setCurrentItem(COMMITS_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_S:
                mViewPager.setCurrentItem(STATUS_FRAGMENT_INDEX);
                return true;
            case KeyEvent.KEYCODE_SLASH:
                if (event.isShiftPressed()) {
                    showKeyboardShortcutsHelpOverlay();
                }
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void showKeyboardShortcutsHelpOverlay() {
        showMessageDialog(R.string.dialog_keymap_title, getString(R.string.dialog_keymap_mesg));
    }

    public void error() {
        finish();
        showToastMessage(R.string.error_unknown);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_drawer) {
            if (mDrawerLayout.isDrawerOpen(mRightDrawer)) {
                mDrawerLayout.closeDrawer(mRightDrawer);
            } else {
                mDrawerLayout.openDrawer(mRightDrawer);
            }
            return true;
        } else if (item.getItemId() == R.id.action_edit_tags) {
            showEditTagsDialog();
            return true;
        } else if (item.getItemId() == R.id.action_search) {
            showSearchBar();
            return true;
        } else if (item.getItemId() == R.id.action_sort_files) {
            if (mFilesFragment != null) {
                mFilesFragment.showSortDialog();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * 「在仓库中搜索…」的条件对话框：关键词 + 模式（文件名 / 文件内容）+ 大小写勾选 +
     * 类型 glob 过滤。确认后启动 {@link SearchResultsActivity} 做递归搜索；点目录结果
     * 由 onActivityResult(SEARCH_REPO_ACTIVITY) 接回并跳目录。设计见
     * docs/file-search-design.md。大小写勾选存 repo_view_state，搜索条的
     * 当前目录过滤读的是同一份（{@link FilesListAdapter}）。
     */
    private void showSearchRepoDialog() {
        final android.content.SharedPreferences prefs = getSharedPreferences(
            getString(R.string.repo_view_state_file), MODE_PRIVATE);
        final String caseKey = getString(R.string.pref_key_search_case_sensitive);

        final android.widget.EditText keywordInput = new android.widget.EditText(this);
        keywordInput.setHint(R.string.search_hint_keyword);
        keywordInput.setSingleLine(true);
        // 预填搜索条里已输入的词：两个搜索入口串成一条路径（先过滤本目录，不够再全仓库搜）
        if (mSearchBarInput != null && mSearchBarInput.getText().length() > 0) {
            keywordInput.setText(mSearchBarInput.getText());
            keywordInput.setSelection(keywordInput.getText().length());
        }

        final android.widget.RadioGroup modeGroup = new android.widget.RadioGroup(this);
        modeGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        android.widget.RadioButton nameRadio = new android.widget.RadioButton(this);
        nameRadio.setText(R.string.search_mode_name);
        nameRadio.setId(android.view.View.generateViewId());
        android.widget.RadioButton contentRadio = new android.widget.RadioButton(this);
        contentRadio.setText(R.string.search_mode_content);
        contentRadio.setId(android.view.View.generateViewId());
        modeGroup.addView(nameRadio);
        modeGroup.addView(contentRadio);
        nameRadio.setChecked(true);

        final android.widget.CheckBox caseBox = new android.widget.CheckBox(this);
        caseBox.setText(R.string.search_case_sensitive);
        caseBox.setChecked(prefs.getBoolean(caseKey, false));

        final android.widget.EditText globInput = new android.widget.EditText(this);
        globInput.setHint(R.string.search_hint_glob);
        globInput.setSingleLine(true);

        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int margin = getResources().getDimensionPixelSize(R.dimen.general_padding_larger);
        content.setPadding(margin, margin / 2, margin, 0);
        content.addView(keywordInput);
        content.addView(modeGroup);
        content.addView(caseBox);
        content.addView(globInput);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);

        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_search_repo_title)
            .setView(scroll)
            .setPositiveButton(R.string.action_search, (dialog, which) -> {
                String query = keywordInput.getText().toString().trim();
                if (query.isEmpty()) {
                    showToastMessage(R.string.search_empty_keyword);
                    return;
                }
                prefs.edit().putBoolean(caseKey, caseBox.isChecked()).apply();
                Intent intent = new Intent(this, SearchResultsActivity.class);
                intent.putExtra(Repo.TAG, mRepo);
                intent.putExtra(SearchResultsActivity.EXTRA_QUERY, query);
                intent.putExtra(SearchResultsActivity.EXTRA_MODE,
                    modeGroup.getCheckedRadioButtonId() == contentRadio.getId()
                        ? SearchResultsActivity.MODE_CONTENT : SearchResultsActivity.MODE_NAME);
                intent.putExtra(SearchResultsActivity.EXTRA_CASE_SENSITIVE, caseBox.isChecked());
                intent.putExtra(SearchResultsActivity.EXTRA_GLOB,
                    globInput.getText().toString().trim());
                startActivityForResult(intent, SEARCH_REPO_ACTIVITY);
            })
            .setNegativeButton(R.string.label_cancel, null)
            .show();
    }

    /**
     * 详情页的标签编辑。与列表页长按共用同一个面板（{@link TagPickerDialog}），
     * 差别只在入口：这里一次只针对当前仓库。写库后由 RepoDbManager 广播，列表页只重绘
     * 对应那一行——仓库的集合与顺序都没变，没必要整表重算，滚动位置也就不会动。
     */
    private void showEditTagsDialog() {
        List<Tag> tags = Tag.getTagList(RepoDbManager.queryAllTags());
        List<String> current = RepoDbManager.queryRepoTagMap().get((long) mRepo.getID());
        // 现查而不是用 mRepo.getTagNames()：从快捷方式进来时仓库是按 id 从库里读的，没带标签
        if (current == null) current = new ArrayList<>();
        TagPickerDialog.show(this, R.string.dialog_edit_tags_title,
            () -> Tag.getTagList(RepoDbManager.queryAllTags()),
            Tag.idsOfNames(tags, current),
            true,   // 允许就地新建
            false,  // 不需要命中数
            false,  // 编辑场景没有「无标签」伪标签，互斥不参与
            tagIds -> RepoDbManager.setRepoTags(mRepo.getID(),
                Tag.namesOfIds(Tag.getTagList(RepoDbManager.queryAllTags()), tagIds)));
    }

    public void closeOperationDrawer() {
        mDrawerLayout.closeDrawer(mRightDrawer);
    }

    public void enterDiffActionMode() {
        mViewPager.setCurrentItem(COMMITS_FRAGMENT_INDEX);
        mCommitsFragment.enterDiffActionMode();
    }

    private void repoInit() {
        mRepo.updateLatestCommitInfo();
        mRepo.getRemotes();
    }

    public class ProgressCallback implements MAsyncTask.AsyncTaskCallback {

        private final int mInitMsg;

        public ProgressCallback(int initMsg) {
            mInitMsg = initMsg;
        }

        @Override
        public void onPreExecute() {
            mPullMsg.setText(mInitMsg);
            Animation anim = AnimationUtils.loadAnimation(
                RepoDetailActivity.this, R.anim.fade_in);
            mPullProgressContainer.setAnimation(anim);
            mPullProgressContainer.setVisibility(View.VISIBLE);
            mPullLeftHint.setText(R.string.progress_left_init);
            mPullRightHint.setText(R.string.progress_right_init);
        }

        @Override
        public void onProgressUpdate(String... progress) {
            mPullMsg.setText(progress[0]);
            mPullLeftHint.setText(progress[1]);
            mPullRightHint.setText(progress[2]);
            mPullProgressBar.setProgress(Integer.parseInt(progress[3]));
        }

        @Override
        public void onPostExecute(Boolean isSuccess) {
            Animation anim = AnimationUtils.loadAnimation(
                RepoDetailActivity.this, R.anim.fade_out);
            mPullProgressContainer.setAnimation(anim);
            mPullProgressContainer.setVisibility(View.GONE);
            reset();
        }

        @Override
        public boolean doInBackground(Void... params) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
            return true;
        }

    }

    class TabItemPagerAdapter extends FragmentPagerAdapter implements ViewPager.OnPageChangeListener {

        private final int[] PAGE_TITLE = {R.string.tab_files_label,
            R.string.tab_commits_label, R.string.tab_status_label};

        public TabItemPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public BaseFragment getItem(int position) {
            switch (position) {
                case FILES_FRAGMENT_INDEX:
                    return mFilesFragment;
                case COMMITS_FRAGMENT_INDEX:
                    return mCommitsFragment;
                case STATUS_FRAGMENT_INDEX:
                    return mStatusFragment;
            }
            return mFilesFragment;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return getString(PAGE_TITLE[position]);
        }

        @Override
        public int getCount() {
            return PAGE_TITLE.length;
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageSelected(int position) {
            mSelectedTab = position;
            // 状态页是第 3 页，落在 offscreenPageLimit=1 之内：来回滑不会销毁重建它，
            // 也不会走 onResume（实测，见 StatusFragment 注释），所以「回到这一页就刷新」
            // 只能挂在这里。stage / unstage 之后切回来看到的就是新索引状态。
            if (position == STATUS_FRAGMENT_INDEX) {
                mStatusFragment.reset();
            }
            if (mSearchItem != null) {
                mSearchItem.setVisible(position != STATUS_FRAGMENT_INDEX);
            }
            if (mSortItem != null) {
                mSortItem.setVisible(position == FILES_FRAGMENT_INDEX);
            }
            // 搜索条跟着 tab 走：提示词换一套，过滤词套用到新 tab；状态页没有过滤
            // 语义，直接收起（溢出菜单里的搜索项本来也在这一页隐藏）。
            if (position == STATUS_FRAGMENT_INDEX) {
                if (isSearchBarVisible()) {
                    hideSearchBar();
                }
            } else if (isSearchBarVisible()) {
                updateSearchBarHint();
                applyFilter(mSearchBarInput.getText().toString());
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {

        }

    }

}
