/*
 * Copyright 2020 The Android Open Source Project
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

#include "IoOveruseConfigs.h"
#include "OveruseConfigurationTestUtils.h"
#include "OveruseConfigurationXmlHelper.h"

#include <android-base/strings.h>
#include <gmock/gmock.h>

#include <inttypes.h>

#include <unordered_map>

namespace android {
namespace automotive {
namespace watchdog {

using ::android::sp;
using ::android::automotive::watchdog::internal::ApplicationCategoryType;
using ::android::automotive::watchdog::internal::ComponentType;
using ::android::automotive::watchdog::internal::IoOveruseAlertThreshold;
using ::android::automotive::watchdog::internal::IoOveruseConfiguration;
using ::android::automotive::watchdog::internal::PackageInfo;
using ::android::automotive::watchdog::internal::PackageMetadata;
using ::android::automotive::watchdog::internal::ResourceOveruseConfiguration;
using ::android::automotive::watchdog::internal::ResourceSpecificConfiguration;
using ::android::automotive::watchdog::internal::UidType;
using ::android::base::Error;
using ::android::base::StringAppendF;
using ::android::base::StringPrintf;
using ::testing::IsEmpty;
using ::testing::Matcher;
using ::testing::UnorderedElementsAre;
using ::testing::UnorderedElementsAreArray;

namespace {

const PerStateBytes SYSTEM_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(200, 100, 500);
const PerStateBytes SYSTEM_PACKAGE_A_THRESHOLDS = toPerStateBytes(600, 400, 1000);
const PerStateBytes SYSTEM_PACKAGE_B_THRESHOLDS = toPerStateBytes(1200, 800, 1500);
const PerStateBytes VENDOR_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(100, 50, 900);
const PerStateBytes VENDOR_PACKAGE_A_THRESHOLDS = toPerStateBytes(800, 300, 500);
const PerStateBytes VENDOR_PKG_B_THRESHOLDS = toPerStateBytes(1600, 600, 1000);
const PerStateBytes MAPS_THRESHOLDS = toPerStateBytes(700, 900, 1300);
const PerStateBytes MEDIA_THRESHOLDS = toPerStateBytes(1800, 1900, 2100);
const PerStateBytes THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS = toPerStateBytes(300, 150, 1900);
const std::vector<IoOveruseAlertThreshold> ALERT_THRESHOLDS = {toIoOveruseAlertThreshold(5, 200),
                                                               toIoOveruseAlertThreshold(30,
                                                                                         40000)};

std::unordered_map<std::string, ApplicationCategoryType> toPackageToAppCategoryMappings(
        const std::vector<PackageMetadata>& metas) {
    std::unordered_map<std::string, ApplicationCategoryType> mappings;
    for (const auto& meta : metas) {
        mappings[meta.packageName] = meta.appCategoryType;
    }
    return mappings;
}

PackageInfo constructPackageInfo(
        const char* packageName, const ComponentType componentType,
        const ApplicationCategoryType appCategoryType = ApplicationCategoryType::OTHERS) {
    PackageInfo packageInfo;
    packageInfo.packageIdentifier.name = packageName;
    packageInfo.uidType = UidType::APPLICATION;
    packageInfo.componentType = componentType;
    packageInfo.appCategoryType = appCategoryType;
    return packageInfo;
}

std::string toString(std::vector<ResourceOveruseConfiguration> configs) {
    std::string buffer;
    StringAppendF(&buffer, "[");
    for (const auto& config : configs) {
        if (buffer.size() > 1) {
            StringAppendF(&buffer, ",\n");
        }
        StringAppendF(&buffer, "%s", config.toString().c_str());
    }
    StringAppendF(&buffer, "]\n");
    return buffer;
}

std::vector<Matcher<const ResourceOveruseConfiguration>> ResourceOveruseConfigurationsMatchers(
        const std::vector<ResourceOveruseConfiguration>& configs) {
    std::vector<Matcher<const ResourceOveruseConfiguration>> matchers;
    for (const auto config : configs) {
        matchers.push_back(ResourceOveruseConfigurationMatcher(config));
    }
    return matchers;
}

ResourceOveruseConfiguration sampleBuildSystemConfig() {
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM,
                                                            toPerStateBytes(1200, 1100, 1500)),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageA", SYSTEM_PACKAGE_A_THRESHOLDS)},
            /*categorySpecific=*/{},
            /*systemWide=*/ALERT_THRESHOLDS);
    return constructResourceOveruseConfig(ComponentType::SYSTEM,
                                          /*safeToKill=*/{"systemPackageA"},
                                          /*vendorPrefixes=*/{},
                                          /*packageMetadata*/
                                          {toPackageMetadata("systemPackageA",
                                                             ApplicationCategoryType::MEDIA)},
                                          systemIoConfig);
}

