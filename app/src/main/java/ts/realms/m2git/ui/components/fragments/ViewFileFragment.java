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

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import timber.log.Timber;
import ts.realms.m2git.R;
import ts.realms.m2git.local.preference.Profile;
import ts.realms.m2git.ui.screens.main.BaseCompatActivity;
import ts.realms.m2git.ui.viewModels.BaseFragment;
import ts.realms.m2git.utils.CodeGuesser;
import ts.realms.m2git.utils.WebViewJs;

/**
 * Created by phcoder on 09.12.15.
 */
public class ViewFileFragment extends BaseFragment {
    private static final String JS_INF = "CodeLoader";
    private WebView mFileContent;
    private WebViewJs mJs;
    private ProgressBar mLoading;
    private File mFile;
    private short mActivityMode = ViewFileActivity.TAG_MODE_NORMAL;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_view_file, container, false);

        mFileContent = v.findViewById(R.id.fileContent);
        mLoading = v.findViewById(R.id.loading);

        String fileName = null;
        if (savedInstanceState != null) {
            fileName = savedInstanceState.getString(ViewFileActivity.TAG_FILE_NAME);
            mActivityMode = savedInstanceState.getShort(ViewFileActivity.TAG_MODE, ViewFileActivity.TAG_MODE_NORMAL);
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

    private void loadFileContent() {
        mFileContent.loadUrl("file:///android_asset/editor.html");
    }

    public File getFile() {
        return mFile;
    }

    public void copyAll() {
        mJs.eval("copy_all();");
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
