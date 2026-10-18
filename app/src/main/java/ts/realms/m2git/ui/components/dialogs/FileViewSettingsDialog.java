package ts.realms.m2git.ui.components.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import ts.realms.m2git.R;
import ts.realms.m2git.local.preference.FileViewOptions;

/**
 * 文件内容查看页的「显示选项」弹窗：行号 / 自动换行 / 括号匹配 + 字号 / Tab 宽度。
 *
 * <p>选项<b>即时生效</b>而不是「确定后再生效」：这几项改动的效果就是看得见/看不见，
 * 需要用户当场看到结果才能判断，所以没有「确定」按钮，只有「关闭」。
 * 由此也不存在「取消」语义——已经把值推给页面了，撤销与关闭是两件事，混在一起会误导。
 *
 * <p>「恢复默认」= 丢弃本页的临时覆盖并回到设置页的全局值，点击后弹窗关闭
 * （AlertDialog 的按钮默认行为），避免弹窗里的控件与「已丢弃覆盖」的状态不一致。
 *
 * <p>纯代码构建视图，沿用 {@link SortOptionsDialog} 的做法：项目里对话框没有走
 * MaterialAlertDialogBuilder，自绘 LinearLayout + 系统 AlertDialog 是既有风格。
 */
public final class FileViewSettingsDialog {

    public interface Listener {
        /** 本页临时覆盖变化；回调值是完整组合的副本，调用方需自己持有。 */
        void onOptionsChanged(FileViewOptions options);

        /** 用户要求丢弃本页覆盖、回到全局默认。 */
        void onRestoreDefaults();
    }

    private FileViewSettingsDialog() {
    }

    public static void show(Context context, FileViewOptions effective, Listener listener) {
        final FileViewOptions working = effective.copy();
        final int[] fontSizes = readIntArray(context.getResources(), R.array.viewer_font_size_values);
        final int[] tabSizes = readIntArray(context.getResources(), R.array.viewer_tab_sizes);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int margin = context.getResources().getDimensionPixelSize(R.dimen.general_padding_larger);
        content.setPadding(margin, margin / 2, margin, 0);

        // CheckBox 先 setChecked 再挂监听，初始化不会回调
        CheckBox lineNumbers = makeCheckBox(context, R.string.preference_viewer_line_numbers,
            working.lineNumbers);
        lineNumbers.setOnCheckedChangeListener((buttonView, checked) -> {
            if (working.lineNumbers == checked) {
                return;
            }
            working.lineNumbers = checked;
            listener.onOptionsChanged(working.copy());
        });

        CheckBox lineWrapping = makeCheckBox(context, R.string.preference_viewer_line_wrap,
            working.lineWrapping);
        lineWrapping.setOnCheckedChangeListener((buttonView, checked) -> {
            if (working.lineWrapping == checked) {
                return;
            }
            working.lineWrapping = checked;
            listener.onOptionsChanged(working.copy());
        });

        CheckBox matchBrackets = makeCheckBox(context, R.string.preference_viewer_match_brackets,
            working.matchBrackets);
        matchBrackets.setOnCheckedChangeListener((buttonView, checked) -> {
            if (working.matchBrackets == checked) {
                return;
            }
            working.matchBrackets = checked;
            listener.onOptionsChanged(working.copy());
        });

        content.addView(lineNumbers);
        content.addView(lineWrapping);
        content.addView(matchBrackets);

        Spinner fontSpinner = makeSpinner(context, R.array.viewer_font_sizes,
            indexOf(fontSizes, working.fontSizeSp));
        // Spinner 在首次 layout 时会补发一次 onItemSelected：这里靠「值没变就返回」拦掉，
        // 不需要额外的初始化标志位
        fontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int value = fontSizes[position];
                if (working.fontSizeSp == value) {
                    return;
                }
                working.fontSizeSp = value;
                listener.onOptionsChanged(working.copy());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Spinner tabSpinner = makeSpinner(context, R.array.viewer_tab_sizes,
            indexOf(tabSizes, working.tabSize));
        tabSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int value = tabSizes[position];
                if (working.tabSize == value) {
                    return;
                }
                working.tabSize = value;
                listener.onOptionsChanged(working.copy());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        content.addView(makeSpinnerRow(context, R.string.preference_viewer_font_size, fontSpinner));
        content.addView(makeSpinnerRow(context, R.string.preference_viewer_tab_size, tabSpinner));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        new AlertDialog.Builder(context)
            .setTitle(R.string.dialog_view_settings_title)
            .setView(scroll)
            .setPositiveButton(R.string.label_close, new DummyDialogListener())
            .setNeutralButton(R.string.label_restore_defaults,
                (dialog, which) -> listener.onRestoreDefaults())
            .show();
    }

    private static CheckBox makeCheckBox(Context context, int labelRes, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(labelRes);
        box.setChecked(checked);
        int pad = box.getPaddingTop();
        box.setPadding(pad, pad, pad, pad);
        return box;
    }

    private static Spinner makeSpinner(Context context, int entriesRes, int selectedIndex) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context, entriesRes,
            android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selectedIndex >= 0) {
            spinner.setSelection(selectedIndex, false);
        }
        return spinner;
    }

    private static LinearLayout makeSpinnerRow(Context context, int labelRes, Spinner spinner) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = context.getResources().getDimensionPixelSize(R.dimen.general_divider_padding);
        row.setPadding(0, pad, 0, pad);

        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        row.addView(spinner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 数组内容是自定的纯数字；解析失败留 -1，找不到就回落到第 0 项而不是崩溃。 */
    private static int[] readIntArray(Resources res, int arrayRes) {
        String[] raw = res.getStringArray(arrayRes);
        int[] values = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            try {
                values[i] = Integer.parseInt(raw[i].trim());
            } catch (NumberFormatException e) {
                values[i] = -1;
            }
        }
        return values;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return 0;
    }
}