ResourceOveruseConfiguration sampleBuildVendorConfig() {
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR,
                                                            toPerStateBytes(1100, 150, 1900)),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", VENDOR_PACKAGE_A_THRESHOLDS)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MEDIA", MEDIA_THRESHOLDS)},
            /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::VENDOR,
                                          /*safeToKill=*/{},
                                          /*vendorPrefixes=*/{"vendorPackage"},
                                          /*packageMetadata=*/
                                          {toPackageMetadata("vendorPackageA",
                                                             ApplicationCategoryType::MEDIA)},
                                          vendorIoConfig);
}

ResourceOveruseConfiguration sampleBuildThirdPartyConfig() {
    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY,
                                         toPerStateBytes(1300, 1150, 2900)),
            /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                          /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                          thirdPartyIoConfig);
}

ResourceOveruseConfiguration sampleUpdateSystemConfig() {
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM,
                                                            SYSTEM_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageA", SYSTEM_PACKAGE_A_THRESHOLDS),
             toPerStateIoOveruseThreshold("systemPackageB", SYSTEM_PACKAGE_B_THRESHOLDS)},
            /*categorySpecific=*/{},
            /*systemWide=*/ALERT_THRESHOLDS);
    return constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                          /*vendorPrefixes=*/{},
                                          /*packageMetadata=*/
                                          {toPackageMetadata("systemPackageA",
                                                             ApplicationCategoryType::MEDIA),
                                           toPackageMetadata("vendorPkgB",
                                                             ApplicationCategoryType::MAPS)},
                                          systemIoConfig);
}

ResourceOveruseConfiguration sampleUpdateVendorConfig() {
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR,
                                                            VENDOR_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", VENDOR_PACKAGE_A_THRESHOLDS),
             toPerStateIoOveruseThreshold("vendorPkgB", VENDOR_PKG_B_THRESHOLDS)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", MAPS_THRESHOLDS),
             toPerStateIoOveruseThreshold("MEDIA", MEDIA_THRESHOLDS)},
            /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::VENDOR,
                                          /*safeToKill=*/{"vendorPackageA"},
                                          /*vendorPrefixes=*/{"vendorPackage"},
                                          /*packageMetadata=*/
                                          {toPackageMetadata("systemPackageA",
                                                             ApplicationCategoryType::MEDIA),
                                           toPackageMetadata("vendorPkgB",
                                                             ApplicationCategoryType::MAPS)},
                                          vendorIoConfig);
}

ResourceOveruseConfiguration sampleUpdateThirdPartyConfig() {
    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY,
                                         THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS),
            /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{});
    return constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                          /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                          thirdPartyIoConfig);
}

sp<IoOveruseConfigs> sampleIoOveruseConfigs() {
    sp<IoOveruseConfigs> ioOveruseConfigs = new IoOveruseConfigs();
    EXPECT_RESULT_OK(
            ioOveruseConfigs->update({sampleUpdateSystemConfig(), sampleUpdateVendorConfig(),
                                      sampleUpdateThirdPartyConfig()}));
    return ioOveruseConfigs;
}

}  // namespace

namespace internal {

class IoOveruseConfigsPeer : public android::RefBase {
public:
    IoOveruseConfigsPeer() {
        IoOveruseConfigs::sParseXmlFile =
                [&](const char* filepath) -> android::base::Result<ResourceOveruseConfiguration> {
            if (const auto it = configsByFilepaths.find(filepath); it != configsByFilepaths.end()) {
                return it->second;
            }
            return Error() << "No configs available for the given filepath '" << filepath << "'";
        };
    }
    ~IoOveruseConfigsPeer() {
        IoOveruseConfigs::sParseXmlFile = &OveruseConfigurationXmlHelper::parseXmlFile;
    }
    std::unordered_map<std::string, ResourceOveruseConfiguration> configsByFilepaths;
};

}  // namespace internal

class IoOveruseConfigsTest : public ::testing::Test {
public:
    virtual void SetUp() { mPeer = sp<internal::IoOveruseConfigsPeer>::make(); }
    virtual void TearDown() { mPeer.clear(); }

