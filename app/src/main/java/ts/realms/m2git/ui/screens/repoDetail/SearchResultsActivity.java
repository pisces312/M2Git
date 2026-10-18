package ts.realms.m2git.ui.screens.repoDetail;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.ui.components.fragments.ViewFileActivity;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.utils.FsUtils;

/**
 * 全仓库递归搜索结果页。设计见 docs/file-search-design.md。
 *
 * 由 RepoDetailActivity 的「在仓库中搜索…」对话框经 startActivityForResult 启动：
 * 点目录结果会 setResult 把路径回传给详情页跳目录；点文件结果就地打开查看。
 *
 * 搜索跑在 static daemon 单线程池上（仿 FilesListAdapter.LISTING_POOL：activity 重建
 * 不留线程，闲时自杀），{@link #mCancelled} 在 onDestroy 置位即取消。全程纯文件系统，
 * 不经过 jgit。
 */
public class SearchResultsActivity extends BaseCompatActivity {

    public static final String EXTRA_QUERY = "query";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_CASE_SENSITIVE = "case_sensitive";
    public static final String EXTRA_GLOB = "glob";
    /** 点目录结果时回传给 RepoDetailActivity 的 extra：目录绝对路径。 */
    public static final String EXTRA_RESULT_DIR = "result_dir";

    public static final int MODE_NAME = 0;
    public static final int MODE_CONTENT = 1;

    /** 内容搜索单文件上限：更大的文件按二进制处理直接跳过。 */
    private static final long MAX_SEARCHABLE_SIZE = 1024 * 1024;
    /** 结果条数上限：防爆闸，截断时状态行会提示。 */
    private static final int MAX_RESULTS = 500;

