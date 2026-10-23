package ts.realms.m2git.core.command.actions;

import ts.realms.m2git.core.command.RepoAction;
import ts.realms.m2git.core.command.SyncRunner;
import ts.realms.m2git.core.models.Repo;
import ts.realms.m2git.ui.screens.repoDetail.RepoDetailActivity;

/**
 * 详情页「Sync」菜单项的入口。实际编排逻辑已抽到 {@link SyncRunner}（依赖 SyncHost 接口），
 * 以便仓库列表也能复用同一套流程；这里只是保持 {@link RepoAction} 层级与
 * {@code RepoOperationDelegate} 的下标映射不变（Sync 恒在 index 0）。
 */
public class SyncRepoAction extends RepoAction {

    public SyncRepoAction(Repo repo, RepoDetailActivity activity) {
        super(repo, activity);
    }

    @Override
    public void execute() {
        new SyncRunner(mRepo, mActivity).run();
    }
}