    sp<internal::IoOveruseConfigsPeer> mPeer;
};

TEST_F(IoOveruseConfigsTest, TestConstructWithBuildConfigs) {
    auto buildSystemResourceConfig = sampleBuildSystemConfig();
    auto buildVendorResourceConfig = sampleBuildVendorConfig();
    const auto buildThirdPartyResourceConfig = sampleBuildThirdPartyConfig();

    mPeer->configsByFilepaths = {{kBuildSystemConfigXmlPath, buildSystemResourceConfig},
                                 {kBuildVendorConfigXmlPath, buildVendorResourceConfig},
                                 {kBuildThirdPartyConfigXmlPath, buildThirdPartyResourceConfig}};

    IoOveruseConfigs ioOveruseConfigs;

    /* Package to app category mapping should be merged from both vendor and system configs. */
    buildVendorResourceConfig.packageMetadata
            .insert(buildVendorResourceConfig.packageMetadata.end(),
                    buildSystemResourceConfig.packageMetadata.begin(),
                    buildSystemResourceConfig.packageMetadata.end());
    buildSystemResourceConfig.packageMetadata = buildVendorResourceConfig.packageMetadata;
    std::vector<ResourceOveruseConfiguration> expected = {buildSystemResourceConfig,
                                                          buildVendorResourceConfig,
                                                          buildThirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestConstructWithLatestConfigs) {
    const auto latestSystemResourceConfig = sampleUpdateSystemConfig();
    auto latestVendorResourceConfig = sampleUpdateVendorConfig();
    const auto latestThirdPartyResourceConfig = sampleUpdateThirdPartyConfig();

    mPeer->configsByFilepaths = {{kBuildSystemConfigXmlPath, sampleBuildSystemConfig()},
                                 {kBuildVendorConfigXmlPath, sampleBuildVendorConfig()},
                                 {kBuildThirdPartyConfigXmlPath, sampleBuildThirdPartyConfig()},
                                 {kLatestSystemConfigXmlPath, latestSystemResourceConfig},
                                 {kLatestVendorConfigXmlPath, latestVendorResourceConfig},
                                 {kLatestThirdPartyConfigXmlPath, latestThirdPartyResourceConfig}};

    IoOveruseConfigs ioOveruseConfigs;

    latestVendorResourceConfig.vendorPackagePrefixes.push_back("vendorPkgB");
    std::vector<ResourceOveruseConfiguration> expected = {latestSystemResourceConfig,
                                                          latestVendorResourceConfig,
                                                          latestThirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestConstructWithOnlyBuildSystemConfig) {
    const auto buildSystemResourceConfig = sampleBuildSystemConfig();

    mPeer->configsByFilepaths = {{kBuildSystemConfigXmlPath, buildSystemResourceConfig}};

    IoOveruseConfigs ioOveruseConfigs;

    /*
     * Vendor/Third-party component-level thresholds should be derived from system
     * component-level thresholds when build configs for Vendor/Third-party components are not
     * available.
     */
    const auto& defaultComponentLevelThresholds =
            buildSystemResourceConfig.resourceSpecificConfigurations[0]
                    .get<ResourceSpecificConfiguration::ioOveruseConfiguration>()
                    .componentLevelThresholds.perStateWriteBytes;
    const auto vendorResourceConfig = constructResourceOveruseConfig(
            ComponentType::VENDOR, /*safeToKill=*/{}, /*vendorPrefixes=*/{},
            /*packageMetadata=*/buildSystemResourceConfig.packageMetadata,
            constructIoOveruseConfig(
                    /*componentLevel=*/
                    toPerStateIoOveruseThreshold(ComponentType::VENDOR,
                                                 defaultComponentLevelThresholds),
                    /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{}));
    const auto thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{},
                                           /*packageMetadata=*/{},
                                           constructIoOveruseConfig(
                                                   /*componentLevel=*/toPerStateIoOveruseThreshold(
                                                           ComponentType::THIRD_PARTY,
                                                           defaultComponentLevelThresholds),
                                                   /*packageSpecific=*/{}, /*categorySpecific=*/{},
                                                   /*systemWide=*/{}));

    std::vector<ResourceOveruseConfiguration> expected = {buildSystemResourceConfig,
                                                          vendorResourceConfig,
                                                          thirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestConstructWithBuildSystemConfigLatestVendorConfig) {
    auto buildSystemResourceConfig = sampleBuildSystemConfig();
    auto latestVendorResourceConfig = sampleUpdateVendorConfig();
    const auto buildThirdPartyResourceConfig = sampleBuildThirdPartyConfig();

    mPeer->configsByFilepaths = {{kBuildSystemConfigXmlPath, buildSystemResourceConfig},
                                 {kBuildVendorConfigXmlPath, sampleBuildVendorConfig()},
                                 {kBuildThirdPartyConfigXmlPath, buildThirdPartyResourceConfig},
                                 {kLatestVendorConfigXmlPath, latestVendorResourceConfig}};

    IoOveruseConfigs ioOveruseConfigs;

    // Package to app category mapping from latest vendor configuration should be given priority.
    buildSystemResourceConfig.packageMetadata = latestVendorResourceConfig.packageMetadata;
    latestVendorResourceConfig.vendorPackagePrefixes.push_back("vendorPkgB");
    std::vector<ResourceOveruseConfiguration> expected = {buildSystemResourceConfig,
                                                          latestVendorResourceConfig,
                                                          buildThirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestConstructWithLatestSystemConfigBuildVendorConfig) {
    const auto latestSystemResourceConfig = sampleUpdateSystemConfig();
    auto buildVendorResourceConfig = sampleBuildVendorConfig();
    const auto buildThirdPartyResourceConfig = sampleBuildThirdPartyConfig();

    mPeer->configsByFilepaths = {{kBuildSystemConfigXmlPath, sampleBuildSystemConfig()},
                                 {kBuildVendorConfigXmlPath, sampleBuildVendorConfig()},
                                 {kBuildThirdPartyConfigXmlPath, buildThirdPartyResourceConfig},
                                 {kLatestSystemConfigXmlPath, latestSystemResourceConfig}};

    IoOveruseConfigs ioOveruseConfigs;

    // Package to app category mapping from latest system configuration should be given priority.
    buildVendorResourceConfig.packageMetadata = latestSystemResourceConfig.packageMetadata;
    std::vector<ResourceOveruseConfiguration> expected = {latestSystemResourceConfig,
                                                          buildVendorResourceConfig,
                                                          buildThirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestUpdateWithValidConfigs) {
    auto systemResourceConfig = sampleUpdateSystemConfig();
    auto vendorResourceConfig = sampleUpdateVendorConfig();
    auto thirdPartyResourceConfig = sampleUpdateThirdPartyConfig();

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update(
            {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig}));

    vendorResourceConfig.vendorPackagePrefixes.push_back("vendorPkgB");
    std::vector<ResourceOveruseConfiguration> expected = {systemResourceConfig,
                                                          vendorResourceConfig,
                                                          thirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);

    // Check whether previous configs are overwritten.
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 300, 400, 600),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageC", 700, 100, 200),
             toPerStateIoOveruseThreshold("systemPackageC", 300, 200, 300)},
            /*categorySpecific=*/{},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(6, 4), toIoOveruseAlertThreshold(6, 10)});
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageC"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    /*
     * Not adding any safe to kill packages list or package specific thresholds should clear
     * previous entries after update.
     */
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR, 10, 90, 300),
            /*packageSpecific=*/{},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 800, 900, 2000),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100),
             toPerStateIoOveruseThreshold("MEDIA", 1400, 1600, 2000)},
            /*systemWide=*/{});
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 600, 300, 2300),
            /*packageSpecific=*/{}, /*categorySpecific=*/{}, /*systemWide=*/{});
    thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           thirdPartyIoConfig);

    ASSERT_RESULT_OK(ioOveruseConfigs.update(
            {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig}));

    systemIoConfig.packageSpecificThresholds.erase(
            systemIoConfig.packageSpecificThresholds.begin());
    systemIoConfig.systemWideThresholds.erase(systemIoConfig.systemWideThresholds.begin() + 1);
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageC"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    vendorIoConfig.categorySpecificThresholds.erase(
            vendorIoConfig.categorySpecificThresholds.begin() + 1);
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR, /*safeToKill=*/{},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    expected = {systemResourceConfig, vendorResourceConfig, thirdPartyResourceConfig};

    actual.clear();
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestDefaultConfigWithoutUpdate) {
    PerStateBytes defaultPerStateBytes = defaultThreshold().perStateWriteBytes;
    IoOveruseConfigs ioOveruseConfigs;

    auto packageInfo = constructPackageInfo("systemPackage", ComponentType::SYSTEM);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "System package should have default threshold";
    EXPECT_FALSE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "System package shouldn't be killed by default";

    packageInfo = constructPackageInfo("vendorPackage", ComponentType::VENDOR,
                                       ApplicationCategoryType::MEDIA);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "Vendor package should have default threshold";
    EXPECT_FALSE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "Vendor package shouldn't be killed by default";

    packageInfo = constructPackageInfo("3pPackage", ComponentType::THIRD_PARTY,
                                       ApplicationCategoryType::MAPS);
    EXPECT_THAT(ioOveruseConfigs.fetchThreshold(packageInfo), defaultPerStateBytes)
            << "Third-party package should have default threshold";
    EXPECT_TRUE(ioOveruseConfigs.isSafeToKill(packageInfo))
            << "Third-party package should be killed by default";

    EXPECT_THAT(ioOveruseConfigs.systemWideAlertThresholds(), IsEmpty());
    EXPECT_THAT(ioOveruseConfigs.vendorPackagePrefixes(), IsEmpty());

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnInvalidComponentName) {
    IoOveruseConfiguration randomIoConfig;
    randomIoConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold("random name", 200, 100, 500);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::SYSTEM, {}, {}, {},
                                                                 randomIoConfig)})
                         .ok());

    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::VENDOR, {}, {}, {},
                                                                 randomIoConfig)})
                         .ok());

    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::THIRD_PARTY, {}, {},
                                                                 {}, randomIoConfig)})
                         .ok());

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnDuplicatePackageToAppCategoryMappings) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::VENDOR, VENDOR_COMPONENT_LEVEL_THRESHOLDS);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(
            ioOveruseConfigs
                    .update({constructResourceOveruseConfig(
                            ComponentType::VENDOR,
                            /*safeToKill=*/{},
                            /*vendorPrefixes=*/{"vendorPackage"},
                            /*packageMetadata=*/
                            {toPackageMetadata("vendorPackageA", ApplicationCategoryType::MEDIA),
                             toPackageMetadata("vendorPackageA", ApplicationCategoryType::MAPS)},
                            ioConfig)})
                    .ok())
            << "Should error on duplicate package to app category mapping";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnInvalidComponentLevelThresholds) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 0, 0, 0);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::THIRD_PARTY, {}, {},
                                                                 {}, ioConfig)})
                         .ok())
            << "Should error on invalid component level thresholds";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnInvalidSystemWideAlertThresholds) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 100, 200, 300);
    ioConfig.systemWideThresholds = {toIoOveruseAlertThreshold(0, 0)};

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({constructResourceOveruseConfig(ComponentType::SYSTEM, {}, {}, {},
                                                                 ioConfig)})
                         .ok())
            << "Should error on invalid system-wide thresholds";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnDuplicateConfigsForSameComponent) {
    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs
                         .update({sampleUpdateThirdPartyConfig(), sampleUpdateThirdPartyConfig()})
                         .ok())
            << "Should error on duplicate configs for the same component";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnNoIoOveruseConfiguration) {
    ResourceOveruseConfiguration resConfig;
    resConfig.componentType = ComponentType::THIRD_PARTY;

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update({resConfig}).ok())
            << "Should error on no I/O overuse configuration";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestFailsUpdateOnMultipleIoOveruseConfigurations) {
    IoOveruseConfiguration ioConfig;
    ioConfig.componentLevelThresholds =
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 100, 200, 300);

    ResourceOveruseConfiguration resConfig;
    resConfig.componentType = ComponentType::THIRD_PARTY;
    ResourceSpecificConfiguration resourceSpecificConfig;
    resourceSpecificConfig.set<ResourceSpecificConfiguration::ioOveruseConfiguration>(ioConfig);
    resConfig.resourceSpecificConfigurations.push_back(resourceSpecificConfig);
    resConfig.resourceSpecificConfigurations.push_back(resourceSpecificConfig);

    IoOveruseConfigs ioOveruseConfigs;
    EXPECT_FALSE(ioOveruseConfigs.update({resConfig}).ok())
            << "Should error on multiple I/O overuse configuration";

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, IsEmpty());
}