    private static final ThreadPoolExecutor SEARCH_POOL = new ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
        r -> {
            Thread t = new Thread(r, "mgit-repo-search");
            t.setDaemon(true);
            return t;
        });

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<SearchResult> mResults = new ArrayList<>();
    private SearchResultAdapter mAdapter;
    private ProgressBar mProgress;
    private TextView mStatus;
    private volatile boolean mCancelled;
    private Repo mRepo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search_results);
        mRepo = (Repo) getIntent().getSerializableExtra(Repo.TAG);
        String query = getIntent().getStringExtra(EXTRA_QUERY);
        if (mRepo == null || query == null || query.trim().isEmpty()) {
            finish();
            return;
        }
        setTitle(query);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mProgress = findViewById(R.id.searchProgress);
        mStatus = findViewById(R.id.searchStatus);
        ListView listView = findViewById(R.id.searchResultList);
        mAdapter = new SearchResultAdapter();
        listView.setAdapter(mAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onResultClick(mResults.get(position)));

        int mode = getIntent().getIntExtra(EXTRA_MODE, MODE_NAME);
        boolean caseSensitive = getIntent().getBooleanExtra(EXTRA_CASE_SENSITIVE, false);
        String glob = getIntent().getStringExtra(EXTRA_GLOB);
        runSearch(query.trim(), mode, caseSensitive, glob);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        mCancelled = true;
        super.onDestroy();
    }

    private void runSearch(final String query, final int mode, final boolean caseSensitive,
                           final String glob) {
        final File root = mRepo.getDir();
        final PathMatcher globMatcher = buildGlobMatcher(glob);
        SEARCH_POOL.execute(() -> {
            boolean truncated = search(root, query, mode, caseSensitive, globMatcher);
            mMainHandler.post(() -> {
                if (mCancelled) return;
                mProgress.setVisibility(View.GONE);
                int count = mResults.size();
                if (count == 0) {
                    mStatus.setText(R.string.search_no_results);
                } else if (truncated) {
                    mStatus.setText(getString(R.string.search_result_truncated, count));
                } else {
                    mStatus.setText(getString(R.string.search_result_count, count));
                }
                mAdapter.notifyDataSetChanged();
            });
        });
    }

    /** @return true = 达到 {@link #MAX_RESULTS} 被截断。 */
    private boolean search(File root, String query, int mode, boolean caseSensitive,
                           PathMatcher globMatcher) {
        final String needle = caseSensitive ? query : query.toLowerCase(Locale.US);
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && !mCancelled) {
            File[] files = stack.pop().listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (mCancelled) return false;
                String name = file.getName();
                if (".git".equals(name)) continue;
                if (file.isDirectory()) {
                    stack.push(file);
                    if (mode == MODE_NAME && nameMatches(name, needle, caseSensitive)) {
                        if (!addResult(new SearchResult(file, true, -1, null))) return true;
                    }
                    continue;
                }
                if (globMatcher != null && !globMatcher.matches(Paths.get(name))) continue;
                if (mode == MODE_NAME) {
                    if (nameMatches(name, needle, caseSensitive)
                        && !addResult(new SearchResult(file, false, -1, null))) {
                        return true;
                    }
                } else {
                    if (searchFileContent(file, needle, caseSensitive)) return true;
                }
            }
        }
        return false;
    }

    private static boolean nameMatches(String name, String needle, boolean caseSensitive) {
        String haystack = caseSensitive ? name : name.toLowerCase(Locale.US);
        return haystack.contains(needle);
    }

    /** @return true = 达到结果上限。 */
    private boolean searchFileContent(File file, String needle, boolean caseSensitive) {
        if (file.length() > MAX_SEARCHABLE_SIZE) return false;
        String mime = FsUtils.getMimeType(file);
        if (!FsUtils.isTextMimeType(mime) && !file.getName().toLowerCase(Locale.US).endsWith(".md")) {
            return false;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (mCancelled) return false;
                String haystack = caseSensitive ? line : line.toLowerCase(Locale.US);
                if (haystack.contains(needle)) {
                    String preview = line.trim();
                    if (preview.length() > 120) {
                        preview = preview.substring(0, 120);
                    }
                    if (!addResult(new SearchResult(file, false, lineNumber, preview))) return true;
                }
            }
        } catch (Exception e) {
            // 读不了的文件（权限 / 中途被删）跳过，不让单个文件炸掉整轮搜索
            Timber.e(e, "search content failed: %s", file.getAbsolutePath());
        }
        return false;
    }

    /** @return false = 达到 {@link #MAX_RESULTS}，调用方应立即停止并报告截断。 */
    private boolean addResult(SearchResult result) {
        mResults.add(result);
        return mResults.size() < MAX_RESULTS;
    }

    private static PathMatcher buildGlobMatcher(String glob) {
        if (glob == null || glob.trim().isEmpty()) return null;
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob.trim());
        } catch (IllegalArgumentException e) {
            // 非法 glob（如 "[abc"）按无过滤处理，而不是让搜索无声失败
            Timber.e(e, "invalid glob: %s", glob);
            return null;
        }
    }

    private void onResultClick(SearchResult result) {
        if (result.isDirectory) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_DIR,
                result.file.getAbsolutePath()));
            finish();
            return;
        }
        String mime = FsUtils.getMimeType(result.file);
        boolean isMdFile = result.file.getName().toLowerCase(Locale.US).endsWith(".md");
        if (FsUtils.isTextMimeType(mime) || isMdFile) {
            Intent intent = new Intent(this, ViewFileActivity.class);
            intent.putExtra(ViewFileActivity.TAG_FILE_NAME, result.file.getAbsolutePath());
            intent.putExtra(Repo.TAG, mRepo);
            startActivity(intent);
            return;
        }
        try {
            FsUtils.openFile(this, result.file);
        } catch (ActivityNotFoundException e) {
            Timber.e(e);
            showMessageDialog(R.string.dialog_error_title,
                getString(R.string.error_can_not_open_file));
        }
    }

    private static final class SearchResult {
        final File file;
        final boolean isDirectory;
        final int lineNumber;      // 内容模式命中行号；文件名模式为 -1
        final String linePreview;

        SearchResult(File file, boolean isDirectory, int lineNumber, String linePreview) {
            this.file = file;
            this.isDirectory = isDirectory;
            this.lineNumber = lineNumber;
            this.linePreview = linePreview;
        }
    }

    private final class SearchResultAdapter extends ArrayAdapter<SearchResult> {

        SearchResultAdapter() {
            super(SearchResultsActivity.this, 0, mResults);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.list_item_search_result, parent, false);
            }
            TextView pathView = convertView.findViewById(R.id.resultPath);
            TextView previewView = convertView.findViewById(R.id.resultPreview);
            SearchResult item = getItem(position);
            pathView.setText(FsUtils.getRelativePath(item.file, mRepo.getDir()));
            if (item.lineNumber > 0) {
                previewView.setText(getString(R.string.search_result_line_preview,
                    item.lineNumber, item.linePreview));
            } else if (item.isDirectory) {
                previewView.setText(R.string.search_result_folder);
            } else {
                previewView.setText(item.file.getName());
            }
            return convertView;
        }
    }
}
