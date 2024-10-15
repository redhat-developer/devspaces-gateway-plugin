# OpenShift Dev Spaces Gateway Plugin

[![Build](https://github.com/redhat-developer/devspaces-gateway-plugin/workflows/Build/badge.svg)](https://github.com/redhat-developer/devspaces-gateway-plugin/actions/workflows/build.yml)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.redhat.devtools.gateway.svg)](https://plugins.jetbrains.com/plugin/24234-openshift-dev-spaces)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.redhat.devtools.gateway.svg)](https://plugins.jetbrains.com/plugin/24234-openshift-dev-spaces)

<!-- Plugin description -->
<!-- This specific section is a source for the [plugin.xml](/src/main/resources/META-INF/plugin.xml) file which will be extracted by the [Gradle](/build.gradle.kts) during the build process. -->
Plugin for JetBrains Gateway enables local desktop development experience with the IntelliJ IDEs connected to OpenShift Dev Spaces.
<!-- Plugin description end -->

## Development
- To test the plugin quickly against the Gateway instance bundled with the plugin, run:

```console
./gradlew runIde
```

- To skip opening the Gateway main window and connect to an already running workspace with the IDEA dev server:

```console
./gradlew runIde --args="jetbrains-gateway://connect#type=devspaces&dwNamespace=john-che&dwName=my-ws"
```

with replacing your DevWorkspace's namespace and name in `dwNamespace` and `dwName` parameters.

### Building
1. Run:

```console
./gradlew clean buildPlugin
```

2. Find the built plugin in the `build/distributions` folder.

### Installation
In the Gateway, click the gear button <kbd>⚙️</kbd>, and choose `Manage Providers` to open the `Plugins` window.

There're a couple of options to install the plugin:

- Search for "OpenShift Dev Spaces".
- Click the gear button <kbd>⚙️</kbd> and choose `Install Plugin from Disk...`. Then,
  - choose the built plugin (zip) located at the `build/distributions` folder or
  - download the plugin from the [latest release](https://github.com/redhat-developer/devspaces-gateway-plugin/releases/latest)

### IntelliJ Plugin Verifier
To check the plugin compatibility against the Gateway versions defined in the [gradle.properties](./gradle.properties) file.

```console
./gradlew runPluginVerifier
```

### Troubleshooting
To check the state of the port forwarding:
```console
sudo lsof -i -P | grep LISTEN | grep 5990
```

## Release
1. Find a draft release on the [Releases](https://github.com/redhat-developer/devspaces-gateway-plugin/releases) page. The draft is created and updated automatically on each push to the `main` branch.
2. Edit the draft and use the `Publish release` button. The [Release](https://github.com/redhat-developer/devspaces-gateway-plugin/blob/main/.github/workflows/release.yml) Workflow will attach the built plugin artifact to the published release.
3. Upload the plugin artifact to Jet Brains Marketplace.
4. Find the [`Changelog update` PR](https://github.com/redhat-developer/devspaces-gateway-plugin/pulls), created automatically by the [Release](https://github.com/redhat-developer/devspaces-gateway-plugin/blob/main/.github/workflows/release.yml) Workflow, and merge it.
5. Update the `pluginVersion` in the [gradle.properties](https://github.com/redhat-developer/devspaces-gateway-plugin/blob/main/gradle.properties) file.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
