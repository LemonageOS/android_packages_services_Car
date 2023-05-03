/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.car.media;

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.annotation.SystemApi;
import android.car.annotation.ApiRequirements;
import android.os.Build;

import java.util.List;

/**
 * Interface to listen to events impacting volume groups.
 *
 * @hide
 */
@SystemApi
@ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
        minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public interface CarVolumeGroupEventCallback {
    /**
     * This is called when audio framework acts on events that causes changes to
     * {@link android.car.media.CarVolumeGroupInfo}.
     * The callback includes a list of {@link android.car.media.CarVolumeGroupEvent}.
     * Each event contains event-types (what has changed), list of volume group infos
     * and additional information (why it has changed).
     */
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    void onVolumeGroupEvent(@NonNull List<CarVolumeGroupEvent> volumeGroupEvents);
}
