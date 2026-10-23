package ts.realms.m2git.core.command;

import android.content.Context;

/**
 * {@link SyncRunner} 运行所需的最小宿主能力。
 * <p>
 * sync 原本硬绑 {@code RepoDetailActivity}（用它的内部类 ProgressCallback、reset() 等），
 * 导致无法从仓库列表触发。这里把 SyncRunner 真正依赖的几项抽成接口，让详情页与列表页
 * 都能各自实现。
 * <p>
 * 进度呈现统一为「全屏进度对话框 + 结束弹结果对话框」，且**仅作用于 sync**：详情页 pull/push/fetch
 * 等其它操作仍走各自 {@code new ProgressCallback(...)} 的底部卡片，不受影响。三个 progress 方法与
 * {@code showSyncResult} 都在 {@code BaseCompatActivity} 上提供了共享实现，两个 Activity 继承即可。
 * <p>
 * {@code showToastMessage(String)} / {@code showMessageDialog(int, String)} /
 * {@code getApplicationContext()} 在 {@code BaseCompatActivity}（或 Context）上已有兼容实现。
 */
public interface SyncHost {

    /** 显示全屏进度对话框（幂等：已显示则只更新初始文案）。 */
    void showSyncProgress(int initMsgRes);

    /** 更新进度对话框内容。percent 取 0..100。 */
    void updateSyncProgress(String msg, String leftHint, String rightHint, int percent);

    /** 关闭进度对话框。 */
    void dismissSyncProgress();

    /** 一次 sync 结束后刷新宿主 UI（详情页重置三个 fragment；列表页重查列表）。 */
    void reset();

    void showToastMessage(String msg);

    void showMessageDialog(int titleRes, String msg);

    Context getApplicationContext();
}
