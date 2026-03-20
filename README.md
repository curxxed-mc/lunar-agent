# lunar-agent

> **This project is not a user-friendly tool. It is a low-level JVM instrumentation framework that requires deep knowledge of Minecraft, Mixin, and Lunar Client internals.**
> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

---

A Java agent that injects mods into Lunar Client 1.8.9 at runtime, bypassing Lunar's mod discovery entirely. Supports `@AgentMod` mods (no Forge required) as well as Forge mods when running the Lunar Client + Forge module. Includes automatic SRG→MCP remapping, mixin annotation patching, and bake cache invalidation.

You drop a mod JAR in. It works. That's the goal.

---

## Important: Forge mod compatibility

Vanilla Lunar Client does not ship any Forge classes. Forge mods will only work when running the **Lunar Client + Forge module**, which provides FML and the Forge event bus. Attempting to inject a Forge mod into plain Lunar Client will fail at class resolution.

For mods that don't need Forge — no FML, no event bus, just Minecraft and Mixins — use `@AgentMod` instead. These work on both vanilla Lunar Client and the Forge module.

---

## How it works

1. **`premain` fires before `main()`** — The JVM calls the agent's `premain` before anything else. It deletes the Lunar bake cache so Mixin re-runs on the next launch, reads your `agent-mods.json`, scans each mod JAR for `@Mod` or `@AgentMod` annotated classes, registers bytecode transformers, and encodes the mod list into a system property for later.

2. **Bytecode transformers patch mod classes on load** — Three transformers run on every mod class as it loads:
   - `RuntimeRemapper` — translates SRG method/field names (`func_XXXXX_x`, `field_XXXXX_x`) to MCP names. Means a normally reobfuscated Forge JAR works on Lunar without source changes.
   - `MixinAnnotationPatcher` — forces `remap = false` on every mixin annotation and clamps `@Mixin` priority to 100, safely below Lunar's own mixins.
   - `EventConstructorPatcher` — injects a synthetic no-args constructor into any `Event` subclass that's missing one, so the Forge event bus can instantiate it.

3. **Mixin registrar waits for Ichor** — A background thread polls every 50ms until `MixinEnvironment` appears in the loaded classes. It then adds the agent JAR and each mod JAR to Ichor's classloader and registers their mixin configs. All JARs are added before any configs are registered to avoid a race where `AgentMixinBootstrap` fires before mod classes are on the classpath.

4. **`AgentMixinBootstrap` fires mod init** — The agent ships its own mixin targeting `Minecraft.runTick`. On the first tick it reads the mod list from the system property, builds a `URLClassLoader` with Genesis as parent (bypassing IchorPipeline), instantiates each mod class via `Unsafe.allocateInstance`, and calls `init()` on `@AgentMod` classes or the `@EventHandler` init method on Forge `@Mod` classes. Fires exactly once.

5. **The system property gates Forge mod init** — Before initialising a Forge `@Mod`, the bootstrap checks that the mod's agent property is set. If the mod is loaded normally through FML, the property is never set, the agent does nothing, and Forge's own lifecycle handles init as usual. `@AgentMod` classes have no Forge lifecycle at all, so they always proceed.

---

## Setup

### 1. Build the agent JAR

```
./gradlew shadowJar
```

The shadow plugin shades and relocates ASM so it doesn't conflict with Lunar's own copy. The output `MANIFEST.MF` declares:

```
Premain-Class: net.curxxed.dev.agent.AgentBootstrap
Agent-Class:   net.curxxed.dev.agent.AgentBootstrap
Can-Redefine-Classes:    true
Can-Retransform-Classes: true
```

This is already configured in `build.gradle.kts`. Don't use `./gradlew jar` — the plain JAR doesn't have ASM shaded in and will crash on startup.

### 2. Create your config file

Create `agent-mods.json` anywhere on your system:

