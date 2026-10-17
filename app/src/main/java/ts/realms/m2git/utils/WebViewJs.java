package ts.realms.m2git.utils;

import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

/**
 * WebView 的 JS 调用通道，取代 {@code loadUrl("javascript:…")}。
 *
 * 解决两件事：
 * 1. {@code loadUrl} 调 JS 没有任何回执，页面没装载完时整条调用直接蒸发（「点了没反应」）。
 *    这里改为 {@code evaluateJavascript}，并挂 {@link WebViewClient#onPageFinished} 门控：
 *    页面 ready 前的调用进队列，ready 时按序补发，不再静默丢失。
 * 2. 队列与补发都在主线程发生，与 {@code evaluateJavascript} 的线程要求一致。
 *    调用方须保证在主线程调 {@link #eval(String)}（本仓两处调用点：菜单/按钮点击、
 *    MAsyncTask 的 onPostExecute —— 都已在主线程，后者见 MAsyncTask 的 mainLooper post）。
 *
 * 注意：构造时会 {@code setWebViewClient}，一个 WebView 只能挂这一个通道；
 * JS 异常不会进 eval 的回执（恒为 null），靠 WebChromeClient.onConsoleMessage 的既有日志兜底。
 */
public class WebViewJs {

    private final WebView mView;
    private final List<String> mPending = new ArrayList<>();
    private boolean mPageReady;

    public WebViewJs(WebView view) {
        mView = view;
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                mPageReady = true;
                for (String script : mPending) {
                    evaluateNow(script);
                }
                mPending.clear();
            }
        });
    }

    /** 在主线程调用。页面未 ready 时入队（打日志），ready 后按序补发。 */
    public void eval(String script) {
        if (!mPageReady) {
            Timber.w("WebViewJs: page not ready, queued [%s]", script);
            mPending.add(script);
            return;
        }
        evaluateNow(script);
    }

    private void evaluateNow(String script) {
        mView.evaluateJavascript(script,
            value -> Timber.d("WebViewJs: eval done [%s]", script));
    }
}
