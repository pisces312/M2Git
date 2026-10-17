package ts.realms.m2git.ui.components.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ts.realms.m2git.R;
import ts.realms.m2git.local.preference.Profile;

/**
 * Created by sheimi on 8/18/13.
 *
 * 目录条目的列表。排序（依据 / 方向 / 目录优先）在 {@link #setDir(File)} 里生效，
 * 设置存进 repo_view_state，与仓库列表的排序偏好同一个文件、同一套存法。
 *
 * 列目录 + 逐项 stat + 排序跑在 {@link #LISTING_POOL} 上（见 {@link #setDir(File)}），
 * 只有「清空」和「装结果」两步在主线程。
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

    /**
     * 列目录的单线程池。static 共享而不是每实例一个：adapter 随 fragment 重建，
     * 实例级线程池会留下永不退出的线程；corePoolSize=0 + 30s keepAlive 让它闲下来就自杀，
     * daemon 保证任何情况下都不拖住进程。单线程 = 同一时刻只有一轮 stat 在打盘。
     */
    private static final ThreadPoolExecutor LISTING_POOL = new ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
        r -> {
            Thread t = new Thread(r, "mgit-dir-listing");
            t.setDaemon(true);
            return t;
        });

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 每次 {@link #setDir(File)} 递增。后台结果落地前再比对一次，连续点上翻 / 快速改排序时，
     * 过期那一轮直接丢弃 —— 没有它，慢的那一轮会把上一个目录的内容盖到当前目录上。
     */
    private final AtomicInteger mListingToken = new AtomicInteger();

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
     * 设置都走它。
     *
     * 主线程只留两步：立刻清空旧条目、把排好序的结果装进 adapter。{@code listFiles()} 与
     * 每条目一次的 stat 挪到 {@link #LISTING_POOL} —— 这两项都随条目数线性增长，而调用点全在
     * 主线程（{@code FileExplorerActivity} 的 onCreate / onActivityResult / 点「上一层」的
     * click handler、{@code FilesFragment.setCurrentDir}），以前进大目录就是实打实掉帧。
     * 换目录的观感不变：清空仍然是同步的。
     *
     * 排序三个值在提交后台前拍成局部快照，后台不读可变字段 —— 否则「后台正在按旧设置 stat、
     * 用户同时改设置」会读出半新半旧的组合。
     */
    public void setDir(File dir) {
        mDir = dir;
        clear();
        notifyDataSetChanged();

        final int token = mListingToken.incrementAndGet();
        final int key = mSortKey;
        final int direction = mSortDir;
        final boolean dirsFirst = mDirsFirst;

        LISTING_POOL.execute(() -> {
            List<Entry> entries = readEntries(dir, key);
            // ArrayList.sort 走 TimSort，是稳定的：名称只差大小写的条目仍保持磁盘返回的相对顺序
            entries.sort(entryComparator(key, direction, dirsFirst));
            final File[] files = new File[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                files[i] = entries.get(i).file;
            }
            mMainHandler.post(() -> {
                // 已经有更新的一轮（换目录 / 又改了排序）在跑或跑完，这一轮的结果直接丢
                if (token != mListingToken.get()) return;
                addAll(Arrays.asList(files));
                notifyDataSetChanged();
            });
        });
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

    /** 一轮列举的快照：目录标志与排序值都在后台线程各取一次 syscall，比较器里不再碰文件系统。 */
    private static final class Entry {
        final File file;
        final boolean dir;
        final long sortValue;

        Entry(File file, boolean dir, long sortValue) {
            this.file = file;
            this.dir = dir;
            this.sortValue = sortValue;
        }
    }

    private Comparator<Entry> entryComparator(int key, int direction, boolean dirsFirst) {
        final boolean desc = direction == SORT_DIR_DESC;
        return (a, b) -> {
            // 目录优先是「分块」而不是排序值，所以不跟着升降序翻转：翻过去就变成了
            // 「文件优先」，那不是勾了目录优先 + 降序的人想要的结果。
            if (dirsFirst && a.dir != b.dir) {
                return a.dir ? -1 : 1;
            }
            if (key == SORT_KEY_NAME) {
                int cmp = compareByName(a.file, b.file);
                return desc ? -cmp : cmp;
            }
            long ta = a.sortValue, tb = b.sortValue;
            // 读不到值恒沉底，同样不随升降序翻转：翻转会把整批读不到的条目顶到最前
            if (ta == VALUE_UNKNOWN || tb == VALUE_UNKNOWN) {
                if (ta == tb) return compareByName(a.file, b.file);
                return ta == VALUE_UNKNOWN ? 1 : -1;
            }
            int cmp = ta != tb ? Long.compare(ta, tb) : compareByName(a.file, b.file);
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
     * 列目录并把每个条目的「是否目录 + 排序值」一次取齐，全在后台线程。
     * 排序前先把值读出来存进 {@link Entry} —— 比较器里绝不 stat：一次排序要比 O(n log n) 回，
     * 每条目预取一次就够。按名称排序时排序值那一趟整个跳过，但 {@code isDirectory()} 仍要取：
     * 目录优先是默认开的，以前它在比较器里被反复 stat，是这条路径上被漏掉的那笔开销。
     */
    private List<Entry> readEntries(File dir, int key) {
        File[] files = (mFileFilter == null) ? dir.listFiles() : dir.listFiles(mFileFilter);
        // this is to fix a bug：目录已被删除 / 无权限时 listFiles 返回 null，按空目录处理
        if (files == null) {
            return new ArrayList<>();
        }
        List<Entry> entries = new ArrayList<>(files.length);
        for (File file : files) {
            long value = key == SORT_KEY_NAME ? 0L : readSortValue(file, key);
            entries.add(new Entry(file, file.isDirectory(), value));
        }
        return entries;
    }

    private long readSortValue(File file, int key) {
        if (key == SORT_KEY_MODIFIED) {
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
