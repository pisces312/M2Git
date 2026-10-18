package ts.realms.m2git.ui.components.views;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

/**
 * 一个能把「内容还能不能横向滚动」如实告诉父容器的 WebView。
 *
 * <p>为什么必须自己报：CodeMirror 的横向溢出滚动发生在 Chromium 内部（DOM 层），
 * WebView 这个 View 本身从不滚动，所以 {@code getScrollX()} 恒为 0、
 * {@code canScrollHorizontally()} 恒为 false、{@code computeHorizontalScrollRange()}
 * 也恒等于 Extent（模拟器实测：内容已经能向右滚时，canH 两个方向仍都是 false）。
 * 结论是「给外面包一层 ScrollView / 加横向滚动条」并不会让 ViewPager 看见可滚动区域。
 *
 * <p>ViewPager 判断「这个手势该不该归孩子」的唯一入口是
 * {@code View.canScrollHorizontally()}，所以覆盖它是让 ViewPager 原生接力生效的最短路径：
 * 内容还能滚时它不拦手势，滚到边界后的下一次手势才由它接管翻页。
 *
 * <p>边界值由 editor.js 经 JavaBridge 线程写入，主线程读取，因此是 volatile。
 */
public class EdgeAwareWebView extends WebView {

    private volatile boolean mCanScrollLeft;
    private volatile boolean mCanScrollRight;

    public EdgeAwareWebView(Context context) {
        super(context);
    }

    public EdgeAwareWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EdgeAwareWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * @param direction > 0 表示想看右边的内容（手指左移），< 0 反之
     */
    @Override
    public boolean canScrollHorizontally(int direction) {
        return direction > 0 ? mCanScrollRight : mCanScrollLeft;
    }

    /** 由 JS 侧调用，只写字段，不碰 UI。 */
    public void setScrollEdges(boolean canScrollLeft, boolean canScrollRight) {
        mCanScrollLeft = canScrollLeft;
        mCanScrollRight = canScrollRight;
    }

    public boolean canScrollLeft() {
        return mCanScrollLeft;
    }

    public boolean canScrollRight() {
        return mCanScrollRight;
    }
}
