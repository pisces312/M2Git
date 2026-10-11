package ts.realms.m2git.ui.components.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import ts.realms.m2git.R;
import ts.realms.m2git.local.preference.Profile;

/**
 * Created by sheimi on 8/18/13.
 *
 * 目录条目的列表。排序（依据 / 方向 / 目录优先）在 {@link #setDir(File)} 里生效，
 * 设置存进 repo_view_state，与仓库列表的排序偏好同一个文件、同一套存法。
 */
public class FilesListAdapter extends ArrayAdapter<File> {

    public static final int SORT_KEY_NAME = 0;
    public static final int SORT_KEY_MODIFIED = 1;
    public static final int SORT_KEY_SIZE = 2;
    public static final int SORT_DIR_ASC = 0;
    public static final int SORT_DIR_DESC = 1;

    /**
     * 偏好里存稳定字符串而不是位置索引：选项数组以后平移不会把用户的设置指到别的键上。
     * 顺序与 SORT_KEY_* / SORT_DIR_* 常量一一对应，也正是排序面板里的选项顺序。
     * 不认识的值读不回来，回退到按名称（{@link #indexOfValue}）。第三项曾经是「创建时间」，
     * 实测 {@code BasicFileAttributes.creationTime()} 在 Android 上恒等于
     * {@code lastModifiedTime()}（ext4 与 FUSE 两条路径都验过），等于它就没意义，换成了大小。
     */
    private static final String[] SORT_KEY_VALUES = {"name", "modified", "size"};
    private static final String[] SORT_DIR_VALUES = {"asc", "desc"};

    /** 读不到排序值时的哨兵值。时间戳恒 > 0，大小 0 是合法值（空文件），所以 -1 不会撞车。 */
    private static final long VALUE_UNKNOWN = -1L;

    private final FileFilter mFileFilter;

    /** 本轮装进 adapter 的目录，改排序设置后要按它重排一次。 */
    private File mDir;
    private int mSortKey = SORT_KEY_NAME;
    private int mSortDir = SORT_DIR_ASC;
    /** 默认 true：保持本 app 一直以来的观感，也和绝大多数文件浏览器一致。 */
    private boolean mDirsFirst = true;
    /** 本轮预取的排序值；只在按大小 / 时间排序时填充。 */
    private final Map<File, Long> mSortValues = new HashMap<>();

