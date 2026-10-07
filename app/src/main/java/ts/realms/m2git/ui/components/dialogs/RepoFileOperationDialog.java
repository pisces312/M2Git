package ts.realms.m2git.ui.components.dialogs;

import static ts.realms.m2git.core.command.tasks.local.DeleteFileFromRepoTask.DeleteOperationType;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;

import java.io.File;

import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.UpdateIndexTask;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity;

/**
 * Created by sheimi on 8/16/13.
 */
public class RepoFileOperationDialog extends BaseDialogFragment {

    public static final String FILE_PATH = "file path";
    public static final String REPO_PATH = "repo path";
    private static final int ADD_TO_STAGE = 0;
    private static final int CHECKOUT_FILE = 1;
    private static final int DELETE = 2;
    private static final int REMOVE_CACHED = 3;
    private static final int REMOVE_FORCE = 4;
    private static final int MAKE_EXECUTABLE = 5;
    private static final int MAKE_NOT_EXECUTABLE = 6;
    private static final int COPY_RELATIVE_PATH = 7;
    private static final int COPY_ABSOLUTE_PATH = 8;
    private static String mFilePath;
    private static String mRepoPath;
    private RepoDetailActivity mActivity;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        super.onCreateDialog(savedInstanceState);
        Bundle args = getArguments();
        if (args != null && args.containsKey(FILE_PATH)) {
            mFilePath = args.getString(FILE_PATH);
            mRepoPath = args.getString(REPO_PATH);
        }

        mActivity = (RepoDetailActivity) getActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity);

        builder.setTitle(R.string.dialog_title_you_want_to).setItems(
            R.array.repo_file_operations, (dialog, which) -> {
                switch (which) {
                    case ADD_TO_STAGE: // Add to stage
                        mActivity.getRepoDelegate().addToStage(
                            mFilePath);
                        break;
                    case CHECKOUT_FILE:
                        mActivity.getRepoDelegate().checkoutFile(mFilePath);
                        break;
                    case DELETE:
                        showRemoveFileMessageDialog(R.string.dialog_file_delete,
                            R.string.dialog_file_delete_msg,
                            R.string.label_delete,
                            DeleteOperationType.DELETE);
                        break;
                    case REMOVE_CACHED:
                        showRemoveFileMessageDialog(R.string.dialog_file_remove_cached,
                            R.string.dialog_file_remove_cached_msg,
                            R.string.label_delete,
                            DeleteOperationType.REMOVE_CACHED);
                        break;
                    case REMOVE_FORCE:
                        showRemoveFileMessageDialog(R.string.dialog_file_remove_force,
                            R.string.dialog_file_remove_force_msg,
                            R.string.label_delete,
                            DeleteOperationType.REMOVE_FORCE);
                        break;
                    case MAKE_EXECUTABLE:
                    case MAKE_NOT_EXECUTABLE:
                        final boolean newExecutableState = which == MAKE_EXECUTABLE;
                        mActivity.getRepoDelegate().updateIndex(mFilePath, UpdateIndexTask.calculateNewMode(newExecutableState));
                        break;
                    case COPY_RELATIVE_PATH:
                    case COPY_ABSOLUTE_PATH: {
                        String path = mFilePath;
                        if (which == COPY_RELATIVE_PATH) {
                            String relative = toRelativePath(mRepoPath, mFilePath);
                            if (relative != null) {
                                path = relative;
                            }
                        }
                        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("mgit", path));
                        showToastMessage(R.string.msg_copied_to_clipboard);
                        break;
                    }
                }
            });

        return builder.create();
    }

    private static String toRelativePath(String repoPath, String filePath) {
        if (repoPath == null || repoPath.isEmpty()) {
            return null;
        }
        // URI 相对化可正确处理路径前缀；结果仍是绝对路径说明文件在仓库外，此时回退为绝对路径
        String relative = new File(repoPath).toURI().relativize(new File(filePath).toURI()).getPath();
        if (relative.startsWith("/")) {
            return null;
        }
        // 长按目录项时 URI 末尾会带 "/"，剪贴板里去掉
        if (relative.endsWith("/") && relative.length() > 1) {
            relative = relative.substring(0, relative.length() - 1);
        }
        return relative;
    }

    private void showRemoveFileMessageDialog(int dialog_title, int dialog_msg, int dialog_positive_button, final DeleteOperationType deleteOperationType) {
        showMessageDialog(
            dialog_title,
            dialog_msg,
            dialog_positive_button,
            (dialogInterface, i) -> mActivity.getRepoDelegate().deleteFileFromRepo(mFilePath, deleteOperationType)
        );
    }
}
