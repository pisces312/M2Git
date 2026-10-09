package ts.realms.m2git.ui.components.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import java.util.List;

/**
 * 标签 chip 的统一绘制入口：仓库条目底部的展示 chip，与顶部筛选条里可点掉的已选 chip
 * 共用同一套配色和圆角，让同一名标签在两处看起来就是同一个东西。
 *
 * 色相由标签名的 hashCode 确定性派生，所以库里不需要 color 列，同名标签恒同色。
 * 亮/暗主题靠 setTheme 在 AppTheme / DarkAppTheme 之间切换（项目没有 -night 资源），
 * 因此这里解析 android:attr/colorBackground 的亮度来判断主题，文字与描边取深浅两档。
 */
public final class TagChipRenderer {

    /** 条目底部一行最多展示几个标签，其余折叠成 "+n"，保证行高可预测。 */
    public static final int MAX_TAGS_IN_ROW = 4;

    private static final float CHIP_TEXT_SP = 12f;
    private static final float CHIP_H_PADDING_DP = 9f;
    private static final float CHIP_V_PADDING_DP = 3f;
    private static final float CHIP_SPACING_DP = 6f;
    private static final float CHIP_CORNER_DP = 11f;
    private static final float CHIP_STROKE_DP = 1f;

    private TagChipRenderer() {
    }

    /**
     * 建一个标签 chip。
     *
     * @param filled  true = 实心底（筛选条里已选中的），false = 浅色底 + 描边（展示用）
     * @param onClick 非 null 时 chip 自身可点击（筛选条点它即取消该筛选）；条目里的 chip
     *                必须传 null —— ListView 的行内放可点击子 view 会抢走整行点击。
     */
    public static TextView createChip(Context context, String text, boolean filled,
        View.OnClickListener onClick) {
        return createChip(context, text, filled, onClick, false, text);
    }

    /**
     * @param neutral true = 中性灰（溢出计数这类「不是标签」的占位 chip），不参与色相派生，
     *                免得 "+2" 看起来像一个真标签。
     */
    public static TextView createChip(Context context, String text, boolean filled,
        View.OnClickListener onClick, boolean neutral) {
        return createChip(context, text, filled, onClick, neutral, text);
    }

    /**
     * 筛选条里的「已选」chip：文案带「×」，但配色按标签名派生 —— 否则 "work  ×" 和条目里的
     * "work" 会被算成两个色相，同一个标签在两处长得不一样。
     *
     * @param colorKey 派生色相用的稳定键；「无标签」这类伪标签传固定英文键，避免换语言就换色。
     */
    public static TextView createSelectedChip(Context context, String text, String colorKey,
        View.OnClickListener onClick) {
        return createChip(context, removableLabel(text), true, onClick, false, colorKey);
    }