TEST_F(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsBySystemComponent) {
    auto systemIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::SYSTEM, 200, 100, 500),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("systemPackageA", 600, 400, 1000),
             toPerStateIoOveruseThreshold("systemPackageB", 1200, 800, 1500)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage"},
                                           /*packageMetadata=*/{}, systemIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({systemResourceConfig}));

    // Drop fields that aren't updatable by system component.
    systemIoConfig.categorySpecificThresholds.clear();
    systemResourceConfig =
            constructResourceOveruseConfig(ComponentType::SYSTEM, /*safeToKill=*/{"systemPackageA"},
                                           /*vendorPrefixes=*/{}, /*packageMetadata=*/{},
                                           systemIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {systemResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByVendorComponent) {
    auto vendorIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/toPerStateIoOveruseThreshold(ComponentType::VENDOR, 100, 50, 900),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", 800, 300, 500),
             toPerStateIoOveruseThreshold("vendorPkgB", 1600, 600, 1000)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR,
                                           /*safeToKill=*/
                                           {"vendorPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({vendorResourceConfig}));

    // Drop fields that aren't updatable by vendor component.
    vendorIoConfig.systemWideThresholds.clear();
    vendorResourceConfig =
            constructResourceOveruseConfig(ComponentType::VENDOR,
                                           /*safeToKill=*/
                                           {"vendorPackageA"},
                                           /*vendorPrefixes=*/{"vendorPackage", "vendorPkg"},
                                           /*packageMetadata=*/{}, vendorIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {vendorResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestIgnoresNonUpdatableConfigsByThirdPartyComponent) {
    auto thirdPartyIoConfig = constructIoOveruseConfig(
            /*componentLevel=*/
            toPerStateIoOveruseThreshold(ComponentType::THIRD_PARTY, 300, 150, 1900),
            /*packageSpecific=*/
            {toPerStateIoOveruseThreshold("vendorPackageA", 800, 300, 500),
             toPerStateIoOveruseThreshold("systemPackageB", 1600, 600, 1000)},
            /*categorySpecific=*/
            {toPerStateIoOveruseThreshold("MAPS", 700, 900, 1300),
             toPerStateIoOveruseThreshold("MEDIA", 1800, 1900, 2100)},
            /*systemWide=*/
            {toIoOveruseAlertThreshold(5, 200), toIoOveruseAlertThreshold(30, 40000)});
    auto thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY,
                                           /*safeToKill=*/{"vendorPackageA", "systemPackageB"},
                                           /*vendorPrefixes=*/{"vendorPackage"},
                                           /*packageMetadata=*/{}, thirdPartyIoConfig);

    IoOveruseConfigs ioOveruseConfigs;
    ASSERT_RESULT_OK(ioOveruseConfigs.update({thirdPartyResourceConfig}));

    // Drop fields that aren't updatable by third-party component.
    thirdPartyIoConfig.packageSpecificThresholds.clear();
    thirdPartyIoConfig.categorySpecificThresholds.clear();
    thirdPartyIoConfig.systemWideThresholds.clear();
    thirdPartyResourceConfig =
            constructResourceOveruseConfig(ComponentType::THIRD_PARTY,
                                           /*safeToKill=*/{}, /*vendorPrefixes=*/{},
                                           /*packageMetadata=*/{}, thirdPartyIoConfig);

    std::vector<ResourceOveruseConfiguration> expected = {thirdPartyResourceConfig};

    std::vector<ResourceOveruseConfiguration> actual;
    ioOveruseConfigs.get(&actual);

    EXPECT_THAT(actual, UnorderedElementsAreArray(ResourceOveruseConfigurationsMatchers(expected)))
            << "Expected: " << toString(expected) << "Actual:" << toString(actual);
}

TEST_F(IoOveruseConfigsTest, TestFetchThresholdForSystemPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("systemPackageGeneric", ComponentType::SYSTEM));

    EXPECT_THAT(actual, SYSTEM_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("systemPackageA", ComponentType::SYSTEM));

    EXPECT_THAT(actual, SYSTEM_PACKAGE_A_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("systemPackageB",
                                                                   ComponentType::SYSTEM,
                                                                   ApplicationCategoryType::MEDIA));

    // Package specific thresholds get priority over media category thresholds.
    EXPECT_THAT(actual, SYSTEM_PACKAGE_B_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("systemPackageC",
                                                                   ComponentType::SYSTEM,
                                                                   ApplicationCategoryType::MEDIA));

    // Media category thresholds as there is no package specific thresholds.
    EXPECT_THAT(actual, MEDIA_THRESHOLDS);
}

TEST_F(IoOveruseConfigsTest, TestFetchThresholdForVendorPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR));

    EXPECT_THAT(actual, VENDOR_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPkgB", ComponentType::VENDOR));

    EXPECT_THAT(actual, VENDOR_PKG_B_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("vendorPackageC",
                                                                   ComponentType::VENDOR,
                                                                   ApplicationCategoryType::MAPS));

    // Maps category thresholds as there is no package specific thresholds.
    EXPECT_THAT(actual, MAPS_THRESHOLDS);
}

TEST_F(IoOveruseConfigsTest, TestFetchThresholdForThirdPartyPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    auto actual = ioOveruseConfigs->fetchThreshold(
            constructPackageInfo("vendorPackageGenericImpostor", ComponentType::THIRD_PARTY));

    EXPECT_THAT(actual, THIRD_PARTY_COMPONENT_LEVEL_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("3pMapsPackage",
                                                                   ComponentType::THIRD_PARTY,
                                                                   ApplicationCategoryType::MAPS));

    EXPECT_THAT(actual, MAPS_THRESHOLDS);

    actual = ioOveruseConfigs->fetchThreshold(constructPackageInfo("3pMediaPackage",
                                                                   ComponentType::THIRD_PARTY,
                                                                   ApplicationCategoryType::MEDIA));

    EXPECT_THAT(actual, MEDIA_THRESHOLDS);
}

TEST_F(IoOveruseConfigsTest, TestIsSafeToKillSystemPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("systemPackageGeneric", ComponentType::SYSTEM)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("systemPackageA", ComponentType::SYSTEM)));
}

