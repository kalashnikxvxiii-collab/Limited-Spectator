# Developer Guide - Limited Spectator v2.0.1

## Quick Start

### Prerequisites
- **Java**: 21+ (OpenJDK or similar)
- **Gradle**: 8.10.2+ (included via wrapper)
- **IDE**: IntelliJ IDEA or Eclipse (with Gradle support)

### Clone & Setup
```bash
git clone https://github.com/karashi/limitedspectator.git
cd limitedspectator
```

### Build All Loaders
```bash
# Windows
.\gradlew.bat build

# Linux/Mac
./gradlew build
```

**Output**: JAR files in `*/build/libs/`
- `common/build/libs/common-2.0.1.jar` (147 KB)
- `neoforge/build/libs/LimitedSpectator-neoforge-2.0.1.jar` (174 KB)
- `fabric/build/libs/fabric-2.0.1.jar` (159 KB)
- `quilt/build/libs/quilt-2.0.1.jar` (159 KB)

---

## Project Structure

### Multi-Project Layout
```
MDK-1.21-ModDevGradle-main/
├── settings.gradle                 # Multi-project configuration
├── gradle.properties               # Shared properties (versions, mod metadata)
├── common/                         # Loader-agnostic code
│   ├── build.gradle
│   └── src/main/java/com/karashi/limitedspectator/
│       ��── SpectatorConfig.java    # Interface (no MC deps)
│       ├── CommonConfig.java       # TOML parser (no MC deps)
│       └── ConfigReloadWatcher.java # File watcher (no MC deps)
├── neoforge/                       # NeoForge-specific
│   ├── build.gradle
│   └── src/main/java/com/karashi/limitedspectator/
│       ├── SpectatorMod.java       # Main mod class
│       ├── ModConfig.java          # NeoForge config
│       ├── SpectatorManager.java   # (duplicated from common)
│       ├── SpectatorState.java     # (duplicated from common)
│       ├── DistanceValidationResult.java # (duplicated from common)
│       ├── client/
│       │   └── ClientEventHandler.java
│       └── network/
│           └── NetworkHandler.java
├── fabric/                         # Fabric-specific
│   ├── build.gradle
│   └── src/main/java/com/karashi/limitedspectator/
│       ├── LimitedSpectatorFabric.java
│       ├── FabricSpectatorConfig.java
│       ├── SpectatorManager.java   # (duplicated from common)
│       ├── SpectatorState.java     # (duplicated from common)
│       └── DistanceValidationResult.java # (duplicated from common)
└── quilt/                          # Quilt-specific
    ├── build.gradle
    └── src/main/java/com/karashi/limitedspectator/
        ├── LimitedSpectatorQuilt.java
        ├── QuiltSpectatorConfig.java
        ├── SpectatorManager.java   # (duplicated from common)
        ├── SpectatorState.java     # (duplicated from common)
        └── DistanceValidationResult.java # (duplicated from common)
```

### Key Design Decisions

**Why Multi-Project?**
- ✅ Isolates loader-specific dependencies
- ✅ Prevents cross-loader compilation conflicts
- ✅ Enables parallel builds
- ✅ Clear separation of concerns

**Why Duplicate SpectatorManager?**
- ❌ Cannot be in common (uses Minecraft classes: Vec3, ResourceKey<Level>)
- ✅ Minimal duplication (~5% of codebase)
- ✅ Acceptable trade-off for clean architecture
- 🔄 Future: Consider shared JAR dependency if duplication becomes problematic

---

## Build Commands

### Build Specific Module
```bash
# Common (loader-agnostic)
.\gradlew.bat :common:build

# NeoForge
.\gradlew.bat :neoforge:build

# Fabric
.\gradlew.bat :fabric:build

# Quilt
.\gradlew.bat :quilt:build
```

### Clean Build
```bash
.\gradlew.bat clean build
```

### Build with Verbose Output
```bash
.\gradlew.bat build --info
```

### Run Development Client (NeoForge only)
```bash
.\gradlew.bat :neoforge:runClient
```

### Run Development Server (NeoForge only)
```bash
.\gradlew.bat :neoforge:runServer
```

---

## Development Workflow

### Adding a New Feature

#### 1. Loader-Agnostic Logic
If the feature doesn't depend on Minecraft classes:
- Add to `common/src/main/java/com/karashi/limitedspectator/`
- Update `SpectatorConfig` interface if needed
- Shared across all loaders automatically

#### 2. Loader-Specific Implementation
If the feature requires Minecraft classes:
- Add to `neoforge/src/main/java/com/karashi/limitedspectator/`
- Add to `fabric/src/main/java/com/karashi/limitedspectator/`
- Add to `quilt/src/main/java/com/karashi/limitedspectator/`
- Use same class names for consistency

#### 3. Configuration
If the feature is configurable:
- Add property to `CommonConfig.java`
- Add getter to `SpectatorConfig` interface
- Update `gradle.properties` if adding new mod properties

### Example: Adding a New Configuration Option

**Step 1**: Update `CommonConfig.java`
```java
public class CommonConfig {
    public boolean myNewFeature = true;  // Add field
    
    // In loadFromFile():
    myNewFeature = config.getBoolean("features.my_new_feature", true);
}
```

**Step 2**: Update `SpectatorConfig.java` interface
```java
public interface SpectatorConfig {
    boolean isMyNewFeatureEnabled();
}
```

