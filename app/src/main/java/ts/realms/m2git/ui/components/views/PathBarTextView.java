package ts.realms.m2git.ui.components.views;

import android.content.Context;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * 单行路径文本：一行放不下时**不是**从一头简单截断，而是保留「头部 + … + 最后一级」。
 *
 * <p>理由是路径两端信息量最高：最后一级回答「我在哪」，头部回答「在哪个模块下」
 * （{@code app/}、{@code lib/}、{@code core/}）；中段通常只是重复的包名层级。
 * 只从左边截断（{@code ellipsize=start}）会让深路径全看起来一样，只从右边截断则
 * 直接丢掉"我在哪"。最后一级本身就超过可用宽度时，才退化成尾部省略
 * （{@code …/最后一级的末段}）。
 *
 * <p>显示文本由可用宽度决定，所以在 {@link #onMeasure} 里算：宽度为 0（0dp + weight）
 * 时也能拿到测量规格给出的确切宽度。设计见 docs/repo-detail-path-bar-design.md。
 */
public class PathBarTextView extends AppCompatTextView {

    private static final String ELLIPSIS = "…";

    /** 完整路径；显示文本是它的截断版，注意两者可能不同。 */
    private String mPath = "";

    public PathBarTextView(Context context) {
        this(context, null);
    }

    public PathBarTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public PathBarTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 设置完整路径；实际显示文本由下一次 measure 按可用宽度算出。 */
    public void setPath(String path) {
        mPath = path == null ? "" : path;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int avail = MeasureSpec.getSize(widthMeasureSpec)
            - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (avail > 0) {
            CharSequence shown = elide(mPath, avail);
            // 只在真的变了才 setText：本方法在每次 measure 都会被调用，
            // 无条件 setText 会反复 requestLayout，白烧一帧布局
            if (!TextUtils.equals(getText(), shown)) {
                setText(shown);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private CharSequence elide(String path, int avail) {
        TextPaint paint = getPaint();
        if (paint.measureText(path) <= avail) {
            return path;
        }
        int slash = path.lastIndexOf('/');
        // tail 带前导 '/'（单段路径没有 '/'，整条就是 tail）
        String head = slash > 0 ? path.substring(0, slash) : "";
        String tail = slash > 0 ? path.substring(slash) : path;
        float tailWidth = paint.measureText(tail);
        if (tailWidth >= avail) {
            // 最后一级自己都放不下：保尾，至少看得到最深一层的末段
            return TextUtils.ellipsize(path, paint, avail, TextUtils.TruncateAt.START);
        }
        float ellipsisWidth = paint.measureText(ELLIPSIS);
        float headAvail = avail - tailWidth - ellipsisWidth;
        if (headAvail <= 0) {
            return ellipsisWidth + tailWidth <= avail ? ELLIPSIS + tail : tail;
        }
        return TextUtils.concat(
            TextUtils.ellipsize(head, paint, headAvail, TextUtils.TruncateAt.END), tail);
    }
}