TEST_F(IoOveruseConfigsTest, TestIsSafeToKillVendorPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageGeneric", ComponentType::VENDOR)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageA", ComponentType::VENDOR)));
}

TEST_F(IoOveruseConfigsTest, TestIsSafeToKillThirdPartyPackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();
    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("vendorPackageGenericImpostor", ComponentType::THIRD_PARTY)));

    EXPECT_TRUE(ioOveruseConfigs->isSafeToKill(
            constructPackageInfo("3pMapsPackage", ComponentType::THIRD_PARTY,
                                 ApplicationCategoryType::MAPS)));
}

TEST_F(IoOveruseConfigsTest, TestIsSafeToKillNativePackages) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    PackageInfo packageInfo;
    packageInfo.packageIdentifier.name = "native package";
    packageInfo.uidType = UidType::NATIVE;
    packageInfo.componentType = ComponentType::SYSTEM;

    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(packageInfo));

    packageInfo.componentType = ComponentType::VENDOR;

    EXPECT_FALSE(ioOveruseConfigs->isSafeToKill(packageInfo));
}

TEST_F(IoOveruseConfigsTest, TestSystemWideAlertThresholds) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    EXPECT_THAT(ioOveruseConfigs->systemWideAlertThresholds(),
                UnorderedElementsAreArray(ALERT_THRESHOLDS));
}

