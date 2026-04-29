package ts.realms.m2git.local.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import ts.realms.m2git.R;
import ts.realms.m2git.local.database.RepoContract;
import ts.realms.m2git.local.database.RepoDbManager;

public class BackupManager {

    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;

    private final Context mContext;

    public BackupManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public void exportToUri(Uri uri, String password, ExportCallback callback) {
        try {
            JSONObject backup = createBackupJson();
            byte[] data = backup.toString().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = encrypt(data, password);

            OutputStream os = mContext.getContentResolver().openOutputStream(uri);
            if (os == null) {
                callback.onError("Cannot open output stream");
                return;
            }
            os.write(encrypted);
            os.close();

            callback.onSuccess(backup);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    public void importFromUri(Uri uri, String password, ImportCallback callback) {
        try {
            InputStream is = mContext.getContentResolver().openInputStream(uri);
            if (is == null) {
                callback.onError("Cannot open input stream");
                return;
            }
            byte[] encrypted = readAllBytes(is);
            is.close();

            byte[] decrypted = decrypt(encrypted, password);
            String jsonStr = new String(decrypted, StandardCharsets.UTF_8);
            JSONObject backup = new JSONObject(jsonStr);

            BackupResult result = importBackup(backup);
            callback.onSuccess(result);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    private JSONObject createBackupJson() throws JSONException {
        JSONObject backup = new JSONObject();
        backup.put("version", 1);
        backup.put("export_time", System.currentTimeMillis());

        // Export repos
        JSONArray repos = new JSONArray();
        Cursor repoCursor = RepoDbManager.queryAllRepo();
        if (repoCursor != null) {
            repoCursor.moveToFirst();
            while (!repoCursor.isAfterLast()) {
                JSONObject repo = new JSONObject();
                repo.put("local_path", RepoContract.getLocalPath(repoCursor));
                repo.put("remote_url", RepoContract.getRemoteURL(repoCursor));
                repo.put("username", RepoContract.getUsername(repoCursor));
                repo.put("password", RepoContract.getPassword(repoCursor));
                repo.put("repo_status", RepoContract.getRepoStatus(repoCursor));
                repo.put("latest_committer_uname", RepoContract.getLatestCommitterName(repoCursor));
                repo.put("latest_committer_email", RepoContract.getLatestCommitterEmail(repoCursor));
                Date date = RepoContract.getLatestCommitDate(repoCursor);
                repo.put("latest_commit_date", date != null ? date.getTime() : 0);
                repo.put("latest_commit_msg", RepoContract.getLatestCommitMsg(repoCursor));
                repos.put(repo);
                repoCursor.moveToNext();
            }
            repoCursor.close();
        }
        backup.put("repos", repos);

        // Export credentials
        JSONArray credentials = new JSONArray();
        Cursor credCursor = RepoDbManager.queryAllCredential();
        if (credCursor != null) {
            credCursor.moveToFirst();
            while (!credCursor.isAfterLast()) {
                JSONObject cred = new JSONObject();
                cred.put("token_account", RepoContract.getOneTokenAccount(credCursor));
                cred.put("token_secret", RepoContract.getOneTokenSecret(credCursor));
                String[] relRepos = RepoContract.getOneRelReop(credCursor);
                JSONArray relRepoArr = new JSONArray();
                if (relRepos != null) {
                    for (String r : relRepos) {
                        relRepoArr.put(r);
                    }
                }
                cred.put("rel_repo", relRepoArr);
                credentials.put(cred);
                credCursor.moveToNext();
            }
            credCursor.close();
        }
        backup.put("credentials", credentials);

        // Export preferences
        JSONObject prefs = new JSONObject();
        SharedPreferences sp = mContext.getSharedPreferences(
            mContext.getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        Map<String, ?> allPrefs = sp.getAll();
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            prefs.put(entry.getKey(), entry.getValue());
        }
        backup.put("preferences", prefs);

        return backup;
    }

    private BackupResult importBackup(JSONObject backup) throws JSONException {
        BackupResult result = new BackupResult();

        // Import repos
        JSONArray repos = backup.optJSONArray("repos");
        if (repos != null) {
            for (int i = 0; i < repos.length(); i++) {
                JSONObject repo = repos.getJSONObject(i);
                String localPath = repo.optString("local_path", "");
                if (localPath.isEmpty()) {
                    result.addRepoResult("", BackupResult.Status.FAILED, "empty local_path");
                    continue;
                }

                // Check if exists
                Cursor existing = searchRepoByLocalPath(localPath);
                if (existing != null && existing.getCount() > 0) {
                    existing.close();
                    result.addRepoResult(localPath, BackupResult.Status.SKIPPED, "already exists");
                    continue;
                }
                if (existing != null) existing.close();

                long id = RepoDbManager.createRepo(
                    localPath,
                    repo.optString("remote_url", ""),
                    repo.optString("repo_status", "")
                );

                // Update additional fields
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(RepoContract.RepoEntry.COLUMN_NAME_USERNAME, repo.optString("username", ""));
                values.put(RepoContract.RepoEntry.COLUMN_NAME_PASSWORD, repo.optString("password", ""));
                values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_UNAME, repo.optString("latest_committer_uname", ""));
                values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_EMAIL, repo.optString("latest_committer_email", ""));
                long dateVal = repo.optLong("latest_commit_date", 0);
                values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_DATE, dateVal > 0 ? String.valueOf(dateVal) : "");
                values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_MSG, repo.optString("latest_commit_msg", ""));
                RepoDbManager.updateRepo(id, values);

                result.addRepoResult(localPath, BackupResult.Status.SUCCESS, null);
            }
        }

        // Import credentials
        JSONArray credentials = backup.optJSONArray("credentials");
        if (credentials != null) {
            for (int i = 0; i < credentials.length(); i++) {
                JSONObject cred = credentials.getJSONObject(i);
                String tokenAccount = cred.optString("token_account", "");
                if (tokenAccount.isEmpty()) {
                    result.addCredentialResult("", BackupResult.Status.FAILED, "empty token_account");
                    continue;
                }

                // Check if exists
                Cursor existing = searchCredentialByAccount(tokenAccount);
                if (existing != null && existing.getCount() > 0) {
                    existing.close();
                    result.addCredentialResult(tokenAccount, BackupResult.Status.SKIPPED, "already exists");
                    continue;
                }
                if (existing != null) existing.close();

                long credId = RepoDbManager.createCredential(
                    tokenAccount,
                    cred.optString("token_secret", "")
                );

                // Relate repos
                JSONArray relRepoArr = cred.optJSONArray("rel_repo");
                if (relRepoArr != null) {
                    for (int j = 0; j < relRepoArr.length(); j++) {
                        RepoDbManager.relateRepoWithCredential(credId, relRepoArr.getString(j));
                    }
                }

                result.addCredentialResult(tokenAccount, BackupResult.Status.SUCCESS, null);
            }
        }

        // Import preferences
        JSONObject prefs = backup.optJSONObject("preferences");
        if (prefs != null) {
            SharedPreferences sp = mContext.getSharedPreferences(
                mContext.getString(R.string.preference_file_key), Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            Iterator<String> keys = prefs.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = prefs.get(key);
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                }
            }
            editor.apply();
            result.prefsImported = true;
        }

