package com.gow3.android.game.drawer;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.gow3.android.R;
import java.util.HashMap;
import java.util.Map;

/** 游戏内抽屉外壳：抽屉 View 树、左侧竖排 tab rail、内容区切换、左滑动画与 scrim。
 *  GameActivity 只负责把 handle()/scrim()/view() 挂进屏幕并委托 open/close，不接触抽屉内部结构。 */
public final class GameDrawer {
    /** 关闭动画结束、抽屉已隐藏：宿主借此恢复游戏画面焦点。 */
    public interface Listener {
        void onDrawerClosed();
    }

    private static final int GOLD = 0xffc6ad76;
    private static final int LABEL_COLOR = 0xff8e9097;
    /** 抽屉面板总宽（rail + 内容列）。宿主按此摆放，滑出动画也用同一宽度。 */
    private static final int DRAWER_WIDTH_DP = 360;
    /** 左侧 tab rail 的宽度（图标 + 竖排标题）。 */
    private static final int RAIL_WIDTH_DP = 64;

    private final Context ctx;
    private final GameDrawerCallbacks callbacks;
    private final Listener listener;
    private final View handle;
    private final View scrim;
    private final LinearLayout drawer;
    private LinearLayout tabStrip;
    private LinearLayout contentColumn;
    private FrameLayout contentHost;
    private final Map<DrawerTab, DrawerPane> panes = new HashMap<>();
    private final Map<DrawerTab, View> tabButtons = new HashMap<>();
    private DrawerTab activeTab = DrawerTab.GRAPHICS;
    private boolean open;

    public GameDrawer(Context ctx, GameDrawerCallbacks callbacks, Listener listener) {
        this.ctx = ctx;
        this.callbacks = callbacks;
        this.listener = listener;
        this.handle = buildHandle();
        this.scrim = buildScrim();
        this.drawer = buildDrawer();
        registerPane(new GraphicsPane(ctx, callbacks));
        registerPane(new CheatsPane(ctx, callbacks));
        registerPane(new ControlsPane(ctx, callbacks));
        selectTab(DrawerTab.GRAPHICS, false);
    }

    /** 左缘拉手（游戏侧），GameActivity 挂入屏幕并摆放。 */
    public View handle() { return handle; }

    /** 全屏遮罩，GameActivity 挂入屏幕并摆放。 */
    public View scrim() { return scrim; }

    /** 抽屉面板根视图，GameActivity 挂入屏幕并摆放。 */
    public View view() { return drawer; }

    /** 抽屉面板宽度（dp），宿主摆放时用它，与滑出动画保持一致。 */
    public int widthDp() { return DRAWER_WIDTH_DP; }

    public boolean isOpen() { return open; }

    private void registerPane(DrawerPane pane) {
        DrawerTab tab = tabOf(pane);
        panes.put(tab, pane);
    }

    private DrawerTab tabOf(DrawerPane pane) {
        if (pane instanceof GraphicsPane) return DrawerTab.GRAPHICS;
        if (pane instanceof CheatsPane) return DrawerTab.CHEATS;
        if (pane instanceof ControlsPane) return DrawerTab.CONTROLS;
        throw new IllegalArgumentException("unknown pane " + pane);
    }

    private View buildHandle() {
        View h = new View(ctx);
        GradientDrawable grip = new GradientDrawable();
        grip.setColor(0x80c6ad76); grip.setCornerRadius(dp(4));
        h.setBackground(grip);
        h.setContentDescription(ctx.getString(R.string.drawer_handle_desc));
        h.setOnClickListener(v -> open());
        return h;
    }

    private View buildScrim() {
        View s = new View(ctx);
        s.setBackgroundColor(0x99000000);
        s.setVisibility(View.GONE);
        s.setOnClickListener(v -> close());
        return s;
    }