TEST_F(IoOveruseConfigsTest, TestVendorPackagePrefixes) {
    const auto ioOveruseConfigs = sampleIoOveruseConfigs();

    EXPECT_THAT(ioOveruseConfigs->vendorPackagePrefixes(),
                UnorderedElementsAre("vendorPackage", "vendorPkgB"));
}

TEST_F(IoOveruseConfigsTest, TestPackagesToAppCategoriesWithSystemConfig) {
    IoOveruseConfigs ioOveruseConfigs;
    const auto resourceOveruseConfig = sampleUpdateSystemConfig();

    ASSERT_RESULT_OK(ioOveruseConfigs.update({resourceOveruseConfig}));

    EXPECT_THAT(ioOveruseConfigs.packagesToAppCategories(),
                UnorderedElementsAreArray(
                        toPackageToAppCategoryMappings(resourceOveruseConfig.packageMetadata)));
}

TEST_F(IoOveruseConfigsTest, TestPackagesToAppCategoriesWithVendorConfig) {
    IoOveruseConfigs ioOveruseConfigs;
    const auto resourceOveruseConfig = sampleUpdateVendorConfig();

    ASSERT_RESULT_OK(ioOveruseConfigs.update({resourceOveruseConfig}));

    EXPECT_THAT(ioOveruseConfigs.packagesToAppCategories(),
                UnorderedElementsAreArray(
                        toPackageToAppCategoryMappings(resourceOveruseConfig.packageMetadata)));
}

}  // namespace watchdog
}  // namespace automotive
}  // namespace android
