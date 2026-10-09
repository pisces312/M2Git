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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import ts.realms.m2git.R;
import ts.realms.m2git.core.models.Tag;
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
        // version 2：加 tags 注册表 + 每仓库的 tags / time_added。
        // 读侧不需要版本分支：v1 文件缺这些键，走 opt* 的空值路径（标签为空、时间保留本地）；
        // v2 文件被旧版打开时未知键被忽略，只丢标签，不崩。
        backup.put("version", 2);
        backup.put("export_time", System.currentTimeMillis());

        // 标签注册表按 sort_order 出（queryAllTags 已排序），导入按这个顺序建，
        // 新设备上的相对顺序就复原了。名字是身份，_id 不导出 —— 跨设备 _id 必然不同。
        JSONArray tags = new JSONArray();
        for (Tag tag : Tag.getTagList(RepoDbManager.queryAllTags())) {
            JSONObject entry = new JSONObject();
            entry.put("name", tag.getName());
            entry.put("sort_order", tag.getSortOrder());
            tags.put(entry);
        }
        backup.put("tags", tags);
        Map<Long, List<String>> repoTagNames = RepoDbManager.queryRepoTagMap();

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
                // 入库时间必须导出：否则还原后「按添加时间排序」全是还原当天，完全失真
                Date timeAdded = RepoContract.getTimeAdded(repoCursor);
                repo.put("time_added", timeAdded != null ? timeAdded.getTime() : 0);
                JSONArray repoTags = new JSONArray();
                List<String> names = repoTagNames.get(
                    (long) RepoContract.getRepoID(repoCursor));
                if (names != null) {
                    for (String name : names) {
                        repoTags.put(name);
                    }
                }
                repo.put("tags", repoTags);
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

        // 标签注册表先建：数组顺序就是导出时的 sort_order，按序创建即复原相对顺序。
        // 身份是名字（createTagIfAbsent 复用同名标签），所以旧设备上的 _id 完全不参与。
        JSONArray tagRegistry = backup.optJSONArray("tags");
        if (tagRegistry != null) {
            for (int i = 0; i < tagRegistry.length(); i++) {
                long tagId = RepoDbManager.createTagIfAbsent(
                    tagRegistry.getJSONObject(i).optString("name", ""));
                if (tagId >= 0) result.tagsRegistered++;
            }
        }

        // Import repos
        JSONArray repos = backup.optJSONArray("repos");
        // 本地已有标签只读一次，合并时按 repoId 查，免得每个仓库一条查询
        Map<Long, List<String>> localTags = RepoDbManager.queryRepoTagMap();
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
                boolean existsBefore = existing != null && existing.getCount() > 0;
                long repoId = existsBefore ? RepoContract.getRepoID(existing) : -1;
                if (existing != null) existing.close();

                // 已存在的行过去是整条跳过，标签会跟着丢；现在仍报 SKIPPED，但标签和时间照常合并
                if (!existsBefore) {
                    repoId = RepoDbManager.createRepo(
                        localPath,
                        repo.optString("remote_url", ""),
                        repo.optString("repo_status", "")
                    );
                }

                android.content.ContentValues values = new android.content.ContentValues();
                if (!existsBefore) {
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_USERNAME, repo.optString("username", ""));
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_PASSWORD, repo.optString("password", ""));
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_UNAME, repo.optString("latest_committer_uname", ""));
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMITTER_EMAIL, repo.optString("latest_committer_email", ""));
                    long dateVal = repo.optLong("latest_commit_date", 0);
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_DATE, dateVal > 0 ? String.valueOf(dateVal) : "");
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_LATEST_COMMIT_MSG, repo.optString("latest_commit_msg", ""));
                }
                // createRepo 固定写 System.currentTimeMillis()，还原必须用备份值覆盖回去，
                // 否则「按添加时间排序」在还原后全是还原当天。这是「唯一写入点」的必然例外。
                // 0 / 缺键（v1 文件）表示「不知道」，保留本地时间而不是写入 epoch。
                long timeAdded = repo.optLong("time_added", 0);
                if (timeAdded > 0) {
                    values.put(RepoContract.RepoEntry.COLUMN_NAME_TIME_ADDED, timeAdded);
                }
                if (!existsBefore || timeAdded > 0) {
                    RepoDbManager.updateRepo(repoId, values);
                }

                applyRepoTags(result, repoId, repo.optJSONArray("tags"), localTags);

                result.addRepoResult(localPath, existsBefore ? BackupResult.Status.SKIPPED
                    : BackupResult.Status.SUCCESS, existsBefore ? "exists, tags merged" : null);
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

    /**
     * 备份里的标签并到本地已有标签上，而不是覆盖：还原的语义是「补全」，
     * 用户在本机新加的标签不该因为导入了一次旧备份而消失。setRepoTags 是先删后插且
     * 关系行 INSERT OR IGNORE，所以喂并集是幂等的。
     */
    private void applyRepoTags(BackupResult result, long repoId, JSONArray backupTags,
        Map<Long, List<String>> localTags) throws JSONException {
        if (backupTags == null || backupTags.length() == 0) return;
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        List<String> current = localTags.get(repoId);
        if (current != null) merged.addAll(current);
        for (int i = 0; i < backupTags.length(); i++) {
            // 只做 trim，与库里的身份规则一致；不做大小写归一，那会让同名标签在两台设备上分叉
            String name = backupTags.optString(i, "");
            if (name != null && !name.trim().isEmpty()) {
                merged.add(name.trim());
            }
        }
        if (merged.isEmpty()) return;
        List<String> names = new ArrayList<>(merged);
        RepoDbManager.setRepoTags(repoId, names);
        localTags.put(repoId, names);
        result.reposTagged++;
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
