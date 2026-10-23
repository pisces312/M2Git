package ts.realms.m2git.ui.screens.commitDiff;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ProgressBar;

import androidx.appcompat.widget.ShareActionProvider;
import androidx.core.view.MenuItemCompat;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.CommitDiffTask;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.local.preference.Profile;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.utils.FsUtils;
import ts.realms.m2git.utils.WebViewJs;

public class CommitDiffActivity extends BaseCompatActivity {

    public final static String OLD_COMMIT = "old commit";
    public final static String NEW_COMMIT = "new commit";
    public final static String SHOW_DESCRIPTION = "show_description";
    /** 仓库相对路径；带它就只看这一个文件的 diff，不带就是整仓。 */
    public final static String DIFF_PATH = "diff_path";
    private final static int REQUEST_SAVE_DIFF = 1;
    private static final String JS_INF = "CodeLoader";
    private static final String HTML_TMPL = "<!doctype html>"
        + "<head>"
        // 资产文件名/版本由 scripts/rebuild-js-assets.sh 固定（与 build.gradle 的版本台账同步）；
        // hljs-rainbow.css 是 hljs 11 的 .hljs-* 前缀主题，配套的 <code class="hljs"> 见 local_commits_diff.js
        + " <script src=\"js/jquery.slim.min.js\"></script>"
        + " <script src=\"js/highlight.min.js\"></script>"
        + " <script src=\"js/local_commits_diff.js\"></script>"
        + " <link type=\"text/css\" rel=\"stylesheet\" href=\"css/hljs-rainbow.css\" />"
        + " <link type=\"text/css\" rel=\"stylesheet\" href=\"css/local_commits_diff.css\" />"
        + "</head><body class=\"%1$s\"></body>";
    private WebView mDiffContent;
    private WebViewJs mJs;
    private ProgressBar mLoading;
    private String mOldCommit;
    private String mNewCommit;
    private String mDiffPath;
    private boolean mShowDescription;
    private Repo mRepo;
    private RevCommit mCommit;
    private List<String> mDiffStrs;
    private List<DiffEntry> mDiffEntries;
    private CommitDiffTask mDiffTask;
    private boolean mShallowBoundary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_diff);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        mDiffContent = findViewById(R.id.fileContent);
        mLoading = findViewById(R.id.loading);

        Bundle extras = getIntent().getExtras();
        mOldCommit = extras.getString(OLD_COMMIT);
        mNewCommit = extras.getString(NEW_COMMIT);
        mShowDescription = extras.getBoolean(SHOW_DESCRIPTION);
        mDiffPath = extras.getString(DIFF_PATH);
        mRepo = (Repo) extras.getSerializable(Repo.TAG);

        if (mDiffPath != null) {
            // 单文件模式标题只放文件名：整仓那套「新 : 旧」在这里是 dircache/filetree 两个
            // 哨兵名，摆出来不如文件名有用（哪两侧的比较由状态页那一行的徽标决定）。
            setTitle(getString(R.string.title_activity_file_diff, new File(mDiffPath).getName()));
        } else {
            String title = Repo.getCommitDisplayName(mNewCommit);
            if (mOldCommit != null)
                title += " : " + Repo.getCommitDisplayName(mOldCommit);
            setTitle(getString(R.string.title_activity_commit_diff) + title);
        }

        loadFileContent();
    }

    private void loadFileContent() {
        mDiffContent.addJavascriptInterface(new CodeLoader(), JS_INF);
        // JS 通道要在 loadDataWithBaseURL 之前挂好，否则错过这唯一一次导航的 onPageFinished
        mJs = new WebViewJs(mDiffContent);
        // WebView 不跟随应用内手工主题，用 body class 给 CSS 一个钩子（浅色主题不需要额外规则）
        String themeClass = Profile.getTheme(this) == 0 ? "theme-light" : "theme-dark";
        mDiffContent.loadDataWithBaseURL("file:///android_asset/",
            String.format(HTML_TMPL, themeClass), "text/html", "utf-8", null);
        WebSettings webSettings = mDiffContent.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mDiffContent.setWebChromeClient(new WebChromeClient() {
            public void onConsoleMessage(String message, int lineNumber,
                                         String sourceID) {
                Timber.tag("MyApplication").d(message + " -- From line " + lineNumber + " of " + sourceID);
            }

            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        mDiffContent.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.diff_commits, menu);
        MenuItem item = menu.findItem(R.id.action_share_diff);
        ShareActionProvider shareActionProvider = (ShareActionProvider) MenuItemCompat.getActionProvider(item);
        final Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri futurePathName = Uri.fromFile(sharedDiffPathName());
        shareIntent.putExtra(Intent.EXTRA_STREAM, futurePathName);
        shareIntent.setData(futurePathName);
        shareIntent.setType("text/x-patch");

        shareActionProvider.setOnShareTargetSelectedListener(new ShareActionProvider.OnShareTargetSelectedListener() {
            public boolean onShareTargetSelected(ShareActionProvider source, Intent intent) {
                try {
                    File diff = sharedDiffPathName();
                    saveDiff(new FileOutputStream(diff));
                } catch (IOException e) {
                    showToastMessage(R.string.alert_file_creation_failure);
                }
                return false;
            }
        });

        shareActionProvider.setShareIntent(shareIntent);
        return true;
    }

    private String formatCommitInfo() {
        PersonIdent committer, author;
        committer = mCommit.getCommitterIdent();
        author = mCommit.getAuthorIdent();
        return "commit " + mNewCommit + "\n"
            + "Author:     " + author.getName() + " <" + author.getEmailAddress() + ">\n"
            + "AuthorDate: " + author.getWhen() + "\n"
            + "Commit:     " + committer.getName() + " <" + committer.getEmailAddress() + ">\n"
            + "CommitDate: " + committer.getWhen() + "\n";
    }

    private void saveDiff(OutputStream fos) throws IOException {
        /* FIXME: LOCK!!! */
        if (mCommit != null) {
            String message;
            fos.write(formatCommitInfo().getBytes());
            fos.write("\n".getBytes());
            message = mCommit.getFullMessage();
            for (String line : message.split("\n", -1)) {
                fos.write(("    " + line + "\n").getBytes());
            }
            fos.write("\n".getBytes());
        }
        for (String str : mDiffStrs) {
            fos.write(str.getBytes());
        }
    }

    private File sharedDiffPathName() {
        // Should we rather use createTempFile?
        String fname = mNewCommit;
        if (mOldCommit != null)
            fname += "_" + mOldCommit;
        return new File(FsUtils.getExternalDir("diff", true), fname + ".diff");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE_DIFF && resultCode == RESULT_OK) {
            Uri diffUri = data.getData();
            try {
                saveDiff(getContentResolver().openOutputStream(diffUri));
            } catch (IOException e) {
                showToastMessage(R.string.alert_file_creation_failure);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_save_diff) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/x-patch")
                .putExtra(Intent.EXTRA_TITLE, Repo.getCommitDisplayName(mNewCommit) + ".diff");
            startActivityForResult(intent, REQUEST_SAVE_DIFF);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class CodeLoader {
        @JavascriptInterface
        public String getDiff(int index) {
            return mDiffStrs.get(index);
        }

        @JavascriptInterface
        public boolean haveCommitInfo() {
            return (mCommit != null);
        }

        @JavascriptInterface
        public String getCommitInfo() {
            if (mCommit == null) {
                return "";
            }

            return formatCommitInfo();
        }

        @JavascriptInterface
        public String getCommitMessage() {
            if (mCommit == null) {
                return "";
            }
            return mCommit.getFullMessage();
        }

        @JavascriptInterface
        public String getChangeType(int index) {
            DiffEntry diff = mDiffEntries.get(index);
            DiffEntry.ChangeType ct = diff.getChangeType();
            return ct.toString();
        }

        @JavascriptInterface
        public String getOldPath(int index) {
            DiffEntry diff = mDiffEntries.get(index);
            String op = diff.getOldPath();
            return op;
        }

        @JavascriptInterface
        public String getNewPath(int index) {
            DiffEntry diff = mDiffEntries.get(index);
            String np = diff.getNewPath();
            return np;
        }

        @JavascriptInterface
        public void getDiffEntries() {
            String oldCommit = mOldCommit != null ? mOldCommit : (mNewCommit + "^");
            mDiffTask = new CommitDiffTask(mRepo, oldCommit,
                mNewCommit, new CommitDiffTask.CommitDiffResult() {
                @Override
                public void pushResult(List<DiffEntry> diffEntries,
                                       List<String> diffStrs, RevCommit commit) {
                    mDiffEntries = diffEntries;
                    mDiffStrs = diffStrs;
                    mCommit = commit;
                    mShallowBoundary = mDiffTask.isShallowBoundary();
                    mLoading.setVisibility(View.GONE);
                    // pushResult 由 MAsyncTask 经 mainLooper post 送达（onPostExecute），已在主线程
                    mJs.eval("notifyEntriesReady();");
                }
            }, mShowDescription, mDiffPath);
            mDiffTask.executeTask();
        }

        @JavascriptInterface
        public boolean isShallowBoundary() {
            return mShallowBoundary;
        }

        @JavascriptInterface
        public String getShallowBoundaryTitle() {
            return getString(R.string.diff_shallow_boundary_title);
        }

        @JavascriptInterface
        public String getShallowBoundaryHint() {
            return getString(R.string.diff_shallow_boundary_hint);
        }

        @JavascriptInterface
        public int getDiffSize() {
            return mDiffEntries.size();
        }

        @JavascriptInterface
        public String getTheme() {
            return Profile.getCodeMirrorTheme(getApplicationContext());
        }
    }

}