**Step 3**: Implement in all adapters
```java
// NeoForgeSpectatorConfig.java
@Override
public boolean isMyNewFeatureEnabled() {
    return ModConfig.MY_NEW_FEATURE.get();
}

// FabricSpectatorConfig.java
@Override
public boolean isMyNewFeatureEnabled() {
    return config.myNewFeature;
}

// QuiltSpectatorConfig.java
@Override
public boolean isMyNewFeatureEnabled() {
    return config.myNewFeature;
}
```

**Step 4**: Use in SpectatorManager
```java
if (config.isMyNewFeatureEnabled()) {
    // Feature logic
}
```

---

## Code Organization

### Common Module (Loader-Agnostic)
**Allowed Dependencies**:
- ✅ Java standard library
- ✅ SLF4J (logging)
- ❌ Minecraft classes
- ❌ Loader-specific APIs

**Responsibilities**:
- Configuration parsing (TOML)
- File watching (hot-reload)
- Configuration interface definition

### Loader Modules (NeoForge/Fabric/Quilt)
**Allowed Dependencies**:
- ✅ Common module
- ✅ Minecraft classes
- ✅ Loader-specific APIs
- ✅ Loader-specific libraries

**Responsibilities**:
- Event handling
- Command registration
- Network communication
- Client-side logic

---

## Testing

### Manual Testing Checklist

#### NeoForge
```bash
.\gradlew.bat :neoforge:runClient
# In-game:
# 1. /spectator - Enter spectator mode
# 2. /survival - Exit spectator mode
# 3. Edit config/limitedspectator-common.toml
# 4. /reload - Verify hot-reload works
```

#### Fabric/Quilt
1. Build JAR: `.\gradlew.bat :fabric:build`
2. Copy JAR to Minecraft mods folder
3. Launch Minecraft with Fabric/Quilt loader
4. Test commands and configuration

### Automated Testing
Currently: Manual testing only
Future: Add JUnit tests for SpectatorManager

---

## Debugging

### Enable Debug Logging
Uncomment debug logs in:
- `neoforge/src/main/java/com/karashi/limitedspectator/SpectatorMod.java`
- `neoforge/src/main/java/com/karashi/limitedspectator/client/ClientEventHandler.java`

### Common Issues

**Issue**: Build fails with "duplicate entry icon.png"
- **Solution**: Already fixed in build.gradle with `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`

**Issue**: Fabric/Quilt JAR doesn't load
- **Solution**: Verify `fabric.mod.json` or `quilt.mod.json` has correct entrypoint

**Issue**: Config file not loading
- **Solution**: Check `config/limitedspectator-common.toml` exists and is readable

---

## Architecture Patterns

### Adapter Pattern (Configuration)
```
SpectatorConfig (interface)
    ↓
NeoForgeSpectatorConfig (adapts ModConfig)
FabricSpectatorConfig (adapts CommonConfig)
QuiltSpectatorConfig (adapts CommonConfig)
```

### Singleton Pattern (Configuration)
```java
public class FabricSpectatorConfig implements SpectatorConfig {
    private static final FabricSpectatorConfig INSTANCE = new FabricSpectatorConfig();
    
    public static FabricSpectatorConfig getInstance() {
        return INSTANCE;
    }
}
```

### Strategy Pattern (Spectator Manager)
```java
SpectatorManager manager = new SpectatorManager(config);
manager.enterSpectatorMode(uuid, position, dimension);
DistanceValidationResult result = manager.validateDistance(uuid, position);
```

---

## Performance Considerations

### Hot-Reload (Fabric/Quilt)
- File polling every 5 seconds
- CPU impact: <0.1%
- Memory: ~1 KB per watcher thread
- Configurable interval in `ConfigReloadWatcher.java`

### State Management
- In-memory HashMaps (cleared on logout)
- No database persistence
- Suitable for single-server deployments
- Future: Add persistent storage option

---

## Contributing

### Code Style
- **Java**: 21+
- **Formatting**: 4-space indentation
- **Naming**: camelCase for variables/methods, PascalCase for classes
- **Comments**: Javadoc for public APIs, inline for complex logic

### Commit Messages
```
[LOADER] Feature: Brief description

Detailed explanation if needed.

Fixes #123
```

Example:
```
[FABRIC] Feature: Add hot-reload support

Implemented ConfigReloadWatcher for automatic config reloading.
Hooks into /reload command for manual reload.

Fixes #45
```

### Pull Request Process
1. Fork repository
2. Create feature branch: `git checkout -b feature/my-feature`
3. Commit changes: `git commit -am '[LOADER] Feature: Description'`
4. Push to branch: `git push origin feature/my-feature`
5. Create Pull Request with description

---

## Troubleshooting

### Gradle Issues

**Issue**: `Could not resolve dependency`
- **Solution**: Run `.\gradlew.bat clean` and retry

**Issue**: `Daemon process has died`
- **Solution**: Run with `--no-daemon` flag

**Issue**: `Out of memory during build`
- **Solution**: Increase heap in `gradle.properties`: `org.gradle.jvmargs=-Xmx8G`

### IDE Issues

**IntelliJ IDEA**:
- Right-click project → "Load/Unload Modules"
- File → Invalidate Caches → Restart

**Eclipse**:
- Project → Clean
- Project → Refresh (F5)

---

## Resources

- **Minecraft Modding**: https://docs.neoforged.net/
- **Fabric Wiki**: https://fabricmc.net/wiki/
- **Quilt Wiki**: https://quiltmc.org/wiki/
- **Gradle**: https://gradle.org/

---

## License

MIT License - See LICENSE file for details

---

## Contact

- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Email**: kalashnikxvxiii@gmail.com

---

**Last Updated**: 2026-06-05
**Version**: 2.0.1
