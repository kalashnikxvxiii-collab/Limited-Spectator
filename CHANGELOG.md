# Changelog

All notable changes to the Limited Spectator mod will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.0.0-alpha.1] - 2026-06-06

### 🚀 Minecraft 26.1.2 Support — Alpha 1

**Pre-release for testing.** Full port of the mod to Minecraft 26.1.2 — the first release on Mojang's new year-based versioning scheme (`YY.release.patch`) and the first MC release with official, stable Mojang mappings. This is a major rewrite at the toolchain and API level; semantics of the spectator mode (commands, restrictions, distance limits, HUD-hide UX) are preserved.

The 1.21.x line continues to receive critical fixes on the [`legacy-1.21`](https://github.com/kalashnikxvxiii/Limited-Spectator/tree/legacy-1.21) branch (last stable: v2.0.1).

### Added
- **MC 26.1.2 compatibility** for the NeoForge and Fabric loaders.
- New static helpers `displayMessage(player, msg, useActionBar)` and `hasPermissionLevel(source, level)` in `SpectatorMod` (NeoForge) and `LimitedSpectatorFabric` (Fabric) to centralise the API renames so individual call sites stay tidy.

### Changed
- **Java 25** is now the minimum runtime (was Java 21). Mojang ships Java 25 with MC 26.1 end-user installers.
- **Toolchain**:
  - Gradle wrapper bumped from 8.10 to 9.4.0
  - NeoForge: switched from NeoGradle 7.0.167 to **ModDevGradle 2.0.141** (the recommended plugin family for 26.1+)
  - Fabric: Loom 1.7.3 → 1.16.3, plugin ID renamed from `fabric-loom` to `net.fabricmc.fabric-loom`
  - foojay-resolver-convention 0.8.0 → 1.0.0
- **Dependencies**:
  - NeoForge 21.1.217 → 26.1.2.71
  - Fabric Loader 0.16.5 → 0.19.2
  - Fabric API 0.107.0+1.21.1 → 0.150.0+26.1.2
  - JUnit Jupiter 5.10.1 → 5.13.2 (+ explicit `junit-platform-launcher` for Gradle 9.x)
  - Mockito 5.8.0 → 5.18.0
- **API renames** (from MC 26.1's deobfuscation pass; no behavioural change):
  - `ResourceLocation` → `Identifier`
  - `ResourceKey<T>.location()` → `.identifier()`
  - `ServerPlayer.displayClientMessage(Component, boolean)` split into `sendSystemMessage(Component)` (chat) and `sendOverlayMessage(Component)` (action bar)
  - `CommandSourceStack.hasPermission(int)` removed → new `PermissionSet`/`Permission` API (mapped to old semantics via `hasPermissionLevel()` helper; **TODO**: proper named-permission rewrite in a follow-up alpha)
  - `ServerPlayer.server` field is now private → `player.level().getServer()`
  - `Entity.teleportTo(ServerLevel, x, y, z, yaw, pitch)` widened to `teleportTo(level, x, y, z, Set<Relative>, yaw, pitch, boolean)`
  - `Window.getWindow()` (raw GLFW handle accessor) → `.handle()`
  - `InputConstants.isKeyDown(long, int)` → `isKeyDown(Window, int)`
  - `TriState` moved from `net.neoforged.neoforge.common.util` to `net.minecraft.util` (Mojang promoted it to a vanilla type)
- **Fabric build**: Loom 1.16+ no longer remaps Minecraft or mods (MC is unobfuscated). `modImplementation` → plain `implementation`, no more `mappings` line, no more `remapJar`. Plain `jar` task is the published artifact.

### Removed
- **Quilt loader support.** Quilt Loader has not shipped a Minecraft 26.1.x release (last public reference tracks 1.20.1 as of early 2026). The `quilt/` subproject was deleted from this branch along with the `runQuilt*.bat` scripts and `build-quilt.gradle`. The Quilt JAR (v2.0.1) remains available on the `legacy-1.21` branch and Modrinth/CurseForge.
- **Carry-over CVE-pinning `resolutionStrategy`** in root `build.gradle` (Netty 4.1.125.Final, Log4j 2.25.3, Commons Lang 3.18.0, LZ4 1.10.1). `io.netty:netty-codec-compression:4.1.125.Final` does not exist in the Netty layout MC 26.1 ships, and the bundled Netty/Log4j/Commons Lang versions in 26.1 already include the 2025-Q4 / 2026-Q1 patches we were forcing. Re-introduce per-CVE only if a future dependency audit flags a transitive vulnerability that NeoForge or Fabric haven't already addressed upstream.
- **Pre-multi-project leftovers** that had been quietly tracked in git: a stale root `src/` (24 duplicated files), `build-fabric.gradle`, `build-quilt.gradle`, a Mixin-config `META-INF/MANIFEST.MF` referring to a Mixin file the project doesn't use, and three accidentally-tracked test dump txt files.

### Known issues (open for the alpha)
- `hasPermissionLevel()` is a coarse approximation: any `level >= 1` collapses to "ALL_PERMISSIONS only" (server console / full-op). The fine-grained level 1/2/3/4 distinctions from vanilla are not preserved. Track in a follow-up alpha.
- The NeoForge build still emits two deprecation warnings on `compileJava` from inside vanilla NeoForge classes — not from this mod.

### Internal
- Both source sets (`:neoforge`/`:fabric` and `:common`) are grouped under the same `limitedspectator` mod identifier via the loader's plugin API (`neoForge { mods { ... { sourceSet ... } } }` for ModDevGradle and `loom { mods { ... { sourceSet ... } } }` for Loom), so the `:common` classes are part of the same dev mod and the production JAR.
- `:common` now applies the `net.fabricmc.fabric-loom-companion` marker plugin so Loom 1.16+ can include its source set in the `:fabric` mod block.
- `:common` Gradle configuration cache is disabled while Loom 1.16-SNAPSHOT settles (see [FabricMC/fabric-loom#1349](https://github.com/FabricMC/fabric-loom/issues/1349)).

---

## [2.0.1] - 2026-06-05

### 🔧 Compatibility & Cleanup Hotfix

Drop-in update on top of v2.0.0. Lets vanilla clients connect to servers running the NeoForge build, aligns Minecraft version ranges across all manifests, fixes the dev environment for contributors on Linux, and corrects long-standing version metadata drift inside the published JARs.

### Fixed
- **NeoForge: vanilla clients can now connect to servers running the mod.** The HUD-sync network channel is now registered as `optional()`, so the handshake no longer rejects clients that don't have Limited Spectator installed. `sendHudState` also checks `hasChannel()` before dispatching, so server-only deployments stay error-free. (Closes #2)
- **Internal version metadata.** Since v1.2.1 → v2.0.0, the NeoForge JAR's `META-INF/neoforge.mods.toml` had been declaring `version="1.21.x-1.2.1"` and the project-level `modrinth.mod.json` had been declaring `"version": "1.1.1"` — both hard-coded, never updated through the v2.0.0 release. Now `neoforge.mods.toml` uses the `${mod_version}` placeholder (the `ProcessResources` `expand` was already wired up for it) and `modrinth.mod.json` is synced to the current version. The Fabric and Quilt manifests already used `${version}` and were unaffected.

### Changed
- **Modrinth manifest**: description now explicitly states the mod is server-authoritative and that client install is recommended-but-optional. Vanilla clients are supported on all loaders.
- **README**: new "🌐 Environment Requirements" section with a per-loader breakdown of where the mod must be installed vs. where it is recommended for the best UX.
- **Minecraft version range — consistency pass across manifests.** The mod has always been intended (and documented) as compatible with the full **1.21.x** family, tested up to 1.21.11. Two manifests drifted from that intent:
  - `modrinth.mod.json` declared `"minecraft": "1.21.1"` (exact-match, only 1.21.1). Now `">=1.21.1 <1.22"`, matching the actual supported range.
  - `neoforge.mods.toml` declared `versionRange="[1.21.1,)"` (open upper bound, would attempt to load on future MC 1.22 / 26.x and crash on first renamed API call). Now `"[1.21.1,1.22)"`, matching `gradle.properties` and the other loader manifests.

### Internal
- Comprehensive `.gitattributes` rules: all source/config/script files normalized to LF on commit, `*.bat`/`*.cmd` kept as CRLF, common binaries marked. Fixes `./gradlew` on Linux (CRLF shebang broke `/bin/sh`).
- **NeoForge dev runs (`:neoforge:runClient` / `:neoforge:runServer`)** now correctly load the `:common` module's classes (`SpectatorConfig`, `CommonConfig`, `ConfigReloadWatcher`). Both source sets are grouped under the same `limitedspectator` mod identifier via NeoGradle's `modSources { add 'limitedspectator', ... }` API so they end up in the same fake-fat-jar at dev time. Previously the dev server crashed at boot with `NoClassDefFoundError: SpectatorConfig` even though the production JAR (which bundles `:common`'s output) worked fine.
- `build.gradle` runs DSL: switched from `getArguments().addAll(...)` to the canonical NeoGradle 7 shorthand `arguments 'arg1', 'arg2'`.

---

## [2.0.0] - 2026-01-18

### 🚀 Multi-Loader Architecture Release

Major architectural overhaul introducing support for NeoForge, Fabric, and Quilt mod loaders with shared core logic.

### Added
- **Multi-Loader Support** - Three separate builds for different mod loaders:
  - `LimitedSpectator-neoforge-2.0.0.jar` (171 KB) - Full configuration support
  - `LimitedSpectator-fabric-2.0.0.jar` (156 KB) - Hardcoded defaults
  - `LimitedSpectator-quilt-2.0.0.jar` (158 KB) - Uses Fabric API compatibility
- **Core Abstraction Layer**:
  - `SpectatorConfig` interface - Loader-agnostic configuration contract
  - `SpectatorManager` class - Shared business logic (100% code reuse)
  - `SpectatorState` record - Immutable state representation
  - `DistanceValidationResult` record - Type-safe validation results
- **Loader-Specific Adapters**:
  - `NeoForgeSpectatorConfig` - Adapts NeoForge config to interface
  - `FabricSpectatorConfig` - Hardcoded defaults for Fabric
  - `QuiltSpectatorConfig` - Delegates to Fabric config (composition pattern)
- **Fabric Implementation**:
  - `LimitedSpectatorFabric` - Fabric entry point with Fabric API integration
  - Command registration via `CommandRegistrationCallback.EVENT`
  - Event handlers via `ServerTickEvents` and `ServerPlayConnectionEvents`
- **Quilt Implementation**:
  - `LimitedSpectatorQuilt` - Quilt entry point (100% Fabric API compatible)
  - Uses Fabric API for all events and commands
  - Demonstrates perfect Fabric-Quilt compatibility
- **Build System**:
  - Separate Gradle build files per loader (`build.gradle`, `build-fabric.gradle`, `build-quilt.gradle`)
  - Source exclusions to prevent cross-loader compilation errors
  - Java 21 toolchain configuration

### Changed
- **Architecture**: Refactored from monolithic NeoForge-only to multi-loader with shared core
- **Code Organization**: 
  - Core logic moved to loader-agnostic classes
  - Loader-specific code isolated in separate packages
  - Zero code duplication (>95% shared code)
- **Logging**: Replaced `System.out.println` with SLF4J Logger in Fabric/Quilt implementations
- **Build Process**: 
  - NeoForge build excludes Fabric/Quilt sources
  - Fabric build excludes NeoForge/Quilt sources
  - Quilt build excludes NeoForge/Fabric entry points

### Technical
- **Thread-Safe State Management**: `SpectatorManager` uses `ConcurrentHashMap` for multiplayer safety
- **Type-Safe Validation**: Enum-based validation results prevent null pointer exceptions
- **Backward Compatibility**: Legacy HashMaps maintained with `@Deprecated` annotation
- **Dual-Write Strategy**: Ensures external mods/mixins continue working
- **Clean Separation**: Zero loader dependencies in core business logic

### Performance
- Shared `SpectatorManager` instance across all loaders
- Singleton pattern for config adapters
- Lazy configuration evaluation
- Minimal per-tick overhead (~0.01ms per spectator)

### Documentation
- Updated README.md with multi-loader installation instructions
- Added loader comparison table
- Documented build commands for each loader

### Migration Notes
- **NeoForge users**: No changes required - fully backward compatible with v1.x
- **Fabric/Quilt users**: New! Download the appropriate JAR for your loader
- **Configuration**: Only NeoForge version supports TOML config (Fabric/Quilt use hardcoded defaults)

### Known Limitations
- Quilt version requires Fabric API (QSL support planned)

---

## [1.2.1] - 2025-12-23

### 🔧 Code Quality Hotfix

Minor code quality improvements and performance optimizations in client-side event handling.

### Fixed
- **Removed duplicate condition check** in `ClientEventHandler.onMouseClick()`
  - Merged redundant `isSpectator()` check that was executed twice
  - Simplified logic flow for better readability
- **Commented out production debug log** in `ClientEventHandler.onRenderHud()`
  - Debug log was executing every frame, causing log spam
  - Moved to commented debug section for development use only

### Performance
- Reduced redundant condition checks in mouse input handling
- Eliminated per-frame debug logging in production builds

### Technical
- No functional changes or API modifications
- Pure code cleanup and optimization
- Build remains compatible with v1.2.0

---

## [1.2.0] - 2025-12-23

### 🌍 Multilingual Release

This release introduces full translation support for European languages, making Limited Spectator accessible to a wider international audience.

### Added
- **Multilingual Translation System** - Complete i18n support for all user-facing messages
  - **English** (en_us) - Base language
  - **Italian** (it_it) - Full Italian translation
  - **German** (de_de) - Full German translation
  - **French** (fr_fr) - Full French translation
  - **Spanish** (es_es) - Full Spanish translation
- **Translated Messages**:
  - Command feedback messages (`/spectator` and `/survival`)
  - Distance limit notifications (exceeded/reached)
  - Error messages (dimension travel blocked, crafting blocked)
  - All user-facing text automatically displays in player's Minecraft language

### Changed
- Replaced all hardcoded `Component.literal()` messages with `Component.translatable()`
- Added `MutableComponent` import for proper message building
- Message system now respects player's language preference

### Technical
- Created `src/main/resources/assets/limitedspectator/lang/` directory structure
- Translation files use standard Minecraft JSON format with key-value pairs
- Translation keys follow naming convention: `limitedspectator.command.*`, `limitedspectator.error.*`
- Messages automatically formatted with appropriate chat colors (AQUA, GREEN, RED, GRAY)

### Documentation
- Updated README.md with new Localization section
- Removed multilingual translation from Future Roadmap (completed)
- Added multilingual support to Features section

---

## [1.1.2] - 2025-12-23

### 🔒 Security Release

This release addresses critical security vulnerabilities in transitive dependencies and expands version compatibility.

### Security
- **Updated Netty to 4.1.125.Final** - Fixes CVE-2025-58057 (BrotliDecoder DoS vulnerability, CVSS 7.5)
  - Previous: 4.1.118.Final (CVE-2025-24970)
  - Impact: Prevents denial of service attacks via crafted compressed input
- **Updated Log4j Core to 2.25.3** - Fixes CVE-2025-68161 (TLS hostname verification, CVSS 5.4)
  - New dependency forcing for both log4j-core and log4j-api
  - Impact: Prevents man-in-the-middle attacks on log traffic
- **Updated LZ4-Java to 1.10.1** - Fixes CVE-2025-66566 (buffer disclosure vulnerability, CVSS 7.5)
  - Migrated from org.lz4:lz4-java to at.yawk.lz4:lz4-java (new official group ID)
  - Previous: 1.8.0
  - Impact: Prevents sensitive data disclosure via output buffer reuse
- **Maintained Commons Lang3 3.18.0** - Continues protection against CVE-2025-48924 (CVSS 5.3)

### Changed
- **Version scheme updated from 1.21.1-1.1.1 to 1.21.x-1.1.2**
  - Reflects compatibility with all Minecraft 1.21.x versions (1.21.1, 1.21.2, ... 1.21.11+)
  - Dependency range already configured as [1.21.1,) in neoforge.mods.toml
- **Documentation updates**:
  - Updated README.md to clarify Minecraft 1.21.1+ compatibility
  - Updated build output JAR name to LimitedSpectator-1.21.x-1.1.2.jar

### Technical
- All dependency version forcing configured via build.gradle resolutionStrategy
- Build tested successfully with clean build (no warnings or errors)
- JAR output: `build/libs/LimitedSpectator-1.21.x-1.1.2.jar`
- Gradle configuration remains at 8.10 with NeoGradle 7.0.167

### Migration Notes
Users on any 1.21.x Minecraft version can safely upgrade to this release. No configuration changes required.
All existing configs from 1.1.1 remain fully compatible.

---

## [1.1.1] - 2025-11-14

### 🎯 Stable Release

This is the **stable release** following 1.1.0-beta, with cleaned configuration and comprehensive documentation updates.

### Changed
- **Configuration Cleanup**: Removed non-functional config options that were limited by Minecraft engine behavior:
  - Removed `allow_mob_attacks` - mob attacks always blocked (mobs don't target players with `mayfly=true`)
  - Removed `allow_block_breaking` - always blocked in ADVENTURE mode (GameMode restriction)
  - Removed `allow_block_placing` - always blocked in ADVENTURE mode (GameMode restriction)
  - Removed `auto_hide_hud` - behavior hard-coded to always hide (can toggle with F1)
  - Removed `allow_f1_hud_toggle` - F1 toggle always enabled
  - Removed `auto_start_flying` - players must double-tap spacebar (ADVENTURE mode limitation)

- **Documentation Overhaul**: Complete update of all documentation to reflect actual behavior:
  - Clarified that `enable_invulnerability` does NOT prevent fall damage (Minecraft engine always prevents it with `mayfly=true`)
  - Updated `enable_invulnerability` description to specify it protects against mobs, lava, fire, cacti, drowning, etc.
  - Documented HUD behavior as hard-coded (always hides, F1 toggles)
  - Documented that players must double-tap spacebar to fly (ADVENTURE mode behavior)
  - Renamed "Known Issues" sections to "Known Limitations" with proper explanations

### Fixed
- Configuration file now generates cleanly without obsolete options
- All wiki documentation updated (Configuration-Guide, For-Server-Admins, Beta-Features, Features, Commands, FAQ)
- Updated README.md, CHANGELOG.md, Version-Comparison.md, CONTRIBUTING.md
- Removed confusing config options that appeared functional but weren't due to Minecraft limitations

### Technical
- Cleaned up `ModConfig.java` - removed unused config variables and cached values
- Updated `SpectatorMod.java` - hard-coded HUD hide behavior
- Updated `ClientEventHandler.java` - simplified HUD management (removed config checks)
- Generated config file (`limitedspectator-common.toml`) now accurate and contains only functional options

### Migration Notes
If upgrading from 1.1.0-beta, your existing config will continue to work. The removed options will be ignored.
No action required - the mod will use correct behavior regardless of old config values.

---

## [1.1.0-beta] - 2025-11-09

### ⚠️ Beta Release Notice

This is a **beta release** focused on features that work reliably within Minecraft's ADVENTURE mode limitations. Features incompatible with ADVENTURE mode have been removed to ensure stability and prevent confusion.

### Added
- **Complete Configuration System** - Comprehensive TOML-based configuration file (`limitedspectator-common.toml`)
  - **Movement Restrictions**: Configure max distance (-1 to disable), dimension travel, teleport behavior, logout position reset
  - **Player Abilities**: Toggle flight and choose between ADVENTURE/SPECTATOR gamemode
  - **Interaction Controls**: Individually toggle PvP, mob attacks, item drop/pickup, and inventory crafting
  - **Customizable Block Whitelist**: Define exactly which blocks are interactable via Minecraft block IDs
  - **Permission System**: Set required permission levels (0-4) for `/spectator` and `/survival` commands
  - **Client/HUD Settings**: Configure auto-hide HUD and F1 toggle functionality
  - **Message Settings**: Choose action bar vs chat messages, enable/disable distance warnings
- **Inventory Crafting Control** - Block 2x2 crafting grid in player inventory with automatic ingredient restoration
  - Configurable via `allow_inventory_crafting` (default: false)
  - When blocked, ingredients are automatically returned to player's inventory
  - Prevents item loss or duplication during crafting attempts
  - Works with both single-slot and full 2x2 grid recipes
  - Falls back to dropping items on ground if inventory is full
- Configuration validation with sensible defaults and range checking
- Hot-reload support for configuration changes via `/reload` command
- Performance-optimized config value caching
- **CONTRIBUTING.md** - Comprehensive contributor guide with coding standards and known issues

### Fixed
- **Critical**: `/survival` command now correctly teleports players back to original dimension (Overworld/Nether/End)
- **Critical**: Dimension tracking added - `spectatorStartDimensions` HashMap prevents cross-dimension bugs
- Distance boundary enforcement when `teleport_back_on_exceed=false` - players stopped at exact boundary
- HUD behavior improved (later hard-coded in final release)
- Message encoding fixed - removed `§` color codes causing garbled characters (À symbols)
- Messages now use `Component.literal().withStyle(ChatFormatting.XXX)` for proper rendering
- Messages visible even with HUD hidden (action bar works independently)
- Logout handler cleanup for dimension tracking

### Changed
- Distance limit is now fully configurable (default: 75 blocks, set to -1 to disable)
- Block interaction whitelist now supports any Minecraft block ID instead of hardcoded door/trapdoor/gate types
- Command permission levels can now be customized per-command
- All hardcoded values replaced with config-driven logic
- Improved server admin flexibility with granular control over all spectator restrictions
- Enhanced mod description to reflect configurability and beta status

### Technical
- Created `ModConfig.java` with NeoForge ConfigSpec API (26+ configurable options)
- Integrated `ModConfigEvent` listener for hot-reload support
- Updated `SpectatorMod.java` to use config values throughout
- Updated `ClientEventHandler.java` to respect client-side config options
- Added `PlayerEvent.ItemCraftedEvent` handler for inventory crafting control
  - Captures ingredients from crafting container before consumption
  - Clears crafting grid to prevent duplication
  - Restores ingredients with fallback to ground drop if inventory full
  - Handles both single-slot and full 2x2 grid recipes
- Added `LivingIncomingDamageEvent` handler for invulnerability control (partial)
- Added `BlockEvent.BreakEvent` and `BlockEvent.EntityPlaceEvent` handlers
- Added `ClientboundPlayerAbilitiesPacket` for flying state sync (partial)
- Removed hardcoded constants in favor of config references
- Configuration file auto-generates on first launch with detailed comments

### Documentation
- Added comprehensive configuration section with all available options and examples
- Updated Future Roadmap to reflect completed features
- Added example configuration customization scenarios
- Created CONTRIBUTING.md with developer guidelines
- Documented Known Issues section

### Known Limitations (Final Status)

**Note**: Several "issues" identified in beta testing were determined to be Minecraft engine limitations, not bugs. The final release addresses these by:

1. **Fall Damage**: Confirmed as Minecraft core behavior - fall damage is always prevented when `mayfly=true`. Config option retained for other damage types (mobs, lava, fire, etc.).

2. **Auto-Start Flying**: Removed `auto_start_flying` config option. Players must double-tap spacebar (ADVENTURE mode limitation).

3. **Block Breaking/Placing**: Confirmed as ADVENTURE mode GameMode restriction. Removed `allow_block_breaking` and `allow_block_placing` config options.

4. **HUD Behavior**: Hard-coded HUD auto-hide with F1 toggle. Removed `auto_hide_hud` and `allow_f1_hud_toggle` config options.

5. **Mob Attacks**: Always blocked (mobs don't target players with `mayfly=true` anyway). Removed `allow_mob_attacks` config option.

## [1.0.2] - 2025-11-08

### Fixed
- Fixed critical bug where items would disappear from inventory when attempting to drop them in spectator mode
  - Items are now properly returned to the player's inventory when drop is blocked
  - Resolved issue with `ItemTossEvent` that removed items before the event could be cancelled
- Improved item handling to prevent item duplication or loss

### Added
- Server-side event handler for blocking item pickup (`ItemEntityPickupEvent.Pre`)
- Server-side event handler for blocking item dropping with proper inventory restoration (`ItemTossEvent`)
- Enhanced inventory protection in limited spectator mode

### Changed
- Item drop blocking now uses smart inventory restoration to prevent item loss
- Item pickup blocking uses `TriState.FALSE` for proper NeoForge 1.21.1+ compatibility
- Updated `build.gradle` to use `configurations.configureEach` instead of deprecated `configurations.all` for better performance
- Replaced deprecated `programArguments` with `getArguments()` in run configurations (NeoGradle 7.0+ compatibility)

## [1.0.0] - 2025-11-02

### Added
- Initial release of Limited Spectator mod
- Custom `/spectator` command to enter limited spectator mode
- Custom `/survival` command to return to survival mode
- Distance-based teleportation system (75 block radius limit)
- Server-side position tracking and enforcement
- Client-server packet communication for HUD state synchronization
- F1 key toggle support for temporary HUD visibility
- Selective block interaction (doors, trapdoors, and fence gates allowed)
- PvP and mob attack prevention in spectator mode
- Dimension travel blocking while in spectator mode
- Automatic survival mode restoration on player logout
- Console logging with `[LimitedSpectator]` prefix for debugging

### Features
- Limited spectator mode using Adventure game mode with flight abilities
- HUD hidden by default with F1 toggle capability
- Player repositioning on distance limit exceeded or `/survival` command
- Flight enabled via double-space bar press
- All restrictions enforced server-side for multiplayer security
- Compatible with Minecraft 1.21.1 and NeoForge 21.1.0+

[Unreleased]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.2.1...HEAD
[1.2.1]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.1.2...v1.2.0
[1.1.2]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.1.0-beta...v1.1.1
[1.1.0-beta]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.0.2...v1.1.0-beta
[1.0.2]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/kalashnikxvxiii/Limited-Spectator/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/kalashnikxvxiii/Limited-Spectator/releases/tag/v1.0.0
