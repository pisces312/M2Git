package ts.realms.m2git.ui.components.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.StatusTask;

/**
 * 状态页下半部：变更文件列表。一行 = 一个（分组，路径），徽标 + 仓库相对路径，
 * 顺序由 {@link StatusTask} 排好（按路径，同路径按分组序）。
 */
public class StatusFilesAdapter extends ArrayAdapter<StatusTask.StatusFile> {

    public StatusFilesAdapter(Context context) {
        super(context, 0);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        LayoutInflater inflater = LayoutInflater.from(getContext());
        StatusFileItemHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.list_item_status_file, parent,
                false);
            holder = new StatusFileItemHolder();
            holder.badge = convertView
                .findViewById(R.id.statusFileBadge);
            holder.path = convertView
                .findViewById(R.id.statusFilePath);
            convertView.setTag(holder);
        } else {
            holder = (StatusFileItemHolder) convertView.getTag();
        }
        StatusTask.StatusFile item = getItem(position);
        holder.badge.setText(item.group.badge);
        holder.path.setText(item.path);
        return convertView;
    }

    private static class StatusFileItemHolder {
        public TextView badge;
        public TextView path;
    }
}
