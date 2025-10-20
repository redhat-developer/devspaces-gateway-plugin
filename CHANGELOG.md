<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# gateway-plugin Changelog

## [Unreleased]

## [0.0.13] - 2025-10-20

### ✨ New Features and Enhancements

- Multi-connection support by @msivasubramaniaan in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/188
- Double-click on a workspace to connect to it (#23544) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/175
- Icons are used to display a workspace phase by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/178
- Only the workspaces with JetBraisn IDE are listed by @vrubezhny in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/185
- The progress bar dialogs are consistent across the plugin (#23547) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/171
- Increased wizard borders for nicer looks by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/176
- feature: cluster combobox displays '<cluster name> (<cluster url>)' (#23561) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/179
- disable the 'Connect' button when a stopped workspace is selected (#23566) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/177
- Auto-reconnecting a local IDE to workspace after losing/restoring the network connection by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/190
- avoid connection issues: implemented proper wait for port forwarding by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/173

### 🐛 Bug Fixes

- fix: don't connect if no joinLink, show error (#23549) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/168
- fix: enable/disable connect button upon ws-selection change (#23581) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/182
- fix: don't allow double-click on non-running ws (#23581) by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/183

### 🛠️ Code Improvements

- replaced String.format() by kotlin string templates by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/172

### ⬆️ Dependency Updates

- Bump org.jetbrains.kotlinx.kover from 0.9.2 to 0.9.3 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/192
- Bump org.junit.jupiter:junit-jupiter-api from 5.11.0 to 6.0.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/195
- Bump org.assertj:assertj-core from 3.23.1 to 3.27.6 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/193
- Bump org.jetbrains.kotlinx.kover from 0.9.1 to 0.9.2 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/174
- Bump gradle/actions from 4 to 5 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/186

## [0.0.12] - 2025-09-12

### ✨ New Features and Enhancements

- It's possible to choose a cluster, stored in the local kubeconfig file, from the dropdown box by @msivasubramaniaan in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/158
- The `KUBECONFIG` environment variable is respected when reading the local kube config by @vrubezhny in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/141
- It's possible to cancel the ongoing connection attempts by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/166
- When connecting to a cluster, added a progress bar to show the connection process by @msivasubramaniaan in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/162
- More meaningful error messages on the login step when the OpenShift token has expired or no DevSpaces operator is installed in the cluster #23487 by @vrubezhny in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/144
- The `Connect` button is now disabled when no workspace is selected by @msivasubramaniaan in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/153
- A progress indicator is added to unblock the UI at the connection step and to track the connection process #23471 by @vrubezhny in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/145

### 🐛 Bug Fixes

- fix: avoid deadlock when reading remote server status by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/139
- fix: dont freeze when creating the connection by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/140

### 🛠️ Code Improvements

- move Throwable.rootMessage() to helper class by @adietish in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/165

### ⬆️ Dependency Updates

- updated io.kubernetes:client-java:24 by @msivasubramaniaan in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/137
- Bump org.jetbrains.kotlin.jvm from 2.1.21 to 2.2.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/136
- Bump com.fasterxml.jackson.dataformat:jackson-dataformat-yaml from 2.17.1 to 2.19.2 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/143
- Bump com.fasterxml.jackson.core:jackson-databind from 2.17.1 to 2.19.2 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/142
- Bump org.jetbrains.changelog from 2.2.1 to 2.3.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/146
- Bump org.jetbrains.intellij.platform from 2.6.0 to 2.7.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/147
- Bump actions/checkout from 4 to 5 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/151
- Bump org.jetbrains.changelog from 2.3.0 to 2.4.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/149
- Bump org.jetbrains.intellij.platform from 2.7.0 to 2.7.1 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/152
- Bump org.jetbrains.qodana from 2025.1.1 to 2025.2.1 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/155
- Bump JetBrains/qodana-action from 2025.1 to 2025.2 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/154
- Bump org.jetbrains.intellij.platform from 2.7.1 to 2.7.2 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/157
- Bump org.jetbrains.kotlin.jvm from 2.2.0 to 2.2.10 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/156
- Bump actions/setup-java from 4 to 5 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/159
- Bump com.fasterxml.jackson.core:jackson-databind from 2.19.2 to 2.20.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/161
- Bump com.fasterxml.jackson.dataformat:jackson-dataformat-yaml from 2.19.2 to 2.20.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/160
- Bump org.jetbrains.intellij.platform from 2.7.2 to 2.8.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/163
- Bump org.jetbrains.intellij.platform from 2.8.0 to 2.9.0 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/164
- Bump org.jetbrains.kotlin.jvm from 2.2.10 to 2.2.20 by @dependabot[bot] in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/167

## [0.0.11] - 2025-06-18

- Don't fail when trying to list the devworkspaces in the namespace that the user is not allowed to read by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/134

## [0.0.10] - 2025-05-16

- Changelog update - `v0.0.9` by @github-actions in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/119
- Bump org.jetbrains.qodana from 2024.3.4 to 2025.1.1 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/122
- Bump JetBrains/qodana-action from 2024.3 to 2025.1 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/121
- Support Gateway 2025.* by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/126
- Support the UBI9-based user containers by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/125
- Bump org.jetbrains.intellij.platform from 2.5.0 to 2.6.0 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/124
- Bump org.jetbrains.kotlin.jvm from 2.1.20 to 2.1.21 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/123
- Declare compatibility against GW 2025.2 by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/127

## [0.0.9] - 2025-04-24

- Bump org.jetbrains.intellij.platform from 2.2.1 to 2.3.0 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/110
- Bump org.jetbrains.kotlin.jvm from 2.1.10 to 2.1.20 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/114
- Bump org.jetbrains.intellij.platform from 2.3.0 to 2.5.0 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/115
- Bump org.gradle.toolchains.foojay-resolver-convention from 0.9.0 to 0.10.0 by @dependabot in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/117
- Gateway 2025.1 support  by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/118

## [0.0.8] - 2025-03-09

- Update to `io.kubernetes:client-java:23` by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/111

## [0.0.7] - 2025-01-28

- Migrated to IntelliJ Gradle Plugin 2.0 to support Gateway 2024.3 by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/100

## [0.0.6] - 2024-10-15

- Closing the Jet Brains IDE window should stop the related Gateway process by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/85

## [0.0.5] - 2024-08-16

- Support Gateway 2024.2

## [0.0.4] - 2024-07-16

- Show namespace name when there are DevWorkspaces from multiple OpenShift projects by @azatsarynnyy in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/67

## [0.0.3] - 2024-04-25

- Added the plugin icon shown on the Marketplace page.

## [0.0.2] - 2024-04-23

- feat: Gateway plugin should remember the connection session to OpenSh… by @tolusha in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/33
- chore: UI improvements by @tolusha in https://github.com/redhat-developer/devspaces-gateway-plugin/pull/37

## [0.0.1] - 2024-03-04

- Connecting to a running DevWorkspace from the Dashboard in one click
- Connecting to a DevWorkspace from the Gateway window
- Stopping DevWorkspace after closing the Jet Brains Client
- @azatsarynnyy
- @tolusha

[Unreleased]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.13...HEAD
[0.0.13]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.12...v0.0.13
[0.0.12]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.11...v0.0.12
[0.0.11]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.10...v0.0.11
[0.0.10]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.9...v0.0.10
[0.0.9]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.8...v0.0.9
[0.0.8]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.7...v0.0.8
[0.0.7]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.6...v0.0.7
[0.0.6]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.5...v0.0.6
[0.0.5]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.4...v0.0.5
[0.0.4]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/redhat-developer/devspaces-gateway-plugin/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/redhat-developer/devspaces-gateway-plugin/commits/v0.0.1
