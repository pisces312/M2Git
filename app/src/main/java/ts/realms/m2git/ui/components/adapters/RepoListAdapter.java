package ts.realms.m2git.ui.components.adapters;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.core.models.RepoGroup;
import ts.realms.m2git.local.database.RepoContract;
import ts.realms.m2git.local.database.RepoDbManager;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.screens.main.RepoListActivity;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity;
import ts.realms.m2git.utils.BasicFunctions;

/**
 * Created by sheimi on 8/6/13.
 */
public class RepoListAdapter extends ArrayAdapter<RepoListAdapter.ListItem> implements RepoDbManager.RepoDbObserver,
    AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    private static final int QUERY_TYPE_SEARCH = 0;
    private static final int QUERY_TYPE_QUERY = 1;
    private static final String TAG = RepoListAdapter.class.getSimpleName();

    public static final int SORT_BY_NAME_ASC = 0;
    public static final int SORT_BY_NAME_DESC = 1;
    public static final int SORT_BY_DATE_ASC = 2;
    public static final int SORT_BY_DATE_DESC = 3;

    private final DateFormat mCommitDateFormatter;
    private final RepoListActivity mActivity;
    private int mQueryType = QUERY_TYPE_QUERY;
    private String mSearchQueryString;
    private int mSortMode = SORT_BY_NAME_ASC;
    private final Set<Integer> mExpandedGroups = new HashSet<>();

    public static class ListItem {
        public static final int TYPE_GROUP = 0;
        public static final int TYPE_REPO = 1;

        public int type;
        public Repo repo;
        public RepoGroup group;
        public int repoCount; // for group header
        public boolean isExpanded;

        public static ListItem group(RepoGroup group, int repoCount, boolean isExpanded) {
            ListItem item = new ListItem();
            item.type = TYPE_GROUP;
            item.group = group;
            item.repoCount = repoCount;
            item.isExpanded = isExpanded;
            return item;
        }

        public static ListItem repo(Repo repo) {
            ListItem item = new ListItem();
            item.type = TYPE_REPO;
            item.repo = repo;
            return item;
        }
    }

    public RepoListAdapter(Context context) {
        super(context, 0);
        RepoDbManager.registerDbObserver(RepoContract.RepoEntry.TABLE_NAME, this);
        mActivity = (RepoListActivity) context;
        mCommitDateFormatter = android.text.format.DateFormat.getDateFormat(context);
    }

    public void setSortMode(int sortMode) {
        mSortMode = sortMode;
        requery();
    }

    public int getSortMode() {
        return mSortMode;
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
        switch (mQueryType) {
            case QUERY_TYPE_SEARCH:
                cursor = RepoDbManager.searchRepo(mSearchQueryString);
                break;
            case QUERY_TYPE_QUERY:
                cursor = RepoDbManager.queryAllRepo();
                break;
        }
        List<Repo> repos = Repo.getRepoList(cursor);
        cursor.close();

        // Sort repos
        sortRepos(repos);

        // Build flat list with group headers
        List<ListItem> items = buildGroupedList(repos);

        clear();
        addAll(items);
        notifyDataSetChanged();
    }

    private void sortRepos(List<Repo> repos) {
        Comparator<Repo> comparator;
        switch (mSortMode) {
            case SORT_BY_NAME_DESC:
                comparator = (a, b) -> b.getDisplayName().compareToIgnoreCase(a.getDisplayName());
                break;
            case SORT_BY_DATE_ASC:
                comparator = (a, b) -> {
                    Date da = a.getLastCommitDate();
                    Date db = b.getLastCommitDate();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return da.compareTo(db);
                };
                break;
            case SORT_BY_DATE_DESC:
                comparator = (a, b) -> {
                    Date da = a.getLastCommitDate();
                    Date db = b.getLastCommitDate();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                };
                break;
            case SORT_BY_NAME_ASC:
            default:
                comparator = (a, b) -> a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
                break;
        }
        Collections.sort(repos, comparator);
    }

    private List<ListItem> buildGroupedList(List<Repo> repos) {
        List<ListItem> items = new ArrayList<>();

        // Load all groups
        Map<Integer, RepoGroup> groups = new HashMap<>();
        Map<Integer, List<Repo>> groupRepos = new HashMap<>();
        List<Repo> ungrouped = new ArrayList<>();

        Cursor groupCursor = RepoDbManager.queryAllGroups();
        if (groupCursor != null) {
            groupCursor.moveToFirst();
            while (!groupCursor.isAfterLast()) {
                RepoGroup group = new RepoGroup(groupCursor);
                groups.put(group.getId(), group);
                groupRepos.put(group.getId(), new ArrayList<>());
                groupCursor.moveToNext();
            }
            groupCursor.close();
        }

        // Distribute repos
        for (Repo repo : repos) {
            int gid = repo.getGroupId();
            if (gid > 0 && groups.containsKey(gid)) {
                groupRepos.get(gid).add(repo);
            } else {
                ungrouped.add(repo);
            }
        }

        // Add ungrouped repos first
        if (!ungrouped.isEmpty()) {
            for (Repo repo : ungrouped) {
                items.add(ListItem.repo(repo));
            }
        }

        // Add grouped repos
        List<RepoGroup> sortedGroups = new ArrayList<>(groups.values());
        Collections.sort(sortedGroups, (a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));

        for (RepoGroup group : sortedGroups) {
            List<Repo> groupRepoList = groupRepos.get(group.getId());
            boolean isExpanded = mExpandedGroups.contains(group.getId());
            items.add(ListItem.group(group, groupRepoList != null ? groupRepoList.size() : 0, isExpanded));
            if (isExpanded && groupRepoList != null) {
                for (Repo repo : groupRepoList) {
                    items.add(ListItem.repo(repo));
                }
            }
        }

        return items;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ListItem item = getItem(position);
        if (item.type == ListItem.TYPE_GROUP) {
            return getGroupView(position, convertView, parent);
        }
        return getRepoView(position, convertView, parent);
    }

    private View getGroupView(int position, View convertView, ViewGroup parent) {
        GroupViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.repo_listitem_group, parent, false);
            holder = new GroupViewHolder();
            holder.groupArrow = convertView.findViewById(R.id.groupArrow);
            holder.groupName = convertView.findViewById(R.id.groupName);
            holder.groupCount = convertView.findViewById(R.id.groupCount);
            convertView.setTag(holder);
        } else {
            holder = (GroupViewHolder) convertView.getTag();
        }
        ListItem item = getItem(position);
        holder.groupArrow.setText(item.isExpanded ? "\u25BC" : "\u25B6");
        holder.groupName.setText(item.group.getName());
        holder.groupCount.setText("(" + item.repoCount + ")");
        return convertView;
    }

    private View getRepoView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = newView(getContext(), parent);
        }
        bindView(convertView, position);
        return convertView;
    }

    public View newView(Context context, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.repo_listitem, parent, false);
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
        view.setTag(holder);
        return view;
    }

    public void bindView(View view, int position) {
        RepoListItemHolder holder = (RepoListItemHolder) view.getTag();
        final Repo repo = getItem(position).repo;

        holder.repoTitle.setText(repo.getDisplayName());
        holder.repoRemote.setText(repo.getRemoteURL());

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
        ListItem item = getItem(position);
        if (item.type == ListItem.TYPE_GROUP) {
            int groupId = item.group.getId();
            if (mExpandedGroups.contains(groupId)) {
                mExpandedGroups.remove(groupId);
            } else {
                mExpandedGroups.add(groupId);
            }
            requery();
            return;
        }
        Repo repo = item.repo;
        if (repo.isExternal() && mActivity.checkAndRequestAccessAllFilesPermission(0)) {
            return;
        }
        Intent intent = new Intent(mActivity, RepoDetailActivity.class);
        intent.putExtra(Repo.TAG, repo);
        mActivity.startActivity(intent);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {
        ListItem item = getItem(position);
        if (item.type == ListItem.TYPE_GROUP) return false;
        final Repo repo = item.repo;
        if (!repo.getRepoStatus().equals(RepoContract.REPO_STATUS_NULL)) return false;
        Context context = getContext();
        if (context instanceof BaseCompatActivity) {
            showRepoOptionsDialog((BaseCompatActivity) context, repo);
        }
        return true;
    }

    private void showRepoOptionsDialog(final BaseCompatActivity context, final Repo repo) {

        BaseCompatActivity.onOptionDialogClicked[] dialog =
            new BaseCompatActivity.onOptionDialogClicked[]{
                () -> showRenameRepoDialog(context, repo),
                () -> showRemoveRepoDialog(context, repo),
                () -> createShortcut(context, repo),
                () -> showMoveToGroupDialog(context, repo),
                null};
        // 区分大小写
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
                        Timber.tag(TAG).i(context.getString(R.string.dialog_open_remote_no_app_available));
                        Toast.makeText(context, R.string.dialog_open_remote_no_app_available,
                            Toast.LENGTH_LONG).show();
                    }
                }
            };
        }

        if (repoHasHttpRemote) {
            List<String> stringList = new ArrayList<>(5);
            stringList.addAll(Arrays.asList(context.getResources().getStringArray(R.array.dialog_choose_repo_action_items)));
            stringList.add(context.getString(R.string.dialog_move_to_group));
            stringList.add(context.getString(R.string.dialog_open_remote));
            String[] options_values = stringList.toArray(new String[0]);

            context.showOptionsDialog(R.string.dialog_choose_option, options_values, dialog);
        } else {
            List<String> stringList = new ArrayList<>(4);
            stringList.addAll(Arrays.asList(context.getResources().getStringArray(R.array.dialog_choose_repo_action_items)));
            stringList.add(context.getString(R.string.dialog_move_to_group));
            String[] options_values = stringList.toArray(new String[0]);

            context.showOptionsDialog(R.string.dialog_choose_option, options_values, dialog);
        }
    }

    private void showMoveToGroupDialog(final BaseCompatActivity context, final Repo repo) {
        Cursor cursor = RepoDbManager.queryAllGroups();
        List<RepoGroup> groups = new ArrayList<>();
        if (cursor != null) {
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                groups.add(new RepoGroup(cursor));
                cursor.moveToNext();
            }
            cursor.close();
        }

        List<String> options = new ArrayList<>();
        options.add(context.getString(R.string.group_none));
        for (RepoGroup g : groups) {
            options.add(g.getName());
        }

        final int[] groupIds = new int[options.size()];
        groupIds[0] = 0;
        for (int i = 0; i < groups.size(); i++) {
            groupIds[i + 1] = groups.get(i).getId();
        }

        BaseCompatActivity.onOptionDialogClicked[] listeners = new BaseCompatActivity.onOptionDialogClicked[options.size()];
        for (int i = 0; i < options.size(); i++) {
            final int gid = groupIds[i];
            listeners[i] = () -> {
                RepoDbManager.setRepoGroup(repo.getID(), gid);
            };
        }

        context.showOptionsDialog(R.string.dialog_move_to_group_title,
            options.toArray(new String[0]), listeners);
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
    }

    private static class GroupViewHolder {
        public TextView groupArrow;
        public TextView groupName;
        public TextView groupCount;
    }

}