    private LinearLayout buildDrawer() {
        // 抽屉面板：左侧竖排 tab rail + 右侧内容列。tab 不放在顶部，避免与状态栏/刘海区域
        // 重叠（顶部横排 tab 会被系统状态栏压住，图标和文字都点不准）。
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable panel = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { 0xff1b1d24, 0xff121318 });
        root.setBackground(panel);
        root.setElevation(dp(16));
        root.setVisibility(View.GONE);

        // 左侧 rail：竖排 tab 按钮（图标在上、标题在下）。
        tabStrip = new LinearLayout(ctx);
        tabStrip.setOrientation(LinearLayout.VERTICAL);
        tabStrip.setGravity(Gravity.CENTER_HORIZONTAL);
        tabStrip.setPadding(dp(4), dp(6), dp(4), dp(6));
        root.addView(tabStrip, new LinearLayout.LayoutParams(dp(RAIL_WIDTH_DP), -1));
        buildTabStrip();

        View divider = new View(ctx);
        divider.setBackgroundColor(0x55c6ad76);
        root.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));

        // 右侧内容列：面板内容 + 底部提示。
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        contentColumn = column;

        contentHost = new FrameLayout(ctx);
        column.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部提示（原版本号位置）。
        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.drawer_hint_swipe));
        hint.setTextSize(12);
        hint.setTextColor(LABEL_COLOR);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(14), dp(10), dp(14), dp(14));
        column.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        root.addView(column, new LinearLayout.LayoutParams(0, -1, 1));

        // 系统栏是隐藏的（沉浸式），但用户下滑会临时唤出状态栏：按 inset 把 rail 与内容列
        // 内缩，tab 按钮和内容都不会被状态栏压住。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            tabStrip.setPadding(dp(4), dp(6) + top, dp(4), dp(6));
            contentColumn.setPadding(0, top, 0, bottom);
            return insets;
        });
        return root;
    }

    private void buildTabStrip() {
        for (DrawerTab tab : DrawerTab.values()) {
            DrawerTabSpec spec = DrawerTabSpec.of(tab);
            LinearLayout btn = new LinearLayout(ctx);
            btn.setOrientation(LinearLayout.VERTICAL);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(6), dp(10), dp(6), dp(10));
            btn.setFocusable(true);
            btn.setClickable(true);
            btn.setBackground(tabButtonBackground());

            ImageView icon = new ImageView(ctx);
            icon.setImageResource(spec.iconRes);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            btn.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView label = new TextView(ctx);
            label.setText(spec.labelRes);
            label.setTextSize(10);
            label.setTextColor(LABEL_COLOR);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(Gravity.CENTER);
            btn.addView(label, new LinearLayout.LayoutParams(-2, -2));

            btn.setOnClickListener(v -> selectTab(tab, true));
            tabButtons.put(tab, btn);
            tabStrip.addView(btn, new LinearLayout.LayoutParams(-1, -2));
        }
        // 退出游戏固定在 rail 底部：弹性占位把按钮压到底部，任何 tab 与滚动位置都可见。
        // 仍走确认对话框，避免在长列表里误触直接结束游戏。
        tabStrip.addView(new View(ctx), new LinearLayout.LayoutParams(-1, 0, 1));
        ImageView quit = new ImageView(ctx);
        quit.setImageResource(R.drawable.ic_exit);
        // 原图标是战神金色，压在红底上对比度不足；统一染白。
        quit.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        quit.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        quit.setPadding(dp(11), dp(11), dp(11), dp(11));
        quit.setContentDescription(ctx.getString(R.string.drawer_title_quit));
        quit.setFocusable(true);
        quit.setClickable(true);
        quit.setBackground(quitButtonBackground());
        quit.setOnClickListener(v -> confirmQuit());
        tabStrip.addView(quit, new LinearLayout.LayoutParams(dp(44), dp(44)));
    }

    /** 退出确认对话框：与原来 Game tab 的退出行为一致。 */
    private void confirmQuit() {
        new AlertDialog.Builder(ctx)
            .setTitle(R.string.drawer_confirm_quit_title)
            .setMessage(R.string.drawer_confirm_quit_message)
            .setNegativeButton(R.string.drawer_confirm_cancel, null)
            .setPositiveButton(R.string.drawer_confirm_quit_ok, (d, w) -> callbacks.quitGame())
            .show();
    }

    /** 退出按钮底色：常态战神红（破坏性操作一眼可辨），按压加深、聚焦描金。 */
    private StateListDrawable quitButtonBackground() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xffb8172b); base.setCornerRadius(dp(10));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xff8f1120); pressed.setCornerRadius(dp(10));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xffb8172b); focused.setCornerRadius(dp(10)); focused.setStroke(dp(1), 0xffc6ad76);
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[] { android.R.attr.state_focused }, focused);
        states.addState(new int[] { }, base);
        return states;
    }

    private StateListDrawable tabButtonBackground() {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable selected = new GradientDrawable();
        selected.setColor(0x26c6ad76); selected.setCornerRadius(dp(10));
        GradientDrawable lit = new GradientDrawable();
        lit.setColor(0x33c6ad76); lit.setCornerRadius(dp(10)); lit.setStroke(dp(1), 0x99c6ad76);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x44c6ad76); pressed.setCornerRadius(dp(10));
        states.addState(new int[] { android.R.attr.state_pressed }, pressed);
        states.addState(new int[] { android.R.attr.state_selected }, selected);
        states.addState(new int[] { android.R.attr.state_focused }, lit);
        states.addState(new int[] {}, new ColorDrawable(0));
        return states;
    }

    /** 切换当前 tab：刷新 tab 选中态、换内容区、可选地把焦点移入新 tab 首行。 */
    private void selectTab(DrawerTab tab, boolean focus) {
        activeTab = tab;
        for (Map.Entry<DrawerTab, View> e : tabButtons.entrySet()) {
            boolean active = e.getKey() == tab;
            android.view.ViewGroup b = (android.view.ViewGroup) e.getValue();
            b.setSelected(active);
            if (b.getChildCount() > 1) {
                ((TextView) b.getChildAt(1)).setTextColor(active ? GOLD : LABEL_COLOR);
            }
        }
        DrawerPane pane = panes.get(tab);
        pane.refresh();
        contentHost.removeAllViews();
        contentHost.addView(pane.content());
        if (focus) {
            final DrawerPane p = pane;
            contentHost.post(() -> p.firstFocusTarget().requestFocus());
        }
    }

    /** 打开抽屉：释放由宿主完成，这里负责 scrim/拉手/面板动画与内容刷新。 */
    public void open() {
        if (open) return;
        open = true;
        for (DrawerPane pane : panes.values()) pane.refresh();
        handle.setVisibility(View.GONE);
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.setAlpha(0); scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1).setDuration(200).start();
        drawer.setTranslationX(-dp(DRAWER_WIDTH_DP));
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(220).start();
        // 动画结束后把焦点交给当前 tab 首行（手柄 A 确认语义沿用现有 dispatchKeyEvent）。
        drawer.postDelayed(() -> {
            if (open) panes.get(activeTab).firstFocusTarget().requestFocus();
        }, 240);
    }

    /** 关闭抽屉：scrim 淡出、面板滑出，结束后回调 Listener 供宿主恢复游戏画面焦点。 */
    public void close() {
        if (!open) return;
        open = false;
        scrim.animate().cancel(); drawer.animate().cancel();
        scrim.animate().alpha(0).setDuration(180)
            .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
        drawer.animate().translationX(-dp(DRAWER_WIDTH_DP)).setDuration(200)
            .withEndAction(() -> {
                drawer.setVisibility(View.GONE);
                handle.setVisibility(View.VISIBLE);
                if (listener != null) listener.onDrawerClosed();
            }).start();
    }

    private int dp(float n) { return Math.round(n * ctx.getResources().getDisplayMetrics().density); }
}
