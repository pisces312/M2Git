package ts.realms.m2git.ui.components.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.local.preference.FileViewOptions;
import ts.realms.m2git.local.preference.Profile;
import ts.realms.m2git.ui.components.views.EdgeAwareWebView;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.viewModels.BaseFragment;
import ts.realms.m2git.utils.CodeGuesser;
import ts.realms.m2git.utils.WebViewJs;

/**
 * Created by phcoder on 09.12.15.
 */
public class ViewFileFragment extends BaseFragment {
    private static final String JS_INF = "CodeLoader";
    private static final String TAG_VIEW_OPTIONS = "view_options";
    /**
     * 用 EdgeAwareWebView 而不是裸 WebView：横向手势该滚内容还是该翻页，
     * 由它的 canScrollHorizontally() 如实回答，交给 ViewPager 原生判定。
     * 详见 docs/file-viewer-swipe-conflict-design.md。
     */
    private EdgeAwareWebView mFileContent;
    private WebViewJs mJs;
    private ProgressBar mLoading;
    private File mFile;
    private short mActivityMode = ViewFileActivity.TAG_MODE_NORMAL;
    /** 全局默认（设置页「文件显示」）：onCreateView 读一次，onResume 再刷新。 */
    private FileViewOptions mGlobalOptions = new FileViewOptions();
    /** 本页临时覆盖；null 表示跟随全局默认。不落盘，退出页面即失效。 */
    private FileViewOptions mOverride;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_view_file, container, false);

        mFileContent = v.findViewById(R.id.fileContent);
        mLoading = v.findViewById(R.id.loading);
        mGlobalOptions = FileViewOptions.readGlobal(requireContext().getApplicationContext());

        String fileName = null;
        if (savedInstanceState != null) {
            fileName = savedInstanceState.getString(ViewFileActivity.TAG_FILE_NAME);
            mActivityMode = savedInstanceState.getShort(ViewFileActivity.TAG_MODE, ViewFileActivity.TAG_MODE_NORMAL);
            // 旋转/进程重建把本页的临时覆盖带回来；正常退出页面则随 Fragment 一起消失
            Serializable savedOptions = savedInstanceState.getSerializable(TAG_VIEW_OPTIONS);
            if (savedOptions instanceof FileViewOptions) {
                mOverride = (FileViewOptions) savedOptions;
            }
        }
        if (fileName == null) {
            fileName = getArguments().getString(ViewFileActivity.TAG_FILE_NAME);
            mActivityMode = getArguments().getShort(ViewFileActivity.TAG_MODE, ViewFileActivity.TAG_MODE_NORMAL);
        }

        mFile = new File(fileName);
        mFileContent.addJavascriptInterface(new CodeLoader(), JS_INF);
        WebSettings webSettings = mFileContent.getSettings();
        webSettings.setJavaScriptEnabled(true);
        mFileContent.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onConsoleMessage(String message, int lineNumber,
                                         String sourceID) {
                Timber.tag("MyApplication").d(message + " -- From line " + lineNumber + " of " + sourceID);
            }

            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });
        mFileContent.setBackgroundColor(Color.TRANSPARENT);

        // JS 通道要在 loadUrl 之前挂好：onPageFinished 门控从第一次导航就开始生效
        mJs = new WebViewJs(mFileContent);
        loadFileContent();
        return v;
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 本页没有临时覆盖时，跟随设置页的最新值（用户可能在文件页仍留在返回栈里时改了设置）。
        // 有覆盖时不读盘也不推送，避免把用户的临时选择冲掉。
        if (mOverride == null) {
            FileViewOptions latest = FileViewOptions.readGlobal(requireContext().getApplicationContext());
            if (!latest.sameAs(mGlobalOptions)) {
                mGlobalOptions = latest;
                pushViewOptions();
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mOverride != null) {
            outState.putSerializable(TAG_VIEW_OPTIONS, mOverride);
        }
    }

    private void loadFileContent() {
        mFileContent.loadUrl("file:///android_asset/editor.html");
    }

    /**
     * 切回本页时调用：让 CodeMirror 重新量算一次（横向范围 / 行宽缓存）。
     * 实测不会改变当前滚动位置，也不会造成可见的闪动。
     */
    public void refreshView() {
        if (mJs != null) {
            mJs.eval("refreshEditor();");
        }
    }

    public File getFile() {
        return mFile;
    }

    public void copyAll() {
        mJs.eval("copy_all();");
    }

    /** 当前生效的显示选项（有临时覆盖时是覆盖值，否则是全局默认），返回副本。 */
    public FileViewOptions getViewOptions() {
        return currentOptions().copy();
    }

    /** 全局默认值，供「恢复默认」后回填弹窗控件与判断是否与全局一致。 */
    public FileViewOptions getGlobalViewOptions() {
        return mGlobalOptions.copy();
    }

    /** 本页临时覆盖：只改本页，不写偏好。 */
    public void setViewOptions(FileViewOptions options) {
        mOverride = options.copy();
        pushViewOptions();
    }

    /** 丢弃本页覆盖，回到全局默认。 */
    public void restoreDefaultViewOptions() {
        mOverride = null;
        mGlobalOptions = FileViewOptions.readGlobal(requireContext().getApplicationContext());
        pushViewOptions();
    }

    private FileViewOptions currentOptions() {
        return mOverride != null ? mOverride : mGlobalOptions;
    }

    private void pushViewOptions() {
        if (mJs != null) {
            mJs.eval("applyViewOptions(" + currentOptions().toJson() + ");");
        }
    }

    public void setLanguage(String lang) {
        // JSONObject.quote 负责转义与引号（null 会得到 JS 字面量 null，正好是「无语言」的语义；
        // 旧写法 String.format("setLang('%s')", null) 会拼出 setLang('null')，反而是错的）
        mJs.eval("setLang(" + JSONObject.quote(lang) + ");");
    }

    @Override
    public void reset() {
    }

    @Override
    public BaseCompatActivity.OnBackClickListener getOnBackClickListener() {
        return new BaseCompatActivity.OnBackClickListener() {
            @Override
            public boolean onClick() {
                return false;
            }
        };
    }

    private void showUserError(Throwable e, final int errorMessageId) {
        Timber.e(e);
        getActivity().runOnUiThread(() -> ((BaseCompatActivity) getActivity()).
            showMessageDialog(R.string.dialog_error_title, getString(errorMessageId)));
    }

    private class CodeLoader {

        private String mCode;

        @JavascriptInterface
        public String getCode() {
            return mCode;
        }

        @JavascriptInterface
        public void copy_all(final String content) {
            ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("mgit", content);
            clipboard.setPrimaryClip(clip);
        }

        @JavascriptInterface()
        public void loadCode() {
            Thread thread = new Thread(() -> {
                try {
                    mCode = FileUtils.readFileToString(mFile);
                } catch (IOException e) {
                    showUserError(e, R.string.error_can_not_open_file);
                }
                display();
            });
            thread.start();
        }

        @JavascriptInterface
        public String getTheme() {
            return Profile.getCodeMirrorTheme(getActivity());
        }

        /**
         * 页面初始化时主动拉取。刻意只读字段、不碰偏好文件：本方法由 WebView 的 JS 线程调用，
         * 而全局值已在 onCreateView / onResume 读过，这里再做磁盘 IO 没有必要。
         */
        @JavascriptInterface
        public String getViewOptions() {
            return currentOptions().toJson();
        }

        /**
         * CodeMirror 的横向滚动边界（editor.js 在 scroll 与选项变更后上报）。
         * 由 WebView 的 JavaBridge 线程调用，只写 volatile 字段，不碰 UI——
         * ViewPager 会在主线程通过 WebView.canScrollHorizontally() 读这两个值。
         */
        @JavascriptInterface
        public void onScrollEdges(boolean canScrollLeft, boolean canScrollRight) {
            mFileContent.setScrollEdges(canScrollLeft, canScrollRight);
        }

        private void display() {
            getActivity().runOnUiThread(() -> {
                String lang;
                if (mActivityMode == ViewFileActivity.TAG_MODE_SSH_KEY) {
                    lang = null;
                } else {
                    lang = CodeGuesser.guessCodeType(mFile.getName());
                }
                mJs.eval("setLang(" + JSONObject.quote(lang) + ");");
                mLoading.setVisibility(View.INVISIBLE);
                mJs.eval("display();");
            });
        }
    }
}
