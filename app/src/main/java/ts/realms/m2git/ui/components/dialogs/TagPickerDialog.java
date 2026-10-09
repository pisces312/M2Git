package ts.realms.m2git.ui.components.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Tag;
import ts.realms.m2git.local.database.RepoDbManager;

/**
 * 标签多选面板，两个入口共用：
 * 「按标签筛选」（allowCreate=false，列表带命中数）和「编辑标签」（allowCreate=true，可就地新建）。
 *
 * 选项每次现取（{@link OptionsProvider}），所以就地新建的标签会立刻出现在列表里并被勾上，
 * 不需要关窗重开。
 */
public final class TagPickerDialog {

    public interface OptionsProvider {
        List<Tag> load();
    }

    public interface OnConfirmed {
        void onConfirmed(Set<Integer> tagIds);
    }

    private TagPickerDialog() {
    }

    public static void show(Context context, int titleRes, OptionsProvider provider,
        Collection<Integer> initiallySelected, boolean allowCreate, boolean showCounts,
        OnConfirmed onConfirmed) {

        View content = LayoutInflater.from(context).inflate(R.layout.dialog_tag_picker, null);
        final LinearLayout checkList = content.findViewById(R.id.tagCheckList);
        LinearLayout addRow = content.findViewById(R.id.tagAddRow);
        final EditText input = content.findViewById(R.id.tagNameInput);
        Button addButton = content.findViewById(R.id.tagAddButton);
        addRow.setVisibility(allowCreate ? View.VISIBLE : View.GONE);

        final Set<Integer> selected = new LinkedHashSet<>();
        if (initiallySelected != null) {
            selected.addAll(initiallySelected);
        }

        Runnable rebuild = () -> {
            checkList.removeAllViews();
            int minHeight = context.getResources().getDimensionPixelSize(R.dimen.general_min_height);
            for (final Tag tag : provider.load()) {
                CheckBox box = new CheckBox(context);
                box.setText(chipLabel(context, tag, showCounts));
                box.setChecked(selected.contains(tag.getId()));
                box.setMinimumHeight(minHeight);
                box.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selected.add(tag.getId());
                    } else {
                        selected.remove(tag.getId());
                    }
                });
                checkList.addView(box);
            }
        };
        rebuild.run();

        if (allowCreate) {
            addButton.setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                long tagId = RepoDbManager.createTagIfAbsent(name);
                if (tagId < 0) return;
                // 就地新建即视为选中，符合「打字是为了挂上它」的意图
                selected.add((int) tagId);
                input.setText("");
                hideKeyboard(context, input);
                rebuild.run();
            });
        }

        new AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(content)
            .setPositiveButton(R.string.label_ok, (dialogInterface, which) ->
                onConfirmed.onConfirmed(new LinkedHashSet<>(selected)))
            .setNegativeButton(R.string.label_cancel, new DummyDialogListener())
            .show();
    }

    private static String chipLabel(Context context, Tag tag, boolean showCounts) {
        String name = tag.isUntagged()
            ? context.getString(R.string.tag_untagged) : tag.getName();
        if (!showCounts) return name;
        return name + " (" + tag.getRepoCount() + ")";
    }

    private static void hideKeyboard(Context context, View view) {
        InputMethodManager imm =
            (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }
}