```json
{
  "mods": [
    {
      "jar": "C:\\Users\\<User>\\Documents\\lunar-agent\\your-mod.jar",
      "mixin": "mixins.your-mod.json",
      "property": "your-mod.agent.injected"
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `jar` | yes | Absolute path to the mod JAR |
| `mixin` | no | Mixin config filename at the root of the JAR. Omit if the mod has no mixins |
| `property` | no | System property set to `true` when running via agent. Used to gate Forge mod init so the mod still works on other launchers. Not needed for `@AgentMod` mods |

To inject multiple mods, add more entries to the `mods` array.

### 3. Add the JVM argument to Lunar Client

In the Lunar Client launcher go to **Settings → Java Options** and add:

```
-javaagent:C:\Users\<User>\Documents\lunar-agent\lunar-agent-1.0.0.jar=C:\Users\<User>\Documents\lunar-agent\agent-mods.json
```

Replace both paths with wherever you placed the files.

**Enable Advanced Settings first, otherwise the Java Options field won't be visible.**

---

## What the agent handles automatically

You do not need to do any of the following in your mod source:

- `remap = false` on every mixin annotation
- `priority` clamped to 100 on every `@Mixin`
- SRG→MCP name translation on all method and field references
- No-args constructor on custom `Event` subclasses
- Bake cache invalidation on every launch
- Mod instantiation and init call on the first game tick

---

## Writing a mod

### @AgentMod (no Forge required, works on vanilla Lunar Client)

```java
@AgentMod(id = "my-mod", name = "My Mod", version = "1.0.0")
public class MyMod {
    public static MyMod INSTANCE;

    public void init() {
        INSTANCE = this;
        // all field initialisation goes here.
        // Unsafe.allocateInstance bypasses the constructor entirely —
        // anything assigned in a constructor body or as a field initialiser
        // will be null when init() is called.
    }
}
```

### Forge @Mod (requires Lunar Client + Forge module)

```java
@Mod(modid = "your-mod", name = "Your Mod", version = "1.0.0")
public class YourMod {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // event is null when called via the agent — FML never fires this lifecycle.
        // add a null check if you reference it.
        if (event != null) {
            // FML-only path
        }
        // shared init code here, runs on both launchers
    }
}
```

---

## Project structure

```
lunar-agent/
├── src/main/
│   ├── java/net/curxxed/dev/agent/
│   │   ├── AgentBootstrap.java               # premain, transformer registration, mixin registrar
│   │   ├── annotation/
│   │   │   └── AgentMod.java                 # @AgentMod annotation
│   │   ├── config/
│   │   │   └── ModEntry.java                 # record for jar/mixin/property config entries
│   │   └── transformer/
│   │       ├── AgentMixinBootstrap.java      # Minecraft mixin, handles mod init on first tick
│   │       ├── RuntimeRemapper.java          # SRG->MCP name translation
│   │       ├── MixinAnnotationPatcher.java   # remap=false + priority clamping
│   │       └── EventConstructorPatcher.java  # no-args constructor injection for events
│   └── resources/
│       ├── mixins.agent.json
│       └── mappings/
│           ├── methods.csv                   # MCP stable 9.10 — required
│           └── fields.csv                    # MCP stable 9.10 — required
├── build.gradle.kts
├── settings.gradle.kts
└── agent-mods.json                           # runtime config, not in src
```

---

## Troubleshooting

**`[Mod-Agent] JAR not found, skipping`** — The path in `agent-mods.json` is wrong or the file doesn't exist at that location.

**`[Mod-Agent] Error in mixin registrar`** — The mixin config filename doesn't match what's at the root of the JAR, or the JAR wasn't built with the config on its classpath root.

**`[Mod-Agent] No mods to bootstrap`** — No `@Mod` or `@AgentMod` classes were found during premain scanning. Check that your mod has the annotation and that the JAR path in `agent-mods.json` is correct. Also check you're running `shadowJar` and deploying the right output JAR.

**Mod init fails with NPE on field access** — `Unsafe.allocateInstance` bypasses the constructor. Move all field initialisation into `init()`.

**Forge mod classes not found on vanilla Lunar** — Vanilla Lunar Client does not ship Forge classes. Forge mods only work with the Lunar Client + Forge module. Use `@AgentMod` for mods that don't need Forge.

**Works on Lunar but breaks on vanilla Forge** — Make sure `remapJar` is what you're dropping into the Forge mods folder, not `shadowJar`. The `shadowJar` output has MCP names; Forge expects SRG names.

**Injections silently do nothing** — If using a SRG-mapped JAR, check that the mapping CSVs are present in the agent JAR. If the agent logs `Mappings resource not found`, remapping isn't running.

**Mixin conflicts with Lunar** — Switch from `@Overwrite` to `@Inject` or `@Redirect`, or lower the mixin priority below the conflicting mixin.

**`agent library failed to init` on startup** — You used `./gradlew jar` instead of `./gradlew shadowJar`. Always use `shadowJar`.