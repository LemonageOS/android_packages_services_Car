/*
 * Copyright 2021 The Android Open Source Project
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

#include "IoOveruseMonitor.h"
#include "MockIoOveruseConfigs.h"
#include "MockPackageInfoResolver.h"
#include "MockProcDiskStats.h"
#include "MockResourceOveruseListener.h"
#include "MockUidIoStats.h"
#include "MockWatchdogServiceHelper.h"

#include <binder/IPCThreadState.h>
#include <binder/Status.h>
#include <utils/RefBase.h>

namespace android {
namespace automotive {
namespace watchdog {

constexpr size_t kTestMonitorBufferSize = 3;
constexpr double kTestIoOveruseWarnPercentage = 80;
constexpr std::chrono::seconds kTestMonitorInterval = 5s;

using ::android::IPCThreadState;
using ::android::RefBase;
using ::android::String16;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::PackageIdentifier;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PackageIoOveruseStats;
using ::android::automotive::watchdog::internal::UidType;
using ::android::base::Result;
using ::android::base::StringAppendF;
using ::android::binder::Status;
using ::testing::_;
using ::testing::AllOf;
using ::testing::DoAll;
using ::testing::Field;
using ::testing::Return;
using ::testing::ReturnRef;
using ::testing::SaveArg;
using ::testing::UnorderedElementsAreArray;
using ::testing::Value;

namespace {

IoOveruseAlertThreshold toIoOveruseAlertThreshold(const int64_t durationInSeconds,
                                                  const int64_t writtenBytesPerSecond) {
    IoOveruseAlertThreshold threshold;
    threshold.durationInSeconds = durationInSeconds;
    threshold.writtenBytesPerSecond = writtenBytesPerSecond;
    return threshold;
}

PackageIdentifier constructPackageIdentifier(const char* packageName, const int32_t uid) {
    PackageIdentifier packageIdentifier;
    packageIdentifier.name = String16(String8(packageName));
    packageIdentifier.uid = uid;
    return packageIdentifier;
}

PackageInfo constructPackageInfo(const char* packageName, const int32_t uid,
                                 const UidType uidType) {
    PackageInfo packageInfo;
    packageInfo.packageIdentifier = constructPackageIdentifier(packageName, uid);
    packageInfo.uidType = uidType;
    return packageInfo;
}

PerStateBytes constructPerStateBytes(const int64_t fgBytes, const int64_t bgBytes,
                                     const int64_t gmBytes) {
    PerStateBytes perStateBytes;
    perStateBytes.foregroundBytes = fgBytes;
    perStateBytes.backgroundBytes = bgBytes;
    perStateBytes.garageModeBytes = gmBytes;
    return perStateBytes;
}

IoOveruseStats constructIoOveruseStats(const bool isKillable, const PerStateBytes& remaining,
                                       const PerStateBytes& written, const int totalOveruses,
                                       const int64_t startTime, const int64_t durationInSeconds) {
    IoOveruseStats stats;
    stats.killableOnOveruse = isKillable;
    stats.remainingWriteBytes = remaining;
    stats.startTime = startTime;
    stats.durationInSeconds = durationInSeconds;
    stats.writtenBytes = written;
    stats.totalOveruses = totalOveruses;

    return stats;
}

ResourceOveruseStats constructResourceOveruseStats(IoOveruseStats ioOveruseStats) {
    ResourceOveruseStats stats;
    stats.set<ResourceOveruseStats::ioOveruseStats>(ioOveruseStats);
    return stats;
}

PackageIoOveruseStats constructPackageIoOveruseStats(
        const int32_t uid, const bool shouldNotify, const bool isKillable,
        const PerStateBytes& remaining, const PerStateBytes& written, const int totalOveruses,
        const int64_t startTime, const int64_t durationInSeconds) {
    PackageIoOveruseStats stats;
    stats.uid = uid;
    stats.shouldNotify = shouldNotify;
    stats.ioOveruseStats = constructIoOveruseStats(isKillable, remaining, written, totalOveruses,
                                                   startTime, durationInSeconds);

    return stats;
}

class ScopedChangeCallingUid : public RefBase {
public:
    explicit ScopedChangeCallingUid(uid_t uid) {
        mCallingUid = IPCThreadState::self()->getCallingUid();
        mCallingPid = IPCThreadState::self()->getCallingPid();
        if (mCallingUid == uid) {
            return;
        }
        mChangedUid = uid;
        int64_t token = ((int64_t)mChangedUid << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }
    ~ScopedChangeCallingUid() {
        if (mCallingUid == mChangedUid) {
            return;
        }
        int64_t token = ((int64_t)mCallingUid << 32) | mCallingPid;
        IPCThreadState::self()->restoreCallingIdentity(token);
    }

private:
    uid_t mCallingUid;
    uid_t mChangedUid;
    pid_t mCallingPid;
};

std::string toString(const std::vector<PackageIoOveruseStats>& ioOveruseStats) {
    if (ioOveruseStats.empty()) {
        return "empty";
    }
    std::string buffer;
    for (const auto& stats : ioOveruseStats) {
        StringAppendF(&buffer, "%s\n", stats.toString().c_str());
    }
    return buffer;
}

}  // namespace

namespace internal {

class IoOveruseMonitorPeer : public RefBase {
public:
    explicit IoOveruseMonitorPeer(const sp<IoOveruseMonitor>& ioOveruseMonitor) :
          mIoOveruseMonitor(ioOveruseMonitor) {}

    Result<void> init(const sp<IIoOveruseConfigs>& ioOveruseConfigs,
                      const sp<IPackageInfoResolverInterface>& packageInfoResolver) {
        if (const auto result = mIoOveruseMonitor->init(); !result.ok()) {
            return result;
        }
        mIoOveruseMonitor->mPeriodicMonitorBufferSize = kTestMonitorBufferSize;
        mIoOveruseMonitor->mIoOveruseWarnPercentage = kTestIoOveruseWarnPercentage;
        mIoOveruseMonitor->mIoOveruseConfigs = ioOveruseConfigs;
        mIoOveruseMonitor->mPackageInfoResolver = packageInfoResolver;
        return {};
    }

private:
    sp<IoOveruseMonitor> mIoOveruseMonitor;
};

}  // namespace internal

class IoOveruseMonitorTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mMockWatchdogServiceHelper = new MockWatchdogServiceHelper();
        mMockIoOveruseConfigs = new MockIoOveruseConfigs();
        mMockPackageInfoResolver = new MockPackageInfoResolver();
        mIoOveruseMonitor = new IoOveruseMonitor(mMockWatchdogServiceHelper);
        mIoOveruseMonitorPeer = new internal::IoOveruseMonitorPeer(mIoOveruseMonitor);
        mIoOveruseMonitorPeer->init(mMockIoOveruseConfigs, mMockPackageInfoResolver);
    }

    virtual void TearDown() {
        mMockWatchdogServiceHelper.clear();
        mMockIoOveruseConfigs.clear();
        mMockPackageInfoResolver.clear();
        mIoOveruseMonitor.clear();
        mIoOveruseMonitorPeer.clear();
    }

    void executeAsUid(uid_t uid, std::function<void()> func) {
        sp<ScopedChangeCallingUid> scopedChangeCallingUid = new ScopedChangeCallingUid(uid);
        ASSERT_NO_FATAL_FAILURE(func());
    }

    sp<MockWatchdogServiceHelper> mMockWatchdogServiceHelper;
    sp<MockIoOveruseConfigs> mMockIoOveruseConfigs;
    sp<MockPackageInfoResolver> mMockPackageInfoResolver;
    sp<IoOveruseMonitor> mIoOveruseMonitor;
    sp<internal::IoOveruseMonitorPeer> mIoOveruseMonitorPeer;
};

TEST_F(IoOveruseMonitorTest, TestOnPeriodicCollection) {
    std::unordered_map<uid_t, PackageInfo> packageInfoMapping = {
            {1001000,
             constructPackageInfo(
                     /*packageName=*/"system.daemon", /*uid=*/1001000, UidType::NATIVE)},
            {1112345,
             constructPackageInfo(
                     /*packageName=*/"com.android.google.package", /*uid=*/1112345,
                     UidType::APPLICATION)},
            {1212345,
             constructPackageInfo(
                     /*packageName=*/"com.android.google.package", /*uid=*/1212345,
                     UidType::APPLICATION)},
            {1113999,
             constructPackageInfo(
                     /*packageName=*/"com.android.google.package", /*uid=*/1113999,
                     UidType::APPLICATION)},
    };
    ON_CALL(*mMockPackageInfoResolver, getPackageInfosForUids(_))
            .WillByDefault(Return(packageInfoMapping));
    mMockIoOveruseConfigs->injectPackageConfigs({
            {"system.daemon",
             {constructPerStateBytes(/*fgBytes=*/80'000, /*bgBytes=*/40'000, /*gmBytes=*/100'000),
              /*isSafeToKill=*/false}},
            {"com.android.google.package",
             {constructPerStateBytes(/*fgBytes=*/70'000, /*bgBytes=*/30'000, /*gmBytes=*/100'000),
              /*isSafeToKill=*/true}},
    });

    sp<MockResourceOveruseListener> mockResourceOveruseListener = new MockResourceOveruseListener();
    ASSERT_NO_FATAL_FAILURE(executeAsUid(1001000, [&]() {
        ASSERT_RESULT_OK(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener));
    }));

    /*
     * Package "system.daemon" (UID: 1001000) exceeds warn threshold percentage of 80% but no
     * warning is issued as it is a native UID.
     */
    sp<MockUidIoStats> mockUidIoStats = new MockUidIoStats();
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/70'000, /*bgWrBytes=*/20'000, 0, 0)},
             {1112345, IoUsage(0, 0, /*fgWrBytes=*/35'000, /*bgWrBytes=*/15'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/70'000, /*bgWrBytes=*/20'000, 0, 0)}});

    std::vector<PackageIoOveruseStats> actualIoOveruseStats;
    EXPECT_CALL(*mMockWatchdogServiceHelper, latestIoOveruseStats(_))
            .WillOnce(DoAll(SaveArg<0>(&actualIoOveruseStats), Return(Status::ok())));

    time_t currentTime = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    const auto [startTime, durationInSeconds] = calculateStartAndDuration(currentTime);

    ASSERT_RESULT_OK(
            mIoOveruseMonitor->onPeriodicCollection(currentTime, mockUidIoStats, nullptr, nullptr));
    std::vector<PackageIoOveruseStats> expectedIoOveruseStats = {
            constructPackageIoOveruseStats(/*uid*=*/1001000, /*shouldNotify=*/false,
                                           /*isKillable=*/false, /*remaining=*/
                                           constructPerStateBytes(10'000, 20'000, 100'000),
                                           /*written=*/constructPerStateBytes(70'000, 20'000, 0),
                                           /*totalOveruses=*/0, startTime, durationInSeconds),
            constructPackageIoOveruseStats(/*uid*=*/1112345, /*shouldNotify=*/false,
                                           /*isKillable=*/true, /*remaining=*/
                                           constructPerStateBytes(35'000, 15'000, 100'000),
                                           /*written=*/constructPerStateBytes(35'000, 15'000, 0),
                                           /*totalOveruses=*/0, startTime, durationInSeconds),
            // Exceeds threshold.
            constructPackageIoOveruseStats(/*uid*=*/1212345, /*shouldNotify=*/true,
                                           /*isKillable=*/true,
                                           /*remaining=*/constructPerStateBytes(0, 10'000, 100'000),
                                           /*written=*/constructPerStateBytes(70'000, 20'000, 0),
                                           /*totalOveruses=*/1, startTime, durationInSeconds),
    };
    EXPECT_THAT(actualIoOveruseStats, UnorderedElementsAreArray(expectedIoOveruseStats))
            << "Expected: " << toString(expectedIoOveruseStats)
            << "\nActual: " << toString(actualIoOveruseStats);

    ResourceOveruseStats actualOverusingNativeStats;
    // Package "com.android.google.package" for user 11 changed uid from 1112345 to 1113999.
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/30'000, /*bgWrBytes=*/0, 0, 0)},
             {1113999, IoUsage(0, 0, /*fgWrBytes=*/25'000, /*bgWrBytes=*/10'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/20'000, /*bgWrBytes=*/30'000, 0, 0)}});
    actualIoOveruseStats.clear();
    EXPECT_CALL(*mockResourceOveruseListener, onOveruse(_))
            .WillOnce(DoAll(SaveArg<0>(&actualOverusingNativeStats), Return(Status::ok())));
    EXPECT_CALL(*mMockWatchdogServiceHelper, latestIoOveruseStats(_))
            .WillOnce(DoAll(SaveArg<0>(&actualIoOveruseStats), Return(Status::ok())));

    ASSERT_RESULT_OK(
            mIoOveruseMonitor->onPeriodicCollection(currentTime, mockUidIoStats, nullptr, nullptr));

    const auto expectedOverusingNativeStats = constructResourceOveruseStats(
            constructIoOveruseStats(/*isKillable=*/false,
                                    /*remaining=*/constructPerStateBytes(0, 20'000, 100'000),
                                    /*written=*/constructPerStateBytes(100'000, 20'000, 0),
                                    /*totalOveruses=*/1, startTime, durationInSeconds));
    EXPECT_THAT(actualOverusingNativeStats, expectedOverusingNativeStats)
            << "Expected: " << expectedOverusingNativeStats.toString()
            << "\nActual: " << actualOverusingNativeStats.toString();

    expectedIoOveruseStats =
            {constructPackageIoOveruseStats(/*uid*=*/1001000, /*shouldNotify=*/false,
                                            /*isKillable=*/false, /*remaining=*/
                                            constructPerStateBytes(0, 20'000, 100'000),
                                            /*written=*/constructPerStateBytes(100'000, 20'000, 0),
                                            /*totalOveruses=*/1, startTime, durationInSeconds),
             // Exceeds warn threshold percentage.
             constructPackageIoOveruseStats(/*uid*=*/1113999, /*shouldNotify=*/true,
                                            /*isKillable=*/true, /*remaining=*/
                                            constructPerStateBytes(10'000, 5'000, 100'000),
                                            /*written=*/constructPerStateBytes(60'000, 25'000, 0),
                                            /*totalOveruses=*/0, startTime, durationInSeconds),
             /*
              * Exceeds threshold.
              * The package was forgiven on previous overuse so the remaining bytes should only
              * reflect the bytes written after the forgiven bytes.
              */
             constructPackageIoOveruseStats(/*uid*=*/1212345, /*shouldNotify=*/true,
                                            /*isKillable=*/true, /*remaining=*/
                                            constructPerStateBytes(50'000, 0, 100'000),
                                            /*written=*/constructPerStateBytes(90'000, 50'000, 0),
                                            /*totalOveruses=*/2, startTime, durationInSeconds)};
    EXPECT_THAT(actualIoOveruseStats, UnorderedElementsAreArray(expectedIoOveruseStats))
            << "Expected: " << toString(expectedIoOveruseStats)
            << "\nActual: " << toString(actualIoOveruseStats);

    /*
     * Current date changed so the daily I/O usage stats should be reset and the latest I/O overuse
     * stats should not aggregate with the previous day's stats.
     */
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/78'000, /*bgWrBytes=*/38'000, 0, 0)},
             {1113999, IoUsage(0, 0, /*fgWrBytes=*/55'000, /*bgWrBytes=*/23'000, 0, 0)},
             {1212345, IoUsage(0, 0, /*fgWrBytes=*/55'000, /*bgWrBytes=*/23'000, 0, 0)}});
    actualIoOveruseStats.clear();
    EXPECT_CALL(*mMockWatchdogServiceHelper, latestIoOveruseStats(_))
            .WillOnce(DoAll(SaveArg<0>(&actualIoOveruseStats), Return(Status::ok())));

    currentTime += (24 * 60 * 60);  // Change collection time to next day.
    ASSERT_RESULT_OK(
            mIoOveruseMonitor->onPeriodicCollection(currentTime, mockUidIoStats, nullptr, nullptr));

    const auto [nextDayStartTime, nextDayDuration] = calculateStartAndDuration(currentTime);
    expectedIoOveruseStats =
            {constructPackageIoOveruseStats(/*uid*=*/1001000, /*shouldNotify=*/false,
                                            /*isKillable=*/false, /*remaining=*/
                                            constructPerStateBytes(2'000, 2'000, 100'000),
                                            /*written=*/constructPerStateBytes(78'000, 38'000, 0),
                                            /*totalOveruses=*/0, nextDayStartTime, nextDayDuration),
             constructPackageIoOveruseStats(/*uid*=*/1113999, /*shouldNotify=*/false,
                                            /*isKillable=*/true, /*remaining=*/
                                            constructPerStateBytes(15'000, 7'000, 100'000),
                                            /*written=*/constructPerStateBytes(55'000, 23'000, 0),
                                            /*totalOveruses=*/0, nextDayStartTime, nextDayDuration),
             constructPackageIoOveruseStats(/*uid*=*/1212345, /*shouldNotify=*/false,
                                            /*isKillable=*/true, /*remaining=*/
                                            constructPerStateBytes(15'000, 7'000, 100'000),
                                            /*written=*/constructPerStateBytes(55'000, 23'000, 0),
                                            /*totalOveruses=*/0, nextDayStartTime,
                                            nextDayDuration)};

    EXPECT_THAT(actualIoOveruseStats, UnorderedElementsAreArray(expectedIoOveruseStats))
            << "Expected: " << toString(expectedIoOveruseStats)
            << "\nActual: " << toString(actualIoOveruseStats);
}

TEST_F(IoOveruseMonitorTest, TestOnPeriodicMonitor) {
    IIoOveruseConfigs::IoOveruseAlertThresholdSet alertThresholds = {
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/10, /*writtenBytesPerSecond=*/15'360),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/17, /*writtenBytesPerSecond=*/10'240),
            toIoOveruseAlertThreshold(
                    /*durationInSeconds=*/23, /*writtenBytesPerSecond=*/7'168),
    };
    ON_CALL(*mMockIoOveruseConfigs, systemWideAlertThresholds())
            .WillByDefault(ReturnRef(alertThresholds));

    time_t time = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    const auto nextCollectionTime = [&]() -> time_t {
        time += kTestMonitorInterval.count();
        return time;
    };
    bool isAlertReceived = false;
    const auto alertHandler = [&]() { isAlertReceived = true; };

    // 1st polling is ignored
    sp<MockProcDiskStats> mockProcDiskStats = new MockProcDiskStats();
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).Times(0);

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert because first polling is ignored";

    // 2nd polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 70};
    });

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 3rd polling exceeds first threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 90};
    });

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 4th polling - guarded by the heuristic to handle spurious alerting on partially filled buffer
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_FALSE(isAlertReceived) << "Triggered spurious alert when not exceeding the threshold";

    // 5th polling exceeds second threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 80};
    });

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";

    isAlertReceived = false;

    // 6th polling exceeds third threshold
    EXPECT_CALL(*mockProcDiskStats, deltaSystemWideDiskStats()).WillOnce([]() -> DiskStats {
        return DiskStats{.numKibWritten = 10};
    });

    ASSERT_RESULT_OK(mIoOveruseMonitor->onPeriodicMonitor(nextCollectionTime(), mockProcDiskStats,
                                                          alertHandler));
    EXPECT_TRUE(isAlertReceived) << "Failed to trigger alert when exceeding the threshold";
}