    private static TextView createChip(Context context, String text, boolean filled,
        View.OnClickListener onClick, boolean neutral, String colorKey) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP);
        chip.setSingleLine(true);
        chip.setIncludeFontPadding(false);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setTypeface(Typeface.DEFAULT, filled ? Typeface.BOLD : Typeface.NORMAL);
        chip.setPadding((int) dp(context, CHIP_H_PADDING_DP), (int) dp(context, CHIP_V_PADDING_DP),
            (int) dp(context, CHIP_H_PADDING_DP), (int) dp(context, CHIP_V_PADDING_DP));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) dp(context, CHIP_SPACING_DP);
        lp.gravity = Gravity.CENTER_VERTICAL;
        chip.setLayoutParams(lp);

        applyStyle(context, chip, filled, neutral, colorKey);

        if (onClick != null) {
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(onClick);
        }
        return chip;
    }

    /**
     * 渲染条目底部的标签行。没有标签时把整行连同外层 HorizontalScrollView 一起隐藏 ——
     * 外层即使高度为 0 也仍然是行里的一个节点，留着它等于给 ListView 塞了个多余的子 view。
     */
    public static void fillTagRow(LinearLayout container, List<String> tagNames) {
        container.removeAllViews();
        boolean empty = tagNames == null || tagNames.isEmpty();
        int visibility = empty ? View.GONE : View.VISIBLE;
        container.setVisibility(visibility);
        // 只认 HorizontalScrollView 这一种外层：万一以后标签行不再套滚动容器，
        // 无条件隐藏 parent 就会把整个条目的根布局关掉，那种 bug 排查起来很费劲。
        if (container.getParent() instanceof HorizontalScrollView) {
            ((View) container.getParent()).setVisibility(visibility);
        }
        if (empty) return;
        Context context = container.getContext();
        int shown = Math.min(tagNames.size(), MAX_TAGS_IN_ROW);
        for (int i = 0; i < shown; i++) {
            container.addView(createChip(context, tagNames.get(i), false, null));
        }
        if (tagNames.size() > shown) {
            // 溢出计数用中性色：它不是标签，不该抢标签的视觉身份
            container.addView(createChip(context, "+" + (tagNames.size() - shown), false, null, true));
        }
    }

    /** 已选标签的「点我移除」文案。 */
    public static String removableLabel(String tagName) {
        return tagName + "  \u00d7";
    }

    /** 标签名 -&gt; 色相。同名恒同色，与库里是否存 color 无关。 */
    public static int hueOf(String name) {
        if (name == null || name.isEmpty()) return 210;
        return Math.abs(name.hashCode() % 360);
    }

    private static void applyStyle(Context context, TextView chip, boolean filled, boolean neutral,
        String colorKey) {
        int hue = neutral ? 0 : hueOf(colorKey);
        boolean dark = isDarkTheme(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, CHIP_CORNER_DP));

        if (filled) {
            int solid = neutral
                ? ColorUtils.setAlphaComponent(neutralTextColor(context), dark ? 90 : 60)
                : Color.HSVToColor(new float[]{hue, 0.55f, 0.72f});
            bg.setColor(solid);
            chip.setTextColor(neutral ? neutralTextColor(context)
                : (ColorUtils.calculateLuminance(solid) > 0.45
                    ? Color.rgb(24, 24, 24) : Color.WHITE));
        } else {
            int accent = neutral ? neutralTextColor(context)
                : Color.HSVToColor(new float[]{hue, dark ? 0.42f : 0.60f,
                    dark ? 0.90f : 0.42f});
            bg.setColor(neutral ? neutralTint(dark, context) : tintedBackground(hue, dark, context));
            bg.setStroke((int) dp(context, CHIP_STROKE_DP),
                ColorUtils.setAlphaComponent(accent, dark ? 130 : 90));
            chip.setTextColor(accent);
        }
        chip.setBackground(bg);
    }

    /** 低透明度的色相叠在窗口背景上：浅主题得到淡色块，暗主题得到微微发亮的色块。 */
    private static int tintedBackground(int hue, boolean dark, Context context) {
        int vivid = Color.HSVToColor(new float[]{hue, 1f, 1f});
        int alpha = dark ? 46 : 26;
        return ColorUtils.compositeColors(Color.argb(alpha, Color.red(vivid), Color.green(vivid),
            Color.blue(vivid)), windowBackground(context));
    }

    /** 中性 chip 的底：纯灰叠加，不带任何色相。 */
    private static int neutralTint(boolean dark, Context context) {
        return ColorUtils.compositeColors(Color.argb(dark ? 40 : 24, 128, 128, 128),
            windowBackground(context));
    }

    private static int neutralTextColor(Context context) {
        return isDarkTheme(context) ? Color.rgb(170, 170, 170) : Color.rgb(120, 120, 120);
    }

    public static int windowBackground(Context context) {
        TypedArray array = context.obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
        int color = array.getColor(0, Color.WHITE);
        array.recycle();
        return color;
    }

    public static boolean isDarkTheme(Context context) {
        return ColorUtils.calculateLuminance(windowBackground(context)) < 0.5;
    }

    /** 供布局侧使用的圆角矩形（筛选条里的「匹配方式」开关，中性色，不与标签抢色）。 */
    public static GradientDrawable neutralPill(Context context, boolean filled) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(context, CHIP_CORNER_DP));
        boolean dark = isDarkTheme(context);
        if (filled) {
            pill.setColor(dark ? Color.rgb(62, 62, 62) : Color.rgb(232, 232, 232));
        } else {
            pill.setColor(Color.TRANSPARENT);
            pill.setStroke((int) dp(context, CHIP_STROKE_DP),
                dark ? Color.rgb(90, 90, 90) : Color.rgb(200, 200, 200));
        }
        return pill;
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            context.getResources().getDisplayMetrics());
    }
}
