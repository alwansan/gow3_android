package com.gow3.android.game.drawer;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import com.gow3.android.R;

/** tab 的规格表：把 DrawerTab 映射到图标与文案资源，供 rail 构建使用。 */
public final class DrawerTabSpec {
    @StringRes public final int labelRes;
    @DrawableRes public final int iconRes;

    private DrawerTabSpec(@StringRes int labelRes, @DrawableRes int iconRes) {
        this.labelRes = labelRes;
        this.iconRes = iconRes;
    }

    public static DrawerTabSpec of(DrawerTab tab) {
        switch (tab) {
            case GRAPHICS: return new DrawerTabSpec(R.string.drawer_tab_graphics, R.drawable.ic_tune);
            case CHEATS: return new DrawerTabSpec(R.string.drawer_tab_cheats, R.drawable.ic_cheats);
            case CONTROLS: return new DrawerTabSpec(R.string.drawer_tab_controls, R.drawable.ic_gamepad);
        }
        throw new IllegalArgumentException("unknown tab " + tab);
    }
}