TEST_F(IoOveruseMonitorTest, TestRegisterResourceOveruseListener) {
    sp<MockResourceOveruseListener> mockResourceOveruseListener = new MockResourceOveruseListener();

    ASSERT_RESULT_OK(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener));

    ASSERT_RESULT_OK(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener));
}

TEST_F(IoOveruseMonitorTest, TestErrorsRegisterResourceOveruseListenerOnLinkToDeathError) {
    sp<MockResourceOveruseListener> mockResourceOveruseListener = new MockResourceOveruseListener();

    mockResourceOveruseListener->injectLinkToDeathFailure();

    ASSERT_FALSE(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener).ok());
}

TEST_F(IoOveruseMonitorTest, TestUnaddIoOveruseListener) {
    sp<MockResourceOveruseListener> mockResourceOveruseListener = new MockResourceOveruseListener();

    ASSERT_RESULT_OK(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener));

    ASSERT_RESULT_OK(mIoOveruseMonitor->removeIoOveruseListener(mockResourceOveruseListener));

    ASSERT_FALSE(mIoOveruseMonitor->removeIoOveruseListener(mockResourceOveruseListener).ok())
            << "Should error on duplicate unregister";
}

TEST_F(IoOveruseMonitorTest, TestUnaddIoOveruseListenerOnUnlinkToDeathError) {
    sp<MockResourceOveruseListener> mockResourceOveruseListener = new MockResourceOveruseListener();

    ASSERT_RESULT_OK(mIoOveruseMonitor->addIoOveruseListener(mockResourceOveruseListener));

    mockResourceOveruseListener->injectUnlinkToDeathFailure();

    ASSERT_RESULT_OK(mIoOveruseMonitor->removeIoOveruseListener(mockResourceOveruseListener));
}

