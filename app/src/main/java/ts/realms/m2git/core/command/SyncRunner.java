package ts.realms.m2git.core.command;

import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import ts.realms.m2git.R;
import ts.realms.m2git.core.command.tasks.local.AddToStageTask;
import ts.realms.m2git.core.command.tasks.local.CommitChangesTask;
import ts.realms.m2git.core.command.tasks.remote.FetchTask;
import ts.realms.m2git.core.command.tasks.remote.PullTask;
import ts.realms.m2git.core.command.tasks.remote.PushTask;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.local.preference.Profile;
import ts.realms.m2git.ui.common.errors.StopTaskException;

/**
 * sync 的实际编排逻辑：fetch → status/revwalk 比对 → 视情况 add/commit/push 或 pull。
 * <p>
 * 从 {@code SyncRepoAction} 抽出，改为依赖 {@link SyncHost} 而非具体的 Activity，
 * 于是详情页与仓库列表都能触发同一套流程，并共用「全屏进度对话框 + 结束弹精简结果对话框」。
 * <p>
 * **git 行为与原实现完全一致**（含 commit message 用时间戳、diverged 只提示不处理、
 * local_new 分支的两条链），改动只在呈现层：原来散落的过程 toast 收敛成终点的一个结果对话框。
 */
public class SyncRunner {

    private final Repo mRepo;
    private final SyncHost mHost;

    // 精简结果累积：状态分类 + 已执行动作（去重、保序）+ 成功/失败，终点弹一次对话框。
    private String mStatusDesc = "";
    private final Set<String> mActions = new LinkedHashSet<>();
    // local_new 分支存在两条 stage/commit/push 链（沿用原语义），会触发两次终点回调；
    // 用这个守卫保证结果对话框只弹一次。
    private boolean mResultShown = false;

    public SyncRunner(Repo repo, SyncHost host) {
        mRepo = repo;
        mHost = host;
    }

    public void run() {
        mHost.showSyncProgress(R.string.fetch_msg_init);
        try {
            Repository repository = mRepo.getGit().getRepository();
            String[] originRemotes = mRepo.getRemotes().toArray(new String[0]);
            FetchTask fetchTask = new FetchTask(originRemotes, mRepo,
                progressCallback(R.string.fetch_msg_init), fetchResult -> {
                SyncStatus result;
                try {
                    if (fetchResult == null) {
                        mStatusDesc = str(R.string.sync_status_no_remote);
                        showResult(str(R.string.sync_result_failure));
                        return;
                    }

                    Ref remoteRef = fetchResult.getAdvertisedRef(mRepo.getBranchName());
                    if (remoteRef == null) {
                        mStatusDesc = str(R.string.sync_status_no_remote_ref);
                        showResult(str(R.string.sync_result_failure));
                        return;
                    }

                    // 比较时间仅用于两种情况，仅本地有更新或仅远程有更新，但存在计算机时间错乱导致比原来的提交时间晚。
                    Status status = mRepo.getGit().status().call();
                    RevWalk revWalk = new RevWalk(repository);
                    ObjectId remoteHeadObjectID = remoteRef.getObjectId();
                    ObjectId localHeadObjectID = repository.resolve("HEAD");
                    RevCommit remoteHeadCommit = revWalk.parseCommit(remoteHeadObjectID);
                    RevCommit localHeadCommit = revWalk.parseCommit(localHeadObjectID);

                    if (localHeadCommit.equals(remoteHeadCommit)) {
                        result = SyncStatus.synced;
                    } else if (revWalk.isMergedInto(localHeadCommit, remoteHeadCommit)) {
                        result = SyncStatus.remote_new;
                    } else if (revWalk.isMergedInto(remoteHeadCommit, localHeadCommit)) {
                        result = SyncStatus.local_new;
                    } else {
                        result = SyncStatus.diverged;
                    }

                    boolean localChanged = status.hasUncommittedChanges() || !status.isClean();
                    switch (result) {
                        case synced:
                            mStatusDesc = str(R.string.sync_status_synced);
                            if (localChanged) {
                                // 暂存 提交，推送
                                stage(stage -> commit(commit -> push()));
                            } else {
                                showResult(str(R.string.sync_result_up_to_date));
                            }
                            break;
                        case local_new:
                            mStatusDesc = str(R.string.sync_status_local_new);
                            if (localChanged) {
                                // 暂存、提交、推送，这时是推送多个commit。
                                stage(stage -> commit(commit -> push()));
                            }
                            commit(commit -> push());
                            break;
                        case remote_new:
                            mStatusDesc = str(R.string.sync_status_remote_new);
                            if (localChanged) {
                                // 有两种情况，冲突和无冲突。建议在修改之前先拉取，即可避免这种情况。
                                showResult(str(R.string.sync_result_skipped_dirty));
                                return;
                            }
                            // TODO remote指推送到那个远程例如origin。或许需要一个默认的远程名称。
                            String remote = mRepo.getRemotes().iterator().next();
                            mActions.add(str(R.string.sync_action_pull));
                            PullTask pullTask = new PullTask(mRepo, remote, false,
                                terminalCallback(R.string.pull_msg_init));
                            pullTask.executeTask();
                            // 拉取。
                            break;
                        case diverged:
                            mStatusDesc = str(R.string.sync_status_diverged);
                            showResult(str(R.string.sync_result_skipped_diverged));
                            break;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e.fillInStackTrace());
                } catch (GitAPIException | StopTaskException e) {
                    throw new RuntimeException(e);
                }

            });
            fetchTask.executeTask();
        } catch (StopTaskException e) {
            throw new RuntimeException(e);
        }
    }

