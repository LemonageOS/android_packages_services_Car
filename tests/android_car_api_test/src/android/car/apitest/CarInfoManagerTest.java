/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.apitest;

import android.car.Car;
import android.car.CarInfoManager;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class CarInfoManagerTest extends CarApiTestBase {

    private CarInfoManager mInfoManager;

    @Before
    public void setUp() throws Exception {
        mInfoManager = (CarInfoManager) getCar().getCarManager(Car.INFO_SERVICE);
        assertNotNull(mInfoManager);
    }

    @Test
    public void testVehicleId() throws Exception {
        assertNotNull(mInfoManager.getVehicleId());
    }

    @Test
    public void testNotNullItems() throws Exception {
        // call and check if it throws exception.
        assertNotNull(mInfoManager.getManufacturer());
        assertNotNull(mInfoManager.getModel());
        assertNotNull(mInfoManager.getModelYear());
    }
}
