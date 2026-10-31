# Altar (Fabric, server-side) - Minecraft 26.3

Turn any block into a forging altar. Players sacrifice items, wait out a ritual timer, and receive the forged item.
Requires: Java 25, Fabric Loader 0.19.5+, Fabric API. Players do NOT need the mod installed.

Admin menu (ops): run `/altar` to open the GUI. It includes altar creation with ritual duration and chat announcement controls, recipe editing, altar settings, altar removal, remove-all confirmation, and described global toggles.

The individual commands (`/altar set [seconds]`, `/altar settings`, `/altar recipe`, `/altar clear`, `/altar clearall`, and `/altar toggle <toggle> [true|false]`) remain available for compatibility.

## Build
Push to GitHub; Actions builds the jar (Artifacts tab). Tag v1.0.1 to create a release.
Locally: gradle wrapper --gradle-version 9.6.0 once, then ./gradlew build.
