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
            "Repos: %d success, %d skipped, %d failed | Credentials: %d success, %d skipped, %d failed | Prefs: %s",
            getRepoSuccessCount(), getRepoSkippedCount(), getRepoFailedCount(),
            getCredentialSuccessCount(), getCredentialSkippedCount(), getCredentialFailedCount(),
            prefsImported ? "imported" : "not imported"
        );
    }
}
