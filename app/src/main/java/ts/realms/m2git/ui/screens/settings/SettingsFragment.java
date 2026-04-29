package ts.realms.m2git.ui.screens.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.TaskStackBuilder;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.io.File;

import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Repo;

import ts.realms.m2git.local.preference.PreferenceHelper;
import ts.realms.m2git.ui.components.fragments.ExploreRootDirActivity;
import ts.realms.m2git.ui.components.fragments.PrivateKeyManageActivity;
import ts.realms.m2git.ui.screens.main.RepoListActivity;
import ts.realms.m2git.ui.screens.settings.SettingsBackupActivity;
import ts.realms.m2git.utils.BasicFunctions;

public class SettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private PreferenceHelper preferenceHelper;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {

        preferenceHelper = PreferenceHelper.getInstance(this.getContext());
        // need to set as for historical reasons SGit uses custom prefs file
        PreferenceManager prefMgr = getPreferenceManager();
        prefMgr.setSharedPreferencesName(getString(R.string.preference_file_key));
        prefMgr.setSharedPreferencesMode(Context.MODE_PRIVATE);

        // /storage/emulated/0/Documents
        if (preferenceHelper == null || preferenceHelper.getRepoRoot() == null || preferenceHelper.getRepoRoot().toString().isEmpty()) {
            File documentsDir = new File(Environment.getExternalStorageDirectory(), "Documents");
            Repo.setLocalRepoRoot(this.getContext(), documentsDir);
        }

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);

        PreferenceScreen screen = getPreferenceScreen();
        int count = screen.getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = screen.getPreference(i);
            processPreference(pref);
        }

        registerOnPreferenceClickListener();
    }


    private void processPreference(Preference pref) {
        if (pref instanceof PreferenceGroup) {
            // 如果是分组容器（PreferenceCategory或嵌套的PreferenceScreen）
            PreferenceGroup group = (PreferenceGroup) pref;
            int groupCount = group.getPreferenceCount();
            for (int j = 0; j < groupCount; j++) {
                // 递归处理分组内的每一项
                processPreference(group.getPreference(j));
            }
        } else {
            // 这才是真正的设置项（EditTextPreference等）
            setupSummaryProviderForSinglePreference(pref);
        }
    }

    private void setupSummaryProviderForSinglePreference(Preference pref) {
        // EditTextPreference
        if (pref.getClass() == EditTextPreference.class && pref.getSummary() != null) {
            CharSequence summary = pref.getSummary();
            // 已经设置持久化，所以框架会自己存储到文件中。
            pref.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
                if (summary != null) {
                    // 正常情况下
                    return String.format(summary.toString(), preference.getText());
                } else {
                    return null;
                }
            });
            pref.setOnPreferenceChangeListener((preference, newValue) -> true);
        }
        // Preference
        if (pref.getClass() == Preference.class && pref.getSummary() != null) {
            CharSequence summary = pref.getSummary();
            pref.setSummaryProvider((Preference.SummaryProvider<Preference>) preference -> {
                if (summary != null) {
                    String preference_file_key = getContext().getString(R.string.preference_file_key);
                    SharedPreferences sharedPreference = getContext().getSharedPreferences(preference_file_key, Context.MODE_PRIVATE);
                    String key = preference.getKey();
                    String value = sharedPreference.getString(key, "");
                    return String.format(summary.toString(), value);
                } else {
                    return null;
                }
            });
            pref.setOnPreferenceChangeListener((preference, newValue) -> true);
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        // 获取当前 PreferenceScreen 关联的 SharedPreferences 并注册监听
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        // 注销监听，防止内存泄漏
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        Context context = preference.getContext();
        String key = preference.getKey();
        if (key != null) {
            // 需要注册监听器
            if (key.equals(getString(R.string.pref_key_repo_root_location))) {
                Intent exploreRootDirIntent = new Intent(context, ExploreRootDirActivity.class);
                context.startActivity(exploreRootDirIntent);
                return true;
            } else if (key.equals(getString(R.string.pref_key_manage_ssh_keys))) {
                Intent privateKeyManageActivityIntent = new Intent(context, PrivateKeyManageActivity.class);
                context.startActivity(privateKeyManageActivityIntent);
                return true;
            } else if (key.equals(getString(R.string.pref_key_credential_manager))) {
                Intent credentialActivityIntent = new Intent(context, CredentialActivity.class);
                context.startActivity(credentialActivityIntent);
                return true;
            } else if (key.equals(getString(R.string.pref_key_export_settings))) {
                Intent exportIntent = new Intent(context, SettingsBackupActivity.class);
                exportIntent.putExtra("action", "export");
                context.startActivity(exportIntent);
                return true;
            } else if (key.equals(getString(R.string.pref_key_import_settings))) {
                Intent importIntent = new Intent(context, SettingsBackupActivity.class);
                importIntent.putExtra("action", "import");
                context.startActivity(importIntent);
                return true;
            } else if (key.equals(getString(R.string.pref_key_send_feedback))) {
                String feedbackUrl = context.getString(R.string.feedback_url);
                Intent feedbackIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl));
                context.startActivity(feedbackIntent);
                return true;
            }
        }
        return false;
    }

    private void registerOnPreferenceClickListener() {
        final Preference sshPreference = findPreference(getString(R.string.pref_key_manage_ssh_keys));
        final Preference feedbackPreference = findPreference(getString(R.string.pref_key_send_feedback));
        final Preference repoPreference = findPreference(getString(R.string.pref_key_repo_root_location));
        final Preference credentialPreference = findPreference(getString(R.string.pref_key_credential_manager));
        final Preference exportPreference = findPreference(getString(R.string.pref_key_export_settings));
        final Preference importPreference = findPreference(getString(R.string.pref_key_import_settings));

        if (sshPreference != null) {
            sshPreference.setOnPreferenceClickListener(this);
        }
        if (feedbackPreference != null) {
            feedbackPreference.setOnPreferenceClickListener(this);
        }
        if (repoPreference != null) {
            repoPreference.setOnPreferenceClickListener(this);
        }
        if (credentialPreference != null) {
            credentialPreference.setOnPreferenceClickListener(this);
        }
        if (exportPreference != null) {
            exportPreference.setOnPreferenceClickListener(this);
        }
        if (importPreference != null) {
            importPreference.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String s) {
        final String themePrefKey = getString(R.string.pref_key_use_theme_id);
        final String gravatarPrefKey = getString(R.string.pref_key_use_gravatar);
        final String useEnglishPrefKey = getString(R.string.pref_key_use_english);
        if (themePrefKey.equals(s) || useEnglishPrefKey.equals(s)) {
            // nice trick to recreate the back stack, to ensure existing activities onCreate() are
            // called to set new theme, courtesy of: http://stackoverflow.com/a/28799124/85472
            TaskStackBuilder.create(getActivity())
                .addNextIntent(new Intent(getActivity(), RepoListActivity.class))
                .addNextIntent(getActivity().getIntent())
                .startActivities();
        } else if (gravatarPrefKey.equals(s)) {
            BasicFunctions.getImageLoader().clearMemoryCache();
            BasicFunctions.getImageLoader().clearDiskCache();
        }
    }


}
