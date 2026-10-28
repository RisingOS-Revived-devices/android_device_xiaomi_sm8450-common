/*
 * Copyright (C) 2025 Joshaby
 *               2026 acerhizmq
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.powerprofile;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.lineageos.settings.R;

/** Cycles the power profile: Balanced, Gaming, Performance, Battery saver. */
public class PowerProfileTileService extends TileService {

    private static final int[] ICONS = {
            R.drawable.ic_thermal_balance,
            R.drawable.ic_thermal_gaming,
            R.drawable.ic_thermal_performance,
            R.drawable.ic_thermal_battery_saver,
    };

    private PowerProfileUtil mManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mManager = new PowerProfileUtil(this);
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        mManager.toggleMode();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        int mode = mManager.getMode();
        tile.setState(mode == PowerProfileUtil.MODE_GAMING || mode == PowerProfileUtil.MODE_PERFORMANCE
                ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setIcon(Icon.createWithResource(this, ICONS[mode]));
        tile.setLabel(getString(R.string.power_profile_title));
        tile.setSubtitle(mManager.getModeLabel(mode));
        tile.updateTile();
    }

    @Override
    public void onDestroy() {
        if (mManager != null) mManager.cleanup();
        super.onDestroy();
    }
}
