/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.developeroptions.network.telephony;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.util.Log;
import android.view.Gravity;

import androidx.preference.Preference;

import com.android.car.developeroptions.R;
import com.android.settingslib.graph.SignalDrawable;

import java.util.List;

/**
 * A Preference represents a network operator in the NetworkSelectSetting fragment.
 */
public class NetworkOperatorPreference extends Preference {

    private static final String TAG = "NetworkOperatorPref";
    private static final boolean DBG = false;

    private static final int LEVEL_NONE = -1;

    // number of signal strength level
    public static final int NUMBER_OF_LEVELS = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
    private CellInfo mCellInfo;
    private List<String> mForbiddenPlmns;
    private int mLevel = LEVEL_NONE;
    private boolean mShow4GForLTE;

    // The following constants are used to draw signal icon.
    private static final Drawable EMPTY_DRAWABLE = new ColorDrawable(Color.TRANSPARENT);
    private static final int NO_CELL_DATA_CONNECTED_ICON = 0;

    public NetworkOperatorPreference(
            CellInfo cellinfo, Context context, List<String> forbiddenPlmns, boolean show4GForLTE) {
        super(context);
        mCellInfo = cellinfo;
        mForbiddenPlmns = forbiddenPlmns;
        mShow4GForLTE = show4GForLTE;
        refresh();
    }

    public CellInfo getCellInfo() {
        return mCellInfo;
    }

    /**
     * Refresh the NetworkOperatorPreference by updating the title and the icon.
     */
    public void refresh() {
        if (DBG) Log.d(TAG, "refresh the network: " + CellInfoUtil.getNetworkTitle(mCellInfo));
        String networkTitle = CellInfoUtil.getNetworkTitle(mCellInfo);
        if (CellInfoUtil.isForbidden(mCellInfo, mForbiddenPlmns)) {
            networkTitle += " " + getContext().getResources().getString(R.string.forbidden_network);
        }
        setTitle(networkTitle);

        final CellSignalStrength signalStrength = mCellInfo.getCellSignalStrength();
        final int level = signalStrength != null ? signalStrength.getLevel() : LEVEL_NONE;
        if (DBG) Log.d(TAG, "refresh level: " + String.valueOf(level));
        if (mLevel != level) {
            mLevel = level;
            updateIcon(mLevel);
        }
    }

    /**
     * Update the icon according to the input signal strength level.
     */
    public void setIcon(int level) {
        updateIcon(level);
    }

    private int getIconIdForCell(CellInfo ci) {
        final int type = ci.getCellIdentity().getType();
        switch (type) {
            case CellInfo.TYPE_GSM:
                return R.drawable.signal_strength_g;
            case CellInfo.TYPE_WCDMA: // fall through
            case CellInfo.TYPE_TDSCDMA:
                return R.drawable.signal_strength_3g;
            case CellInfo.TYPE_LTE:
                return mShow4GForLTE
                        ? R.drawable.ic_signal_strength_4g : R.drawable.signal_strength_lte;
            case CellInfo.TYPE_CDMA:
                return R.drawable.signal_strength_1x;
            default:
                return 0;
        }
    }

    private void updateIcon(int level) {
        if (level < 0 || level >= NUMBER_OF_LEVELS) return;
        Context context = getContext();
        SignalDrawable signalDrawable = new SignalDrawable(getContext(), false /* statusBar */);
        signalDrawable.setLevel(
                SignalDrawable.getState(level, NUMBER_OF_LEVELS, false /* cutOut */));

        // Make the network type drawable
        int iconType = getIconIdForCell(mCellInfo);
        Drawable networkDrawable =
                iconType == NO_CELL_DATA_CONNECTED_ICON
                        ? EMPTY_DRAWABLE
                        : getContext()
                                .getResources().getDrawable(iconType, getContext().getTheme());

        // Overlay the two drawables
        final Drawable[] layers = {networkDrawable, signalDrawable};
        final int iconSize =
                context.getResources().getDimensionPixelSize(R.dimen.signal_strength_icon_size);

        LayerDrawable icons = new LayerDrawable(layers);
        // Set the network type icon at the top left
        icons.setLayerGravity(0 /* index of networkDrawable */, Gravity.TOP | Gravity.LEFT);
        // Set the signal strength icon at the bottom right
        icons.setLayerGravity(1 /* index of SignalDrawable */, Gravity.BOTTOM | Gravity.RIGHT);
        icons.setLayerSize(1 /* index of SignalDrawable */, iconSize, iconSize);
        setIcon(icons);
    }
}
