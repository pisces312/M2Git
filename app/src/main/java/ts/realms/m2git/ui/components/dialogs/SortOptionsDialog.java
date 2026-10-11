package ts.realms.m2git.ui.components.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import ts.realms.m2git.R;

/**
 * 排序面板：排序依据（单选）+ 方向（单选），按需再挂一节「选项」（目录优先勾选）。
 * 仓库列表和仓库详情的文件列表共用同一份实现，两处都只按**下标**回传选项，
 * 于是调用方的 SORT_KEY_* 常量顺序就是面板里的选项顺序。
 *
 * 为什么是下标而不是 R.id 常量：AGP 9 之后 R 字段非 final（switch(id) 编不过），
 * 而这里的 id 本来就是 {@link View#generateViewId()} 现场生成的，当不了常量。
 *
 * 依据与方向分成两组而不是列成「名称升序 / 名称降序 / …」的组合项：组合式列表每加一种
 * 排序键就要多两个选项，而偏好里存组合索引意味着选项平移会悄悄改掉用户的设置。
 */
public final class SortOptionsDialog {

    public interface OnConfirmed {
        /**
         * @param sortKey     排序依据下标，即调用方 SORT_KEY_* 常量
         * @param sortDir     方向下标，0 = 升序、1 = 降序
         * @param dirsFirst   仅 {@link #showWithDirsFirst} 有意义；用 {@link #show} 时恒为 false，
         *                    调用方直接忽略即可
         */
        void onConfirmed(int sortKey, int sortDir, boolean dirsFirst);
    }

    private SortOptionsDialog() {
    }

    /** 只要「依据 + 方向」两段（仓库列表用）。 */
    public static void show(Context context, int titleRes, int[] keyLabels, int currentKey,
                            int currentDir, OnConfirmed onConfirmed) {
        showInternal(context, titleRes, keyLabels, currentKey, currentDir, false, false,
            onConfirmed);
    }

    /** 多一节「选项」，里面是「目录优先」勾选（文件列表用）。 */
    public static void showWithDirsFirst(Context context, int titleRes, int[] keyLabels,
                                         int currentKey, int currentDir, boolean dirsFirst,
                                         OnConfirmed onConfirmed) {
        showInternal(context, titleRes, keyLabels, currentKey, currentDir, true, dirsFirst,
            onConfirmed);
    }

    private static void showInternal(final Context context, int titleRes, int[] keyLabels,
                                     int currentKey, int currentDir, boolean showDirsFirst,
                                     boolean dirsFirst, final OnConfirmed onConfirmed) {
        final RadioButton[] keyRadios = new RadioButton[keyLabels.length];
        for (int i = 0; i < keyLabels.length; i++) {
            keyRadios[i] = makeRadio(context, keyLabels[i], i == currentKey);
        }
        final RadioButton[] dirRadios = {
            makeRadio(context, R.string.sort_dir_asc, currentDir == 0),
            makeRadio(context, R.string.sort_dir_desc, currentDir == 1)
        };
        // 选项顺序即 SORT_KEY_* / SORT_DIR_* 常量顺序，取中时用下标回推
        final RadioGroup keyGroup = new RadioGroup(context);
        final RadioGroup dirGroup = new RadioGroup(context);
        fillRadioGroup(keyGroup, keyRadios);
        fillRadioGroup(dirGroup, dirRadios);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int margin = context.getResources().getDimensionPixelSize(R.dimen.general_padding_larger);
        content.setPadding(margin, margin / 2, margin, 0);
        content.addView(sectionHeader(context, R.string.sort_section_key));
        content.addView(keyGroup);
        content.addView(sectionHeader(context, R.string.sort_section_direction));
        content.addView(dirGroup);

        // checkbox 在 OK 时才读 checked，不做即时生效：与另外两组单选保持一致，
        // 也让「取消 / 点窗外」天然是无操作。
        final CheckBox dirsFirstBox = showDirsFirst
            ? makeCheckBox(context, R.string.sort_dirs_first, dirsFirst) : null;
        if (dirsFirstBox != null) {
            content.addView(sectionHeader(context, R.string.sort_section_option));
            content.addView(dirsFirstBox);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        new AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(scroll)
            .setPositiveButton(R.string.label_ok, (dialog, which) -> onConfirmed.onConfirmed(
                checkedIndexOf(keyGroup, keyRadios, currentKey),
                checkedIndexOf(dirGroup, dirRadios, currentDir),
                dirsFirstBox == null ? false : dirsFirstBox.isChecked()))
            .setNegativeButton(R.string.label_cancel, new DummyDialogListener())
            .show();
    }

    private static RadioButton makeRadio(Context context, int labelRes, boolean checked) {
        RadioButton radio = new RadioButton(context);
        radio.setText(labelRes);
        radio.setChecked(checked);
        radio.setId(View.generateViewId());
        int pad = radio.getPaddingTop();
        radio.setPadding(pad, pad, pad, pad);
        return radio;
    }

    private static CheckBox makeCheckBox(Context context, int labelRes, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(labelRes);
        box.setChecked(checked);
        int pad = box.getPaddingTop();
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private static void fillRadioGroup(RadioGroup group, RadioButton[] radios) {
        group.setOrientation(RadioGroup.VERTICAL);
        for (RadioButton radio : radios) {
            group.addView(radio);
        }
    }

    /** 取选中项下标；异常状态（没有选中）回退到原值，而不是把排序悄悄改成第一项。 */
    private static int checkedIndexOf(RadioGroup group, RadioButton[] radios, int fallback) {
        int checkedId = group.getCheckedRadioButtonId();
        for (int i = 0; i < radios.length; i++) {
            if (radios[i].getId() == checkedId) return i;
        }
        return fallback;
    }

    private static TextView sectionHeader(Context context, int labelRes) {
        TextView header = new TextView(context);
        header.setText(labelRes);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTextColor(themeColor(context, android.R.attr.textColorSecondary,
            ContextCompat.getColor(context, R.color.general_gray_text_color)));
        int top = context.getResources().getDimensionPixelSize(R.dimen.general_divider_padding);
        header.setPadding(0, top, 0, top / 2);
        return header;
    }

    private static int themeColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            return ContextCompat.getColor(context, value.resourceId);
        }
        return fallback;
    }
}
