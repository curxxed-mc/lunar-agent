# lunar-agent

> **This project is not a user-friendly tool. It is a low-level JVM instrumentation framework that requires deep knowledge of Minecraft, Mixin, and Lunar internals.**
> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

---

A Java agent that injects Forge mods into Lunar Client 1.8.9 at runtime, bypassing Lunar's mod discovery entirely. Supports multiple mods, automatic SRG->MCP remapping, mixin annotation patching, and graceful fallback when running without the agent.

You drop a mod JAR in. It works. That's the goal.

---

## How it works

Lunar Client does not support dropping arbitrary Forge mods into a mods folder the normal way. This agent works around that by hooking into the JVM before Lunar even starts:

1. **`premain` fires before `main()`** — The JVM calls the agent's `premain` before anything else. It reads your `agent-mods.json`, scans each mod JAR for `@Mod` classes and their `@EventHandler` init methods, sets system properties, registers bytecode transformers, and encodes the mod list into a system property for later.

2. **Bytecode transformers patch mod classes on load** — Three transformers run on every mod class as Ichor loads them:
    - `RuntimeRemapper` — translates SRG method/field names (`func_XXXXX_x`, `field_XXXXX_x`) to MCP names. This means a normally reobfuscated Forge JAR works on Lunar without any source changes.
    - `MixinAnnotationPatcher` — forces `remap = false` on every mixin annotation and clamps `@Mixin` priority to 100, safely below Lunar's own mixins.
    - `EventConstructorPatcher` — injects a synthetic no-args constructor into any custom `Event` subclass that's missing one, so the event bus can instantiate it.

3. **Mixin registrar waits for Ichor** — A background thread polls every 50ms until `MixinEnvironment` appears in the loaded classes, meaning Ichor is alive. It then adds the agent JAR and each mod JAR to Ichor and registers their mixin configs.

4. **`AgentMixinBootstrap` fires mod init** — The agent ships its own mixin targeting `Minecraft.runTick`. On the first tick it reads the mod list from the system property, instantiates each `@Mod` class via `Unsafe.allocateInstance` (no constructor required), and calls the `@EventHandler` init method with a `null` event. This fires exactly once, then never again.

5. **The system property gates init** — Before doing anything, the bootstrap mixin checks that the mod's agent property is set. If the mod is loaded as a normal Forge mod on a vanilla launcher, the property is never set, the mixin does nothing, and Forge's own lifecycle handles init as usual.

---

## Setup

### 1. Build the agent JAR

Build with `./gradlew shadowJar`. The shadow plugin shades and relocates ASM so it doesn't conflict with Lunar's own copy.

The output `MANIFEST.MF` must declare:
```
Premain-Class: net.curxxed.dev.agent.AgentBootstrap
Agent-Class:   net.curxxed.dev.agent.AgentBootstrap
Can-Redefine-Classes:    true
Can-Retransform-Classes: true
```

This is already configured in `build.gradle.kts`. Don't use `./gradlew jar` — you'll get a JAR without ASM shaded in and it will crash on startup.

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
| `property` | no | System property set to `true` when running via agent. Used to gate init so the mod still works as a normal Forge mod on other launchers |

To inject multiple mods, add more entries to the `mods` array.

### 3. Add the JVM argument to Lunar Client

In the Lunar Client launcher go to **Settings -> Java Options** and add:

```
-javaagent:C:\Users\<User>\Documents\lunar-agent\lunar-agent-1.0.0.jar=C:\Users\<User>\Documents\lunar-agent\agent-mods.json
```

Replace both paths with wherever you placed the files.

**NOTE: Enable Advanced Settings first, otherwise you won't see the Java Options field.**

---

## What the agent handles automatically

You do not need to do any of the following in your mod source. The agent takes care of it at load time:

- `remap = false` on every mixin annotation
- `priority` clamped to 100 on every `@Mixin`
- SRG->MCP name translation on all method and field references
- No-args constructor on custom `Event` subclasses
- Mod instantiation and `init(null)` call on the first game tick

The mod JAR that goes on a normal Forge launcher (via `remapJar`) is the same JAR you drop into `agent-mods.json`. One JAR, two launchers.

---

## Writing a compatible mod

You don't need a bootstrap mixin anymore. The agent ships `AgentMixinBootstrap` which handles all mod init automatically.

The only thing your mod needs for dual-launcher compatibility is the system property gate in your init method. If the property is set, you're running via the agent. If it isn't, FML's lifecycle is in charge:

```java
@Mod(modid = "your-mod", name = "Your Mod", version = "1.0.0")
public class YourMod {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // event may be null when called via agent — FML never fires this lifecycle.
        // add a null check if you reference it.
        if (event != null) {
            // FML path
        }
        // shared init code goes here, runs on both launchers
    }
}
```

That's it. No mixin bootstrap, no manual event bus registration, no special Lunar-specific code.

---

## Project structure

```
lunar-agent/
├── src/main/
│   ├── java/net/curxxed/dev/agent/
│   │   ├── AgentBootstrap.java               # premain, transformer registration, mixin registrar thread
│   │   ├── config/
│   │   │   └── ModEntry.java                 # record for jar/mixin/property config entries
│   │   ├── mixin/
│   │   │   └── AgentMixinBootstrap.java      # the agent's own Minecraft mixin, handles mod init
│   │   └── transformer/
│   │       ├── RuntimeRemapper.java          # SRG->MCP name translation
│   │       ├── MixinAnnotationPatcher.java   # remap=false + priority clamping
│   │       └── EventConstructorPatcher.java  # no-args constructor injection for events
│   └── resources/
│       ├── mixins.agent.json                 # mixin config for AgentMixinBootstrap
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

**`[Mod-Agent] No mods to bootstrap`** — The `lunar.agent.bootstrap.mods` system property was empty, meaning no `@Mod` classes were found during premain scanning. Check that your mod actually has a `@Mod` annotation and that the JAR path in `agent-mods.json` is correct.

**Mod init fails with NPE** — Your `@EventHandler` init method doesn't null-check the event parameter. The agent calls it with `null` because FML never fires the lifecycle. Add a null check.

**Injections silently do nothing** — If you're using a SRG-mapped JAR, check that the mapping files are present in the agent JAR. If the agent logs `Mappings resource not found`, remapping isn't running and Ichor can't find your mixin targets.

**Works on Lunar but breaks on vanilla Forge** — Make sure `remapJar` is what you're dropping into the Forge mods folder, not `shadowJar`. The `shadowJar` output has MCP names and Forge expects SRG names.

**Mixin conflicts with Lunar or other mods** — If you get `Method overwrite conflict` warnings, something is claiming the same method with a higher priority. Switch from `@Overwrite` to `@Inject` or `@Redirect`, or lower the priority below what the conflicting mixin uses.

**`agent library failed to init` on startup** — You used `./gradlew jar` instead of `./gradlew shadowJar`. The plain JAR doesn't have ASM shaded in and the `Premain-Class` manifest entry is missing. Always use `shadowJar`.