TEST_F(IoOveruseMonitorTest, TestGetIoOveruseStats) {
    // Setup internal counters for a package.
    ON_CALL(*mMockPackageInfoResolver, getPackageInfosForUids(_))
            .WillByDefault([]() -> std::unordered_map<uid_t, PackageInfo> {
                return {{1001000,
                         constructPackageInfo(/*packageName=*/"system.daemon", /*uid=*/1001000,
                                              UidType::NATIVE)}};
            });
    mMockIoOveruseConfigs->injectPackageConfigs(
            {{"system.daemon",
              {constructPerStateBytes(/*fgBytes=*/80'000, /*bgBytes=*/40'000,
                                      /*gmBytes=*/100'000),
               /*isSafeToKill=*/false}}});
    sp<MockUidIoStats> mockUidIoStats = new MockUidIoStats();
    mockUidIoStats->expectDeltaStats(
            {{1001000, IoUsage(0, 0, /*fgWrBytes=*/90'000, /*bgWrBytes=*/20'000, 0, 0)}});

    time_t currentTime = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
    const auto [startTime, durationInSeconds] = calculateStartAndDuration(currentTime);

    ASSERT_RESULT_OK(
            mIoOveruseMonitor->onPeriodicCollection(currentTime, mockUidIoStats, nullptr, nullptr));

    const auto expected =
            constructIoOveruseStats(/*isKillable=*/false,
                                    /*remaining=*/
                                    constructPerStateBytes(80'000, 40'000, 100'000),
                                    /*written=*/
                                    constructPerStateBytes(90'000, 20'000, 0),
                                    /*totalOveruses=*/1, startTime, durationInSeconds);
    IoOveruseStats actual;
    ASSERT_NO_FATAL_FAILURE(executeAsUid(1001000, [&]() {
        ASSERT_RESULT_OK(mIoOveruseMonitor->getIoOveruseStats(&actual));
    }));
    EXPECT_THAT(actual, expected) << "Expected: " << expected.toString()
                                  << "\nActual: " << actual.toString();
}

TEST_F(IoOveruseMonitorTest, TestErrorsGetIoOveruseStatsOnNoStats) {
    ON_CALL(*mMockPackageInfoResolver, getPackageInfosForUids(_))
            .WillByDefault([]() -> std::unordered_map<uid_t, PackageInfo> {
                return {{1001000,
                         constructPackageInfo(/*packageName=*/"system.daemon", /*uid=*/1001000,
                                              UidType::NATIVE)}};
            });
    IoOveruseStats actual;
    ASSERT_NO_FATAL_FAILURE(executeAsUid(1001000, [&]() {
        ASSERT_FALSE(mIoOveruseMonitor->getIoOveruseStats(&actual).ok())
                << "Should fail on missing I/O overuse stats";
    }));

    ASSERT_NO_FATAL_FAILURE(executeAsUid(1102001, [&]() {
        ASSERT_FALSE(mIoOveruseMonitor->getIoOveruseStats(&actual).ok())
                << "Should fail on missing package information";
    }));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
