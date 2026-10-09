package ts.realms.m2git.ui.components.dialogs;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import ts.realms.m2git.R;
import ts.realms.m2git.ui.components.views.TagChipRenderer;

/**
 * 工具栏上的标签筛选弹窗：锚在图标正下方，一列复选框，勾一个立刻筛一次。
 * <p>
 * 与 {@link TagPickerDialog}（居中对话框、点确定才生效）的区别只有两点：挂在点击位置、即时生效。
 * 复选框的渲染与「无标签」互斥逻辑全部走 {@link TagPickerDialog#renderOptions}，这里只负责把它
 * 挂成一个浮层，不复制那一套。
 */
public final class TagFilterPopup {

    private static final float WIDTH_DP = 280f;
    private static final float SCREEN_MARGIN_DP = 8f;
    private static final float CORNER_DP = 6f;
    private static final float ELEVATION_DP = 8f;
    /** 标签多到一屏放不下时内部滚动，不让弹窗顶出屏幕。 */
    private static final float MAX_HEIGHT_RATIO = 0.7f;

    public interface OnSelectionChanged {
        void onSelectionChanged(Set<Integer> tagIds);
    }

    private TagFilterPopup() {
    }

    /**
     * @param anchor 弹窗锚点（工具栏图标自己），弹窗右边缘与它对齐。
     */
    public static void show(Context context, View anchor,
        TagPickerDialog.OptionsProvider provider, Collection<Integer> selectedIds,
        boolean exclusiveUntagged, OnSelectionChanged onSelectionChanged) {

        View content = LayoutInflater.from(context).inflate(R.layout.popup_tag_filter, null);
        LinearLayout checkList = content.findViewById(R.id.tagCheckList);
        final Set<Integer> selected = new LinkedHashSet<>();
        if (selectedIds != null) {
            selected.addAll(selectedIds);
        }
        // 勾选即生效：每动一个框就落一次筛选，列表与顶部筛选条跟着变；点弹窗外面即走，结果已经在。
        TagPickerDialog.renderOptions(context, checkList, provider, selected, true,
            exclusiveUntagged,
            () -> onSelectionChanged.onSelectionChanged(new LinkedHashSet<>(selected)));

        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.min((int) (WIDTH_DP * density),
            context.getResources().getDisplayMetrics().widthPixels
                - (int) (2 * SCREEN_MARGIN_DP * density));
        // 先按不限高量出内容真实高度，再钳到屏幕七成：标签少时贴着内容，多时内部滚动。
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(content.getMeasuredHeight(),
            (int) (context.getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_RATIO));

        PopupWindow popup = new PopupWindow(content, width, height, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(TagChipRenderer.windowBackground(context));
        background.setCornerRadius(CORNER_DP * density);
        popup.setBackgroundDrawable(background);
        popup.setElevation(ELEVATION_DP * density);
        popup.setOutsideTouchable(true);
        // END：弹窗右边缘对齐锚点右边缘，图标本就在最右侧，这样不会溢出屏幕
        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }
}
