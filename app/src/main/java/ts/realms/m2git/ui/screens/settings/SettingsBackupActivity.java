package ts.realms.m2git.ui.screens.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import ts.realms.m2git.R;
import ts.realms.m2git.local.backup.BackupManager;
import ts.realms.m2git.local.backup.BackupResult;

public class SettingsBackupActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_EXPORT = 1001;
    private static final int REQUEST_CODE_IMPORT = 1002;

    private BackupManager mBackupManager;
    private String mPendingPassword;
    private boolean mIsExport;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBackupManager = new BackupManager(this);

        String action = getIntent().getStringExtra("action");
        if ("export".equals(action)) {
            showPasswordDialog(true);
        } else if ("import".equals(action)) {
            showPasswordDialog(false);
        } else {
            finish();
        }
    }

    private void showPasswordDialog(boolean isExport) {
        mIsExport = isExport;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isExport ? R.string.dialog_export_password_title : R.string.dialog_import_password_title);
        builder.setMessage(isExport ? R.string.dialog_export_password_msg : R.string.dialog_import_password_msg);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 0);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(input);

        builder.setView(layout);

        builder.setPositiveButton(R.string.label_ok, (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (password.isEmpty()) {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            mPendingPassword = password;
            if (isExport) {
                openExportPicker();
            } else {
                openImportPicker();
            }
        });

        builder.setNegativeButton(R.string.label_cancel, (dialog, which) -> finish());
        builder.setOnCancelListener(dialog -> finish());
        builder.show();
    }

    private void openExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "m2git-backup-" + System.currentTimeMillis() + ".m2git");
        startActivityForResult(intent, REQUEST_CODE_EXPORT);
    }

    private void openImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) {
            finish();
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            finish();
            return;
        }

        if (requestCode == REQUEST_CODE_EXPORT) {
            doExport(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT) {
            doImport(uri);
        }
    }

    private void doExport(Uri uri) {
        mBackupManager.exportToUri(uri, mPendingPassword, new BackupManager.ExportCallback() {
            @Override
            public void onSuccess(JSONObject backup) {
                int repoCount = backup.optJSONArray("repos") != null ? backup.optJSONArray("repos").length() : 0;
                int credCount = backup.optJSONArray("credentials") != null ? backup.optJSONArray("credentials").length() : 0;
                String msg = getString(R.string.export_success, repoCount, credCount);
                Toast.makeText(SettingsBackupActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(SettingsBackupActivity.this, getString(R.string.backup_error, error), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void doImport(Uri uri) {
        mBackupManager.importFromUri(uri, mPendingPassword, new BackupManager.ImportCallback() {
            @Override
            public void onSuccess(BackupResult result) {
                String msg = getString(R.string.import_success,
                    result.getRepoSuccessCount(),
                    result.getCredentialSuccessCount(),
                    result.getRepoSkippedCount() + result.getCredentialSkippedCount());
                Toast.makeText(SettingsBackupActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(SettingsBackupActivity.this, getString(R.string.backup_error, error), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}
