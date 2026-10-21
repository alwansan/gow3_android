package com.gow3.android.game.drawer;

import com.gow3.android.core.settings.FrameGenSettings;

/** GameDrawer 与 GameActivity 的解耦接口：抽屉面板只经此接口触发动作与读取当前值，
 *  GameActivity 不再被抽屉内部结构引用。Game tab 已移除（继续由拉手/返回键、重启随设置变更、
 *  退出走 rail 底部常驻图标），因此不再有 resumeGame/requestRestart。 */
public interface GameDrawerCallbacks {
    void quitGame();
    void setFrameGenEnabled(boolean enabled);
    void setFrameGenMultiplier(int multiplier);
    void setFrameGenFlowScale(float flowScale);
    void onTogglePerfHud();
    void onToggleController();
    void onEditControllerLayout();
    void onResetControllerLayout();

    /** 金手指：写入一组 cheat_* 键（ini key 即运行时 gow3_settings.cpp 的 cheat_keys）。
     *  启动后下一次重启生效（hook 在 guest 首次执行前读取）。返回是否写盘成功。 */
    boolean applyCheats(java.util.Map<String, String> updates);
    /** 当前 cheat_* 键的值（缺失时为默认 "0"）。 */
    java.util.Map<String, String> cheatStates();

    boolean isFrameGenDllPresent();
    FrameGenSettings.Config frameGenConfig();
    boolean isPerfHudEnabled();
    boolean isControllerEnabled();
}