    private void stage(Consumer<Void> nextStep) {
        mActions.add(str(R.string.sync_action_stage));
        mHost.updateSyncProgress(str(R.string.sync_msg_staging), null, null, 0);
        AddToStageTask addTask = new AddToStageTask(mRepo, ".", isSuccess -> {
            if (nextStep != null) {
                nextStep.accept(null);
            }
        });
        addTask.executeTask();
    }

    private void commit(Consumer<Void> nextStep) {
        //Git用户配置来自首选项中的设置
        String profileUsername = Profile.getUsername(mHost.getApplicationContext());
        String profileEmail = Profile.getEmail(mHost.getApplicationContext());
        if (!(profileUsername != null && !profileUsername.isEmpty() && profileEmail != null && !profileEmail.isEmpty())) {
            // 为空的话只能结束然后提示用户设置。关掉进度并标记已收尾，避免进度对话框悬挂。
            mHost.dismissSyncProgress();
            mHost.showMessageDialog(R.string.dialog_commit_title, str(R.string.sync_msg_profile_missing));
            mResultShown = true;
            return;
        }
        mActions.add(str(R.string.sync_action_commit));
        mHost.updateSyncProgress(str(R.string.sync_msg_committing), null, null, 0);
        String nowDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(LocalDate.now());
        String nowTime = DateTimeFormatter.ofPattern("HHmm").format(LocalTime.now());
        CommitChangesTask commitTask = new CommitChangesTask(mRepo, nowDate + "-" + nowTime,
            false, false, profileUsername, profileEmail, isCommitChangesSuccess -> {
            if (nextStep != null) {
                nextStep.accept(null);
            }
        });
        commitTask.executeTask();
    }

    private void push() {
        mActions.add(str(R.string.sync_action_push));
        mHost.reset();
        // TODO remote指推送到那个远程例如origin。或许需要一个默认的远程名称。
        String remote = mRepo.getRemotes().iterator().next();
        // suppressResultDialog=true：sync 用统一结果对话框，屏蔽 PushTask 自带的 push 结果框，避免双弹。
        PushTask pushTask = new PushTask(mRepo, remote, true, false,
            terminalCallback(R.string.push_msg_init), true);
        pushTask.executeTask();
    }

    /** 过程型回调：只驱动全屏进度对话框，不负责收尾（用于 fetch）。 */
    private MAsyncTask.AsyncTaskCallback progressCallback(int initMsgRes) {
        return new MAsyncTask.AsyncTaskCallback() {
            @Override
            public void onPreExecute() {
                mHost.showSyncProgress(initMsgRes);
            }

            @Override
            public boolean doInBackground(Void... params) {
                return true;
            }

            @Override
            public void onPostExecute(Boolean isSuccess) {
            }

            @Override
            public void onProgressUpdate(String... progress) {
                forwardProgress(progress);
            }
        };
    }

    /** 终点型回调：完成时收尾（关进度 + 弹结果对话框），用于 pull / push。 */
    private MAsyncTask.AsyncTaskCallback terminalCallback(int initMsgRes) {
        return new MAsyncTask.AsyncTaskCallback() {
            @Override
            public void onPreExecute() {
                mHost.showSyncProgress(initMsgRes);
            }

            @Override
            public boolean doInBackground(Void... params) {
                return true;
            }

            @Override
            public void onPostExecute(Boolean isSuccess) {
                showResult(str(isSuccess != null && isSuccess
                    ? R.string.sync_result_success : R.string.sync_result_failure));
            }

            @Override
            public void onProgressUpdate(String... progress) {
                forwardProgress(progress);
            }
        };
    }

    private void forwardProgress(String... progress) {
        if (progress == null || progress.length < 4) return;
        int percent;
        try {
            percent = Integer.parseInt(progress[3]);
        } catch (NumberFormatException e) {
            percent = 0;
        }
        mHost.updateSyncProgress(progress[0], progress[1], progress[2], percent);
    }

    /** 收尾：只弹一次。关进度 → 刷新宿主 → 弹精简结果对话框。 */
    private void showResult(String resultText) {
        if (mResultShown) return;
        mResultShown = true;
        mHost.dismissSyncProgress();
        mHost.reset();
        String actions = mActions.isEmpty()
            ? str(R.string.sync_action_none)
            : String.join(str(R.string.sync_actions_separator), mActions);
        String detail = str(R.string.sync_result_detail,
            mStatusDesc.isEmpty() ? str(R.string.sync_status_unknown) : mStatusDesc,
            actions, resultText);
        mHost.showMessageDialog(R.string.sync_result_title, detail);
    }

    private String str(int resId, Object... args) {
        return mHost.getApplicationContext().getString(resId, args);
    }

    enum SyncStatus {
        synced, local_new, remote_new, diverged
    }
}
