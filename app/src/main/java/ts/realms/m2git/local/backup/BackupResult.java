package ts.realms.m2git.local.backup;

import java.util.ArrayList;
import java.util.List;

public class BackupResult {

    public enum Status {
        SUCCESS, SKIPPED, FAILED
    }

    public static class ItemResult {
        public final String name;
        public final Status status;
        public final String reason;

        public ItemResult(String name, Status status, String reason) {
            this.name = name;
            this.status = status;
            this.reason = reason;
        }
    }

    public final List<ItemResult> repoResults = new ArrayList<>();
    public final List<ItemResult> credentialResults = new ArrayList<>();
    public boolean prefsImported = false;
    /** 注册表里成功落地（新建或按同名复用）的标签数。 */
    public int tagsRegistered = 0;
    /** 至少并上一个备份标签的仓库行数（合并，不是覆盖）。 */
    public int reposTagged = 0;

    public void addRepoResult(String name, Status status, String reason) {
        repoResults.add(new ItemResult(name, status, reason));
    }

    public void addCredentialResult(String name, Status status, String reason) {
        credentialResults.add(new ItemResult(name, status, reason));
    }

    public int getRepoSuccessCount() {
        return (int) repoResults.stream().filter(r -> r.status == Status.SUCCESS).count();
    }

    public int getRepoSkippedCount() {
        return (int) repoResults.stream().filter(r -> r.status == Status.SKIPPED).count();
    }

    public int getRepoFailedCount() {
        return (int) repoResults.stream().filter(r -> r.status == Status.FAILED).count();
    }

    public int getCredentialSuccessCount() {
        return (int) credentialResults.stream().filter(r -> r.status == Status.SUCCESS).count();
    }

    public int getCredentialSkippedCount() {
        return (int) credentialResults.stream().filter(r -> r.status == Status.SKIPPED).count();
    }

    public int getCredentialFailedCount() {
        return (int) credentialResults.stream().filter(r -> r.status == Status.FAILED).count();
    }

    public String getSummary() {
        return String.format(
            "Repos: %d success, %d skipped, %d failed | Tags: %d registered, %d repos tagged | "
                + "Credentials: %d success, %d skipped, %d failed | Prefs: %s",
            getRepoSuccessCount(), getRepoSkippedCount(), getRepoFailedCount(),
            tagsRegistered, reposTagged,
            getCredentialSuccessCount(), getCredentialSkippedCount(), getCredentialFailedCount(),
            prefsImported ? "imported" : "not imported"
        );
    }
}