        return result;
    }

    private Cursor searchRepoByLocalPath(String localPath) {
        // Simple search using queryAllRepo and filter
        Cursor cursor = RepoDbManager.queryAllRepo();
        if (cursor == null) return null;
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String path = RepoContract.getLocalPath(cursor);
            if (localPath.equals(path)) {
                return cursor;
            }
            cursor.moveToNext();
        }
        cursor.close();
        return null;
    }

    private Cursor searchCredentialByAccount(String tokenAccount) {
        Cursor cursor = RepoDbManager.queryAllCredential();
        if (cursor == null) return null;
        cursor.moveToFirst();
        while (!cursor.isAfterLast()) {
            String account = RepoContract.getOneTokenAccount(cursor);
            if (tokenAccount.equals(account)) {
                return cursor;
            }
            cursor.moveToNext();
        }
        cursor.close();
        return null;
    }

    private byte[] encrypt(byte[] plaintext, String password) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        // Format: [salt(16)][iv(12)][ciphertext]
        ByteBuffer buffer = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
        buffer.put(salt);
        buffer.put(iv);
        buffer.put(ciphertext);
        return buffer.array();
    }

    private byte[] decrypt(byte[] data, String password) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(salt);
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return cipher.doFinal(ciphertext);
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    public interface ExportCallback {
        void onSuccess(JSONObject backup);
        void onError(String error);
    }

    public interface ImportCallback {
        void onSuccess(BackupResult result);
        void onError(String error);
    }
}
