package ts.realms.m2git.ui.components.adapters;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.core.models.Tag;
import ts.realms.m2git.local.database.RepoContract;
import ts.realms.m2git.local.database.RepoDbManager;
import ts.realms.m2git.ui.components.dialogs.TagPickerDialog;
import ts.realms.m2git.ui.components.views.TagChipRenderer;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.screens.main.RepoListActivity;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity;
import ts.realms.m2git.utils.BasicFunctions;

/**
 * 仓库列表：平铺 + 标签筛选 + 排序。
 * <p>
 * 原来这里是一套「分组 + 组头 + 折叠」的结构，已被标签取代（设计见 docs/repo-tags-design.md）：
 * 分组强制单一归属，而一个仓库天然同时属于多个维度；筛选一次就能得到目标子集并整屏铺开，
 * 比分组「先看到几块再点进去」少一层。所以本类没有桶状视图，只有裁剪 + 排序。
 */
public class RepoListAdapter extends ArrayAdapter<Repo> implements RepoDbManager.RepoDbObserver,
    AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private static final int QUERY_TYPE_SEARCH = 0;
    private static final int QUERY_TYPE_QUERY = 1;
    private static final String TAG_CLASS = RepoListAdapter.class.getSimpleName();

    public static final int SORT_KEY_NAME = 0;
    public static final int SORT_KEY_LAST_COMMIT = 1;
    public static final int SORT_KEY_TIME_ADDED = 2;
    public static final int SORT_DIR_ASC = 0;
    public static final int SORT_DIR_DESC = 1;

    /**
     * 偏好里存稳定字符串而不是位置索引：数组平移不会把用户的设置指到别的选项上。
     * 顺序与 SORT_KEY_* / SORT_DIR_* 常量一一对应。
     */
    private static final String[] SORT_KEY_VALUES = {"name", "last_commit", "time_added"};
    private static final String[] SORT_DIR_VALUES = {"asc", "desc"};

    private final DateFormat mCommitDateFormatter;
    private final RepoListActivity mActivity;
    private int mQueryType = QUERY_TYPE_QUERY;
    private String mSearchQueryString;

    private int mSortKey = SORT_KEY_NAME;
    private int mSortDir = SORT_DIR_ASC;
    /** true = 选中的标签要「全部满足」(AND)。标签的唯一职责是收窄结果，默认让多选更严格。 */
    private boolean mMatchAll = true;

    /** 选中的标签 id（0 = 无标签伪标签）。顺序即筛选条里的展示顺序。 */
    private final Set<Integer> mFilterTagIds = new LinkedHashSet<>();
    /** 每次 requery 刷新的标签注册表，用来给选中的 id 找名字、并剔除已被删除的标签。 */
    private List<Tag> mTagRegistry = new ArrayList<>();
    private int mTotalRepoCount = 0;
    private Runnable mOnListRefreshed;

    public RepoListAdapter(Context context) {
        super(context, 0);
        RepoDbManager.registerDbObserver(RepoContract.RepoEntry.TABLE_NAME, this);
        mActivity = (RepoListActivity) context;
        mCommitDateFormatter = android.text.format.DateFormat.getDateFormat(context);
        readPreferences();
    }

    // ---- 排序 / 筛选设置 ----

    public int getSortKey() {
        return mSortKey;
    }

    public int getSortDirection() {
        return mSortDir;
    }

    public boolean isMatchAll() {
        return mMatchAll;
    }

    public void setSortSettings(int sortKey, int sortDir) {
        mSortKey = clamp(sortKey, SORT_KEY_VALUES.length);
        mSortDir = clamp(sortDir, SORT_DIR_VALUES.length);
        SharedPreferences.Editor editor = getPrefs().edit();
        editor.putString(getString(R.string.pref_key_repo_sort_key), SORT_KEY_VALUES[mSortKey]);
        editor.putString(getString(R.string.pref_key_repo_sort_direction), SORT_DIR_VALUES[mSortDir]);
        editor.apply();
        requery();
    }

    public void setMatchAll(boolean matchAll) {
        mMatchAll = matchAll;
        getPrefs().edit().putBoolean(getString(R.string.pref_key_repo_tag_match_all), matchAll).apply();
        requery();
    }

    public Set<Integer> getFilterTagIds() {
        return new LinkedHashSet<>(mFilterTagIds);
    }

    public void setFilterSelection(Collection<Integer> tagIds) {
        mFilterTagIds.clear();
        if (tagIds != null) {
            mFilterTagIds.addAll(tagIds);
        }
        // 面板已经互斥掉了「无标签 + 具体标签」，这里是兜底：老偏好文件里可能存着这种组合，
        // 或者用户在面板开着的时候切了匹配方式。与其展示一个必然为空的列表，不如丢掉伪标签。
        if (mMatchAll && mFilterTagIds.size() > 1) {
            mFilterTagIds.remove(Tag.UNTAGGED_ID);
        }
        persistFilterSelection();
        requery();
    }

    /** 筛选条上点掉某个标签走这里。 */
    public void toggleFilter(int tagId) {
        if (mFilterTagIds.contains(tagId)) {
            mFilterTagIds.remove(tagId);
        } else {
            mFilterTagIds.add(tagId);
        }
        persistFilterSelection();
        requery();
    }

    public void clearFilters() {
        if (mFilterTagIds.isEmpty()) return;
        mFilterTagIds.clear();
        persistFilterSelection();
        requery();
    }

    public boolean isFilterActive() {
        return !mFilterTagIds.isEmpty();
    }

    /** 筛选前该查询条件下的仓库总数，用于筛选条上的「3 / 12」。 */
    public int getTotalRepoCount() {
        return mTotalRepoCount;
    }

    /** 列表刷新后回调，让宿主 Activity 同步顶部筛选条（标签被改名/删除时也要跟着变）。 */
    public void setOnListRefreshed(Runnable onListRefreshed) {
        mOnListRefreshed = onListRefreshed;
    }

    /** 筛选面板的选项：全部标签 + 末尾的「无标签」，带命中数。 */
    public List<Tag> getTagFilterOptions() {
        List<Tag> options = loadTags();
        options.add(new Tag(Tag.UNTAGGED_ID, "", 0, RepoDbManager.countReposWithoutTags()));
        return options;
    }

    /** 编辑仓库标签 / 管理标签用：只有真实标签。 */
    public List<Tag> getAssignableTags() {
        return loadTags();
    }

    /** 当前选中的标签，按注册表顺序；「无标签」恒排最后。筛选条用它渲染。 */
    public List<Tag> getSelectedFilters() {
        List<Tag> selected = new ArrayList<>();
        for (Tag tag : mTagRegistry) {
            if (mFilterTagIds.contains(tag.getId())) {
                selected.add(tag);
            }
        }
        if (mFilterTagIds.contains(Tag.UNTAGGED_ID)) {
            selected.add(new Tag(Tag.UNTAGGED_ID, "", 0, RepoDbManager.countReposWithoutTags()));
        }
        return selected;
    }

    public void searchRepo(String query) {
        mQueryType = QUERY_TYPE_SEARCH;
        mSearchQueryString = query;
        requery();
    }

    public void queryAllRepo() {
        mQueryType = QUERY_TYPE_QUERY;
        requery();
    }

    private void requery() {
        Cursor cursor = null;
        if (mQueryType == QUERY_TYPE_SEARCH) {
            cursor = RepoDbManager.searchRepo(mSearchQueryString);
        } else {
            cursor = RepoDbManager.queryAllRepo();
        }
        List<Repo> repos = cursor == null ? new ArrayList<>() : Repo.getRepoList(cursor);
        if (cursor != null) cursor.close();

        mTagRegistry = loadTags();
        Map<Long, List<String>> tagMap = RepoDbManager.queryRepoTagMap();
        for (Repo repo : repos) {
            repo.setTagNames(tagMap.get((long) repo.getID()));
        }

        pruneFilterSelection();
        mTotalRepoCount = repos.size();
        List<Repo> visible = applyTagFilter(repos);
        Collections.sort(visible, repoComparator());

        clear();
        addAll(visible);
        notifyDataSetChanged();
        if (mOnListRefreshed != null) {
            mOnListRefreshed.run();
        }
    }

    private List<Tag> loadTags() {
        return Tag.getTagList(RepoDbManager.queryAllTags());
    }

    /**
     * 标签被删掉后，它的 id 不该继续留在筛选里（否则顶部会挂着一个不存在的标签，
     * 而结果集恒为空）。剪掉之后把剩下的写回，避免看不见的陈旧选择。
     */
    private void pruneFilterSelection() {
        if (mFilterTagIds.isEmpty()) return;
        Set<Integer> known = new LinkedHashSet<>();
        known.add(Tag.UNTAGGED_ID);
        for (Tag tag : mTagRegistry) {
            known.add(tag.getId());
        }
        boolean changed = false;
        Iterator<Integer> it = mFilterTagIds.iterator();
        while (it.hasNext()) {
            if (!known.contains(it.next())) {
                it.remove();
                changed = true;
            }
        }
        if (changed) persistFilterSelection();
    }

    private List<Repo> applyTagFilter(List<Repo> repos) {
        if (mFilterTagIds.isEmpty()) return repos;
        boolean wantUntagged = mFilterTagIds.contains(Tag.UNTAGGED_ID);
        List<String> wanted = new ArrayList<>();
        for (Tag tag : mTagRegistry) {
            if (mFilterTagIds.contains(tag.getId())) {
                wanted.add(tag.getName());
            }
        }
        List<Repo> filtered = new ArrayList<>();
        for (Repo repo : repos) {
            List<String> have = repo.getTagNames();
            boolean hit;
            if (mMatchAll) {
                // AND：每个选中条件都要满足。「无标签」和具体标签同时选中必然无解
                // （既有标签又不带任何标签的仓库不存在），由筛选面板互斥掉，这里不再特殊处理。
                hit = (!wantUntagged || have.isEmpty()) && containsAll(have, wanted);
            } else {
                // OR：任一条件命中即可，「无标签」同样是并集的一部分
                hit = (wantUntagged && have.isEmpty()) || containsAny(have, wanted);
            }
            if (hit) {
                filtered.add(repo);
            }
        }
        return filtered;
    }

    /** 空 wanted 视为满足：AND 下「没提要求」不该把仓库筛掉。 */
    private static boolean containsAll(List<String> have, List<String> wanted) {
        for (String wanted1 : wanted) {
            if (!have.contains(wanted1)) return false;
        }
        return true;
    }

    /** 空 wanted 视为不满足：OR 下没有可命中的标签，只剩「无标签」那条分支。 */
    private static boolean containsAny(List<String> have, List<String> wanted) {
        for (String wanted1 : wanted) {
            if (have.contains(wanted1)) return true;
        }
        return false;
    }

    private Comparator<Repo> repoComparator() {
        final int key = mSortKey;
        final boolean desc = mSortDir == SORT_DIR_DESC;
        if (key == SORT_KEY_NAME) {
            return (a, b) -> desc
                ? b.getDisplayName().compareToIgnoreCase(a.getDisplayName())
                : a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        }
        return (a, b) -> {
            Date da = dateOf(a, key);
            Date db = dateOf(b, key);
            if (da == null && db == null) {
                return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
            }
            // 时间缺失恒沉底，不随升降序翻转：老库里这列是 NULL，翻转会把它们顶到最前
            if (da == null) return 1;
            if (db == null) return -1;
            int cmp = da.compareTo(db);
            return desc ? -cmp : cmp;
        };
    }

    private static Date dateOf(Repo repo, int sortKey) {
        return sortKey == SORT_KEY_TIME_ADDED ? repo.getTimeAdded() : repo.getLastCommitDate();
    }

    // ---- 渲染 ----

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = newView(getContext(), parent);
        }
        bindView(convertView, getItem(position));
        return convertView;
    }

    public View newView(Context context, ViewGroup parent) {
        View view = LayoutInflater.from(context).inflate(R.layout.repo_listitem, parent, false);
        RepoListItemHolder holder = new RepoListItemHolder();
        holder.repoTitle = view.findViewById(R.id.repoTitle);
        holder.repoRemote = view.findViewById(R.id.repoRemote);
        holder.commitAuthor = view.findViewById(R.id.commitAuthor);
        holder.commitMsg = view.findViewById(R.id.commitMsg);
        holder.commitTime = view.findViewById(R.id.commitTime);
        holder.authorIcon = view.findViewById(R.id.authorIcon);
        holder.progressContainer = view.findViewById(R.id.progressContainer);
        holder.commitMsgContainer = view.findViewById(R.id.commitMsgContainer);
        holder.progressMsg = view.findViewById(R.id.progressMsg);
        holder.cancelBtn = view.findViewById(R.id.cancelBtn);
        holder.tagRow = view.findViewById(R.id.tagRow);
        holder.tagRowScroll = view.findViewById(R.id.tagRowScroll);
        // HorizontalScrollView 的构造函数在 XML 属性应用完之后才调用 setFocusableInTouchMode(true)，
        // 所以写在布局里的 focusable/focusableInTouchMode="false" 会被它覆盖 —— 必须在这里关掉。
        // 只要行里存在可聚焦后代，AbsListView.onTouchEvent 的 child.hasFocusable() 就为真，
        // 它跳过整行的 press 记账，短按和长按会一起失效（真机 + 模拟器实测）。
        holder.tagRowScroll.setFocusable(false);
        holder.tagRowScroll.setFocusableInTouchMode(false);
        view.setTag(holder);
        return view;
    }

    public void bindView(View view, final Repo repo) {
        if (repo == null) return;
        RepoListItemHolder holder = (RepoListItemHolder) view.getTag();

        holder.repoTitle.setText(repo.getDisplayName());
        holder.repoRemote.setText(repo.getRemoteURL());
        TagChipRenderer.fillTagRow(holder.tagRow, repo.getTagNames());

        if (!repo.getRepoStatus().equals(RepoContract.REPO_STATUS_NULL)) {
            holder.commitMsgContainer.setVisibility(View.GONE);
            holder.progressContainer.setVisibility(View.VISIBLE);
            holder.progressMsg.setText(repo.getRepoStatus());
            holder.cancelBtn.setOnClickListener(v -> {
                repo.deleteRepo(true);
                repo.cancelTask();
            });
        } else if (repo.getLastCommitter() != null) {
            holder.commitMsgContainer.setVisibility(View.VISIBLE);
            holder.progressContainer.setVisibility(View.GONE);

            String date = "";
            if (repo.getLastCommitDate() != null) {
                date = mCommitDateFormatter.format(repo.getLastCommitDate());
            }
            holder.commitTime.setText(date);
            holder.commitMsg.setText(repo.getLastCommitMsg());
            holder.commitAuthor.setText(repo.getLastCommitter());
            holder.authorIcon.setVisibility(View.VISIBLE);
            BasicFunctions.setAvatarImage(holder.authorIcon, repo.getLastCommitterEmail());
        }
    }

    @Override
    public void notifyChanged() {
        mActivity.runOnUiThread(this::requery);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        Repo repo = getItem(position);
        if (repo == null) return;
        if (repo.isExternal() && mActivity.checkAndRequestAccessAllFilesPermission(0)) {
            return;
        }
        Intent intent = new Intent(mActivity, RepoDetailActivity.class);
        intent.putExtra(Repo.TAG, repo);
        mActivity.startActivity(intent);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {
        final Repo repo = getItem(position);
        if (repo == null) return false;
        if (!repo.getRepoStatus().equals(RepoContract.REPO_STATUS_NULL)) return false;
        Context context = getContext();
        if (context instanceof BaseCompatActivity) {
            showRepoOptionsDialog((BaseCompatActivity) context, repo);
        }
        return true;
    }

    private void showRepoOptionsDialog(final BaseCompatActivity context, final Repo repo) {

        // 位置索引要和 R.array.dialog_choose_repo_action_items 一一对应（两种语言同序）；
        // 第 4 项原来是「移动到分组」，现在是「编辑标签」，位置不变。
        BaseCompatActivity.onOptionDialogClicked[] dialog =
            new BaseCompatActivity.onOptionDialogClicked[]{
                () -> showRenameRepoDialog(context, repo),
                () -> showRemoveRepoDialog(context, repo),
                () -> createShortcut(context, repo),
                () -> showEditTagsDialog(context, repo),
                null};
        final String remoteRaw = repo.getRemoteURL();
        final String remoteRawLowerCase = repo.getRemoteURL().toLowerCase();
        boolean repoHasHttpRemote =
            !remoteRawLowerCase.equals("local repository") && remoteRawLowerCase.contains("http");
        if (repoHasHttpRemote) {
            dialog[4] = () -> {

                //remove git extension if present
                String repoUrl = remoteRaw.endsWith(context.getString(R.string.git_extension)) ?
                    remoteRaw.substring(0, remoteRaw.lastIndexOf('.')) : remoteRaw;

                Intent openUrlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl));

                //get activities that open this url
                List<ResolveInfo> activitiesToOpenUrlIntentList =
                    context.getPackageManager().queryIntentActivities(openUrlIntent,
                        PackageManager.MATCH_ALL);

                List<Intent> intentList = new ArrayList<Intent>();

                //Get application info to exclude it from the intent chooser
                ApplicationInfo applicationInfo = context.getApplicationInfo();
                int stringId = applicationInfo.labelRes;
                String applicationName = (stringId == 0) ?
                    applicationInfo.nonLocalizedLabel.toString().toLowerCase() :
                    context.getString(stringId).toLowerCase();

                if (!activitiesToOpenUrlIntentList.isEmpty()) {
                    for (ResolveInfo resolveInfo : activitiesToOpenUrlIntentList) {
                        String packageName = resolveInfo.activityInfo.packageName;
                        //create and add intent from other applications
                        //this way MGit doesn't show up to open the remote url
                        if (!packageName.toLowerCase().contains(applicationName)) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl));
                            intent.setComponent(new ComponentName(packageName,
                                resolveInfo.activityInfo.name));
                            intent.setAction(Intent.ACTION_VIEW);
                            intent.setPackage(packageName);
                            intentList.add(intent);
                        }
                    }
                    if (!intentList.isEmpty()) {
                        String title =
                            String.format(context.getString(R.string.dialog_open_remote_title),
                                repo.getDisplayName());
                        Intent chooserIntent = Intent.createChooser(intentList.remove(0), title);
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                            intentList.toArray(new Parcelable[intentList.size()]));
                        context.startActivity(chooserIntent);
                    } else {
                        Timber.tag(TAG_CLASS).i(context.getString(
                            R.string.dialog_open_remote_no_app_available));
                        Toast.makeText(context, R.string.dialog_open_remote_no_app_available,
                            Toast.LENGTH_LONG).show();
                    }
                }
            };
        }

        List<String> stringList = new ArrayList<>(5);
        stringList.addAll(Arrays.asList(context.getResources().getStringArray(
            R.array.dialog_choose_repo_action_items)));
        stringList.add(context.getString(R.string.label_edit_tags));
        if (repoHasHttpRemote) {
            stringList.add(context.getString(R.string.dialog_open_remote));
        }
        context.showOptionsDialog(R.string.dialog_choose_option,
            stringList.toArray(new String[0]), dialog);
    }

    private void showEditTagsDialog(final BaseCompatActivity context, final Repo repo) {
        TagPickerDialog.show(context,
            R.string.dialog_edit_tags_title,
            this::loadTags,
            Tag.idsOfNames(loadTags(), repo.getTagNames()),
            true,   // 允许就地新建
            false,  // 编辑场景不需要命中数
            false,  // 编辑场景没有「无标签」伪标签，互斥不参与
            tagIds -> RepoDbManager.setRepoTags(repo.getID(),
                Tag.namesOfIds(loadTags(), tagIds)));
    }

    private void createShortcut(BaseCompatActivity context, final Repo repo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            context.showToastMessage(R.string.error_unknown);
            return;
        }
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) {
            Toast.makeText(context, R.string.dialog_open_remote_no_app_available,
                Toast.LENGTH_LONG).show();
            return;
        }
        String shortcutId = String.format(Locale.US, "repo_%d", repo.getID());
        Intent intent = new Intent(context, RepoDetailActivity.class);
        intent.putExtra("repo_id", repo.getID());
        intent.setAction(Intent.ACTION_VIEW);
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(repo.getDisplayName())
            .setLongLabel(repo.getRemoteURL())
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build();
        shortcutManager.requestPinShortcut(shortcutInfo, null);
    }

    private void showRemoveRepoDialog(BaseCompatActivity context, final Repo repo) {
        context.showMessageDialog(R.string.dialog_delete_repo_title,
            R.string.dialog_delete_repo_msg, R.string.label_delete, (dialogInterface, i) -> {
                repo.deleteRepo(true);
                repo.cancelTask();
            });
    }

    private void showRenameRepoDialog(final BaseCompatActivity context, final Repo repo) {
        context.showEditTextDialog(R.string.dialog_rename_repo_title,
            R.string.dialog_rename_repo_hint, R.string.label_rename, newRepoName -> {
                if (!repo.renameRepo(newRepoName)) {
                    context.showToastMessage(R.string.error_rename_repo_fail);
                }
            });
    }

    // ---- 偏好 ----

    private void readPreferences() {
        SharedPreferences prefs = getPrefs();
        mSortKey = indexOfValue(prefs.getString(getString(R.string.pref_key_repo_sort_key),
            SORT_KEY_VALUES[SORT_KEY_NAME]), SORT_KEY_VALUES, SORT_KEY_NAME);
        mSortDir = indexOfValue(prefs.getString(getString(R.string.pref_key_repo_sort_direction),
            SORT_DIR_VALUES[SORT_DIR_ASC]), SORT_DIR_VALUES, SORT_DIR_ASC);
        mMatchAll = prefs.getBoolean(getString(R.string.pref_key_repo_tag_match_all), true);

        // 选中的标签是视图态，存在另一个文件里：BackupManager 全量搬 preference_file_key，
        // 放一起会把「当前筛选」也备份走，还原后面对一个空列表是很糟的体验。
        String csv = getViewStatePrefs().getString(
            getString(R.string.pref_key_repo_filter_tags), "");
        for (String part : csv.split(",")) {
            if (part.trim().isEmpty()) continue;
            try {
                mFilterTagIds.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 手改过偏好文件之类，跳过而不是崩
            }
        }
    }

    private void persistFilterSelection() {
        StringBuilder sb = new StringBuilder();
        for (Integer id : mFilterTagIds) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        getViewStatePrefs().edit()
            .putString(getString(R.string.pref_key_repo_filter_tags), sb.toString()).apply();
    }

    private SharedPreferences getPrefs() {
        return mActivity.getSharedPreferences(getString(R.string.preference_file_key),
            Context.MODE_PRIVATE);
    }

    private SharedPreferences getViewStatePrefs() {
        return mActivity.getSharedPreferences(getString(R.string.repo_view_state_file),
            Context.MODE_PRIVATE);
    }

    private String getString(int resId) {
        return mActivity.getString(resId);
    }

    private static int clamp(int value, int length) {
        return value < 0 ? 0 : (value >= length ? length - 1 : value);
    }

    /** 存储值 -&gt; 常量序号；不认识的值（旧版本写的、手改的）回退到 defaultIndex。 */
    private static int indexOfValue(String stored, String[] values, int defaultIndex) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(stored)) return i;
        }
        return defaultIndex;
    }

    private class RepoListItemHolder {
        public TextView repoTitle;
        public TextView repoRemote;
        public TextView commitAuthor;
        public TextView commitMsg;
        public TextView commitTime;
        public ImageView authorIcon;
        public View progressContainer;
        public View commitMsgContainer;
        public TextView progressMsg;
        public ImageView cancelBtn;
        public LinearLayout tagRow;
        /** 标签行的外层滚动容器；focusable 在 newView 里关掉，见那里的注释。 */
        public View tagRowScroll;
    }
}