    public FilesListAdapter(Context context, FileFilter fileFilter) {
        super(context, 0);
        mFileFilter = fileFilter;
        readPreferences();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        LayoutInflater inflater = LayoutInflater.from(getContext());
        FilesListItemHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_files, parent,
                false);
            holder = new FilesListItemHolder();
            holder.fileTitle = convertView
                .findViewById(R.id.fileTitle);
            holder.fileIcon = convertView
                .findViewById(R.id.fileIcon);
            convertView.setTag(holder);
        } else {
            holder = (FilesListItemHolder) convertView.getTag();
        }
        File item = getItem(position);
        holder.fileTitle.setText(item.getName());
        if (item.isDirectory()) {
            holder.fileIcon.setImageResource(Profile.getStyledResource(getContext(), R.attr.ic_folder_fl));
        } else {
            holder.fileIcon.setImageResource(Profile.getStyledResource(getContext(), R.attr.ic_file_fl));
        }
        // set if selected
        if (convertView.isSelected()) {
            convertView.setBackgroundColor(convertView.getContext().getResources().getColor(R.color.pressed_sgit));
        } else {
            convertView.setBackgroundColor(convertView.getContext().getResources().getColor(android.R.color.transparent));
        }
        return convertView;
    }

    /**
     * 重新列目录并按当前设置排序。这是本类唯一的装数据入口：换目录、新建文件/目录、改排序
     * 设置都走它。改设置时选择「重新列一次盘」而不是原地重排缓存，代价是多一次 listFiles
     * 和一轮 stat，好处是不用维护「缓存条目 vs 磁盘条目」这对账，而且 pull/checkout 之后
     * 立刻能看到新的修改时间。
     */
    public void setDir(File dir) {
        mDir = dir;
        clear();
        File[] files = null;
        if (mFileFilter == null) {
            files = dir.listFiles();
        } else {
            files = dir.listFiles(mFileFilter);
        }
        // this is to fix a bug
        if (files == null) {
            files = new File[0];
        }
        prefetchSortValues(files);
        // Object[] 上的 Arrays.sort 是归并（稳定），名称只剩大小写差异的条目会保持磁盘返回的相对顺序
        Arrays.sort(files, fileComparator());
        addAll(files);
        notifyDataSetChanged();
    }

    // ---- 排序设置 ----

    public int getSortKey() {
        return mSortKey;
    }

    public int getSortDirection() {
        return mSortDir;
    }

    public boolean isDirsFirst() {
        return mDirsFirst;
    }

    /** 存偏好 + 重排当前目录。还没列过目录时只存，等首次 {@link #setDir(File)} 自然生效。 */
    public void setSortSettings(int sortKey, int sortDir, boolean dirsFirst) {
        mSortKey = clamp(sortKey, SORT_KEY_VALUES.length);
        mSortDir = clamp(sortDir, SORT_DIR_VALUES.length);
        mDirsFirst = dirsFirst;
        SharedPreferences.Editor editor = getViewStatePrefs().edit();
        editor.putString(prefKey(R.string.pref_key_file_sort_key), SORT_KEY_VALUES[mSortKey]);
        editor.putString(prefKey(R.string.pref_key_file_sort_direction), SORT_DIR_VALUES[mSortDir]);
        editor.putBoolean(prefKey(R.string.pref_key_file_dirs_first), mDirsFirst);
        editor.apply();
        if (mDir != null) {
            setDir(mDir);
        }
    }

    private Comparator<File> fileComparator() {
        final int key = mSortKey;
        final boolean desc = mSortDir == SORT_DIR_DESC;
        final boolean dirsFirst = mDirsFirst;
        return (a, b) -> {
            // 目录优先是「分块」而不是排序值，所以不跟着升降序翻转：翻过去就变成了
            // 「文件优先」，那不是勾了目录优先 + 降序的人想要的结果。
            if (dirsFirst && a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            if (key == SORT_KEY_NAME) {
                int cmp = compareByName(a, b);
                return desc ? -cmp : cmp;
            }
            long ta = valueOf(a), tb = valueOf(b);
            // 读不到值恒沉底，同样不随升降序翻转：翻转会把整批读不到的条目顶到最前
            if (ta == VALUE_UNKNOWN || tb == VALUE_UNKNOWN) {
                if (ta == tb) return compareByName(a, b);
                return ta == VALUE_UNKNOWN ? 1 : -1;
            }
            int cmp = ta != tb ? Long.compare(ta, tb) : compareByName(a, b);
            return desc ? -cmp : cmp;
        };
    }

    /**
     * 按文件名比，忽略大小写。
     * 原来这里比的是 {@code File.toString()}（全路径、区分大小写）：同目录的条目父路径必然
     * 相同，比全路径等于白比，还把大小写搬进了顺序（A.txt 排在 a.txt 之前）。
     * 忽略大小写后如果只剩大小写差异，再用精确比较兜一下，顺序才不依赖 listFiles 的返回序。
     */
    private static int compareByName(File a, File b) {
        String na = a.getName();
        String nb = b.getName();
        int cmp = na.compareToIgnoreCase(nb);
        return cmp != 0 ? cmp : na.compareTo(nb);
    }

    /**
     * 排序前一次性把值读出来存着 —— Comparator 里绝不 stat：一次排序要比 O(n log n) 回，
     * 每个条目预取一次就够。按名称排序时整轮跳过，一个额外 syscall 都不发。
     */
    private void prefetchSortValues(File[] files) {
        mSortValues.clear();
        if (mSortKey == SORT_KEY_NAME) return;
        for (File file : files) {
            mSortValues.put(file, readSortValue(file));
        }
    }

    private long readSortValue(File file) {
        if (mSortKey == SORT_KEY_MODIFIED) {
            long modified = file.lastModified();
            // 0 = 读不到（文件已消失 / 无权限 / 老库没这列），交给比较器沉底
            return modified > 0 ? modified : VALUE_UNKNOWN;
        }
        // length() 读不到时也返回 0，与「空文件」无法区分 —— 这里接受这个混淆：两者都该排在
        // 最前，代价只是少一次沉底。
        // 目录的 length() 是文件系统实现细节（ext4 常见 4096、FUSE 上常是 0），不是内容大小；
        // 算的是「这个条目本身」。目录优先默认开着，所以正常看不到这个情形。
        return file.length();
    }

    private long valueOf(File file) {
        Long cached = mSortValues.get(file);
        return cached == null ? VALUE_UNKNOWN : cached;
    }

    // ---- 偏好 ----

    private void readPreferences() {
        SharedPreferences prefs = getViewStatePrefs();
        mSortKey = indexOfValue(prefs.getString(prefKey(R.string.pref_key_file_sort_key),
            SORT_KEY_VALUES[SORT_KEY_NAME]), SORT_KEY_VALUES, SORT_KEY_NAME);
        mSortDir = indexOfValue(prefs.getString(prefKey(R.string.pref_key_file_sort_direction),
            SORT_DIR_VALUES[SORT_DIR_ASC]), SORT_DIR_VALUES, SORT_DIR_ASC);
        mDirsFirst = prefs.getBoolean(prefKey(R.string.pref_key_file_dirs_first), true);
    }

    /**
     * 视图态，不是设置：和仓库列表的排序键、标签筛选同放 repo_view_state，
     * BackupManager 全量搬 preference_file_key，放那边会把「当前怎么看目录」一起备份走。
     */
    private SharedPreferences getViewStatePrefs() {
        return getContext().getSharedPreferences(prefKey(R.string.repo_view_state_file),
            Context.MODE_PRIVATE);
    }

    private String prefKey(int resId) {
        return getContext().getString(resId);
    }

    /**
     * clamp / indexOfValue 与 RepoListAdapter 里的同名私有方法逻辑一致。两处各留一份而不是
     * 抽 util：加起来 10 行，为它开个公共类不划算 —— 改语义时记得两边一起改。
     */
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

    private static class FilesListItemHolder {
        public TextView fileTitle;
        public ImageView fileIcon;
    }
}
