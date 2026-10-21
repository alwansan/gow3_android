package com.gow3.android.game.drawer;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.gow3.android.R;

/** Cheats tab：7 个运行时作弊开关（cheat_* ini 键，与 gow3_settings.cpp 的 cheat_keys 一致）。
 *  金手指 hook 在 guest 代码首次执行前读取 ini，所以更改后下一次重启游戏生效；
 *  游戏进行中的实时开关由运行时 overlay 页签负责（本移植暂不暴露）。 */
final class CheatsPane implements DrawerPane {
    private static final String[] CHEAT_KEYS = {"cheat_health", "cheat_magic", "cheat_item", "cheat_rage",
        "cheat_orbs", "cheat_very_hard", "cheat_double_jumps"};
    private static final int[] CHEAT_LABELS = {
        R.string.cheat_health, R.string.cheat_magic, R.string.cheat_item, R.string.cheat_rage,
        R.string.cheat_orbs, R.string.cheat_very_hard, R.string.cheat_double_jumps,
    };
    private static final int[] CHEAT_DETAILS = {
        R.string.cheat_health_detail, R.string.cheat_magic_detail, R.string.cheat_item_detail,
        R.string.cheat_rage_detail, R.string.cheat_orbs_detail, R.string.cheat_very_hard_detail,
        R.string.cheat_double_jumps_detail,
    };

    private final Context ctx;
    private final GameDrawerCallbacks cb;
    private final ScrollView scroll;
    private final DrawerRow[] rows = new DrawerRow[CHEAT_KEYS.length];

    CheatsPane(Context ctx, GameDrawerCallbacks cb) {
        this.ctx = ctx;
        this.cb = cb;
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx,14), dp(ctx,10), dp(ctx,14), dp(ctx,16));

        for (int i = 0; i < CHEAT_KEYS.length; i++) {
            final int index = i;
            DrawerRow row = new DrawerRow(ctx, 0, CHEAT_LABELS[i], null, () -> toggle(index));
            rows[i] = row;
            content.addView(row.view());
        }

        TextView note = new TextView(ctx);
        note.setText(R.string.cheat_note);
        note.setTextSize(12);
        note.setTextColor(0xff8e9097);
        note.setPadding(dp(ctx,14), dp(ctx,4), dp(ctx,14), dp(ctx,10));
        content.addView(note);

        scroll = new ScrollView(ctx);
        scroll.setFillViewport(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
    }

    private void toggle(int index) {
        java.util.Map<String, String> states = cb.cheatStates();
        boolean on = "1".equals(states.get(CHEAT_KEYS[index]));
        java.util.Map<String, String> update = new java.util.HashMap<>();
        update.put(CHEAT_KEYS[index], on ? "0" : "1");
        cb.applyCheats(update);
        refresh();
    }

    @Override public View content() { return scroll; }

    @Override public View firstFocusTarget() { return rows[0].focusTarget(); }

    @Override public void refresh() {
        java.util.Map<String, String> states = cb.cheatStates();
        for (int i = 0; i < CHEAT_KEYS.length; i++) {
            boolean on = "1".equals(states.get(CHEAT_KEYS[i]));
            rows[i].setDetail(ctx.getString(on
                ? R.string.drawer_detail_on : R.string.drawer_detail_off));
        }
    }

    private static int dp(Context ctx, float n) {
        return Math.round(n * ctx.getResources().getDisplayMetrics().density);
    }
}
