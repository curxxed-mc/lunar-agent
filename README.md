# lunar-agent

> ## ⚠️ WARNING — ADVANCED DEVELOPERS ONLY
>
> **This project is not a user-friendly tool. It is a low-level JVM instrumentation framework that requires deep knowledge of:**
> - Java agents and the `java.lang.instrument` API
> - Mixin (SpongePowered ASM) and bytecode transformation
> - Forge mod lifecycle and event systems
> - Classloader hierarchies (bootstrap, Ichor, FML)
> - Minecraft internals and obfuscated mappings
>
> **If you do not know what any of the above means, this project is not for you.**  
> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

---

A Java agent that injects Forge mods into Lunar Client 1.8.9 at runtime — bypassing Lunar's mod discovery entirely. Supports multiple mods, mixin registration, and graceful fallback when running without the agent.

---

## How it works

Lunar Client does not support dropping arbitrary Forge mods into its mods folder the normal way. This agent works around that by hooking into the JVM before Lunar even starts:

1. **`premain` fires before `main()`** — The JVM calls the agent's `premain` before anything else. It reads your `agent-mods.json`, sets a system property per mod (e.g. `your-mod.agent.injected=true`), and adds all mod JARs to the bootstrap classloader so their classes are visible everywhere.

2. **Mixin registrar waits for Ichor** — Lunar uses its own classloader called Ichor to load and transform classes. A background thread polls every 50ms until `MixinEnvironment` appears in the loaded classes, meaning Ichor is alive. It then adds the mod JARs to Ichor and registers each mixin config.

3. **Mixins weave bytecode at class load time** — Once a config is registered, SpongeMixin injects your `@Inject`/`@Redirect` bytecode into target classes the moment they are first loaded. Classes already loaded before registration cannot be retroactively transformed.

4. **A mixin bootstraps the mod's init** — Since Forge never discovers the `@Mod` class, its lifecycle events never fire. A mixin on a confirmed-working injection point (e.g. `Minecraft.runTick`) manually calls the mod's constructor and `init(null)` on the first game tick, then never again.

5. **The system property gates the init** — The mixin checks `Boolean.getBoolean("your-mod.agent.injected")` before doing anything. If the mod is loaded as a normal Forge mod instead (e.g. via the mods folder on a vanilla launcher), the property is never set, the mixin does nothing, and Forge's own lifecycle handles init as usual.

---

## Setup

### 1. Build the agent JAR

The agent JAR must be built with its `MANIFEST.MF` declaring:
```
Premain-Class: net.curxxed.dev.agent.AgentBootstrap
Agent-Class: net.curxxed.dev.agent.AgentBootstrap
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

### 2. Create your config file

Create `agent-mods.json` anywhere on your system. Each entry in `mods` is one mod to inject:

```json
{
  "mods": [
    {
      "jar": "C:\\Users\\<User>\\Documents\\lunar-agent\\example-mod.jar",
      "mixin": "mixins.example-mod.json",
      "property": "example-mod.agent.injected"
    }
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `jar` | ✅ | Absolute path to the mod JAR |
| `mixin` | ❌ | Mixin config filename at the root of the JAR. Omit if the mod has no mixins |
| `property` | ❌ | System property set to `true` when injected via agent. Used to gate init so the mod still works as a normal Forge mod on other launchers |

To inject multiple mods, add more entries to the `mods` array.

### 3. Add the JVM argument to Lunar Client

In the Lunar Client launcher go to **Settings → Java Options** and add:

```
-javaagent:C:\Users\<User>\Documents\lunar-agent\agent.jar=C:\Users\<User>\Documents\lunar-agent\agent-mods.json
```

Replace both paths with wherever you placed `agent.jar` and `agent-mods.json`.

---

## Writing a compatible mod

For your mod to work correctly whether loaded via the agent or as a normal Forge mod, its bootstrap mixin should gate on the system property:

```java
@Mixin(value = Minecraft.class, remap = false)
public class MixinMinecraft {

    private static boolean initialized = false;

    @Inject(method = "runTick", at = @At("HEAD"), remap = false)
    private void onFirstTick(CallbackInfo ci) {
        // Guard must be set BEFORE the property check — never fires twice
        if (initialized) return;
        initialized = true;

        // Only manually init if loaded via agent.
        // If loaded as a normal Forge mod, the @Mod lifecycle handles this.
        if (!Boolean.getBoolean("your-mod.agent.injected")) return;

        try {
            YourMod mod = new YourMod();
            mod.init(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

> **Note:** `runTick` is used here because it is a proven injection point that is always hit early in the game loop.
---

## Project structure

```
lunar-agent/
├── agent.jar            # The agent — pointed to by -javaagent
├── agent-mods.json      # Your mod list — pointed to by the agent argument
└── your-mod.jar         # Your mod JAR(s)
```

---

## Troubleshooting

**`[Mod-Agent] JAR not found, skipping`** — The path in `agent-mods.json` is wrong or the file doesn't exist at that location.

**`[Mod-Agent] Error in mixin registrar`** — The mixin config name doesn't match the filename at the root of the JAR, or the JAR wasn't built with the config on its classpath root.

**Mod classes load but fields are null** — The mod's `init()` was never called. Verify the injection point is being hit by adding a `System.out.println` before the property check. If nothing prints, the injection was silently skipped — find a different target that is confirmed to fire.

**Works on Lunar but breaks on vanilla Forge** — Ensure the system property gate is in place and the `@Mod` lifecycle events are correctly wired for the non-agent path.

**Mixin conflicts with Lunar or other mods** — Lunar's Ichor applies its own mixins first. If you get `Method overwrite conflict` warnings, your `@Overwrite` is targeting a method already claimed by Lunar. Switch to `@Inject` or `@Redirect` instead.