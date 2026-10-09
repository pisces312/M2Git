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

import java.util.ArrayList;
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

    /**
     * @param exclusiveUntagged true = 「无标签」与具体标签互斥：勾上前者清掉后者，反之亦然。
     *                          只在 AND（全部满足）模式下需要 —— AND 下两者同时成立是无解的
     *                          （既有标签又不带任何标签的仓库不存在），OR 下却是合法并集
     *                          （「挂了 X 的」+「一个标签都没有的」），所以由调用方按当前匹配方式传。
     */
    public static void show(Context context, int titleRes, OptionsProvider provider,
        Collection<Integer> initiallySelected, boolean allowCreate, boolean showCounts,
        boolean exclusiveUntagged, OnConfirmed onConfirmed) {

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

        // 渲染抽成静态自调用方法而不是局部 Runnable：局部 Runnable 在自己的初始化器里
        // 被（哪怕是更内层的）lambda 引用javac 一律报「可能尚未初始化」，编译不过（实测）。
        // 方法自调用没有这个问题。
        // 对话框是「勾完点确定才生效」，所以这里不需要每次勾选就回调；工具栏弹窗走同一个
        // 渲染方法，它传进来的回调是即时生效用的。
        renderOptions(context, checkList, provider, selected, showCounts, exclusiveUntagged, null);

        /**
         * 本次「就地新建」出来的标签。没确认就关窗（取消 / 返回键 / 点窗外）要回滚：
         * 它们只是打字打出来的，一个仓库都没挂，留着就变成 0 命中的空标签，
         * 还会出现在每个仓库的标签候选里。复用已有标签的不能算进来 —— 那会把
         * 那个标签连同它在所有仓库上的关联一起删掉。
         */
        final List<Integer> createdHere = new ArrayList<>();
        final boolean[] confirmed = new boolean[1];

        if (allowCreate) {
            addButton.setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                boolean existed = RepoDbManager.findTagId(name) >= 0;
                long tagId = RepoDbManager.createTagIfAbsent(name);
                if (tagId < 0) return;
                if (!existed) {
                    createdHere.add((int) tagId);
                }
                // 就地新建即视为选中，符合「打字是为了挂上它」的意图
                selected.add((int) tagId);
                input.setText("");
                hideKeyboard(context, input);
                renderOptions(context, checkList, provider, selected, showCounts,
                    exclusiveUntagged, null);
            });
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(content)
            .setPositiveButton(R.string.label_ok, (dialogInterface, which) -> {
                confirmed[0] = true;
                onConfirmed.onConfirmed(new LinkedHashSet<>(selected));
            })
            .setNegativeButton(R.string.label_cancel, new DummyDialogListener())
            .create();
        // 用 dismiss 而不是只监听取消按钮：返回键、点窗外都会走到这里
        dialog.setOnDismissListener(dialogInterface -> {
            if (confirmed[0]) return;
            for (Integer tagId : createdHere) {
                RepoDbManager.deleteTag(tagId);
            }
        });
        dialog.show();
    }

    /**
     * 重画整个选项列表。互斥挤掉别的选择时才需要重画（此时旧的勾选状态已经不对了）——
     * 平时重画会丢掉列表的滚动位置，每点一个框都跳回顶部是没法用的。
     *
     * setChecked 在注册监听之前调用，所以重画不会触发监听器，自调用也不会递归。
     *
     * 工具栏弹窗共用这个方法（勾选即时生效），所以多一个 onChanged：每次勾选变化后回调一次，
     * 让调用方决定是记下来等确定（对话框）还是立刻落库重算（弹窗）。
     *
     * @param onChanged 勾选变化回调，在（可能发生的）重画之后触发一次，不会漏也不会重；
     *                  null = 不需要（对话框是点确定才生效的）。
     */
    static void renderOptions(Context context, LinearLayout checkList,
        OptionsProvider provider, Set<Integer> selected, boolean showCounts,
        boolean exclusiveUntagged, Runnable onChanged) {
        checkList.removeAllViews();
        int minHeight = context.getResources().getDimensionPixelSize(R.dimen.general_min_height);
        for (final Tag tag : provider.load()) {
            CheckBox box = new CheckBox(context);
            box.setText(chipLabel(context, tag, showCounts));
            box.setChecked(selected.contains(tag.getId()));
            box.setMinimumHeight(minHeight);
            box.setOnCheckedChangeListener((buttonView, isChecked) -> {
                boolean displaced = false;
                if (isChecked) {
                    if (exclusiveUntagged) {
                        if (tag.isUntagged()) {
                            displaced = !selected.isEmpty();
                            selected.clear();
                        } else {
                            displaced = selected.remove(Tag.UNTAGGED_ID);
                        }
                    }
                    selected.add(tag.getId());
                } else {
                    selected.remove(tag.getId());
                }
                if (displaced) {
                    renderOptions(context, checkList, provider, selected, showCounts,
                        exclusiveUntagged, onChanged);
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            });
            checkList.addView(box);
        }
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
