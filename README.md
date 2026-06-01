#### TODO: ACTUALLY SUPPORT VANILLA AND BADLION THIS SHIT IS MAKING ME TRIGGERED

# lunar-agent

> **This project is not a user-friendly tool. It is a low-level JVM instrumentation framework that requires deep knowledge of Minecraft, Mixin, and Lunar Client internals.**
> Incorrect use can corrupt game files, cause undiagnosable crashes, or permanently break other mods. No support will be provided for misconfigured setups.

---

A Java agent that injects mods into Lunar Client 1.8.9 at runtime, bypassing Lunar's mod discovery entirely. Lunar remains the primary target, but the agent can also bootstrap against obfuscated vanilla-style runtimes such as vanilla and Badlion. Includes automatic obf/SRG/MCP remapping from `combined.csv`, mixin annotation patching, direct `Minecraft.startGame` bootstrapping, and bake cache invalidation.

You drop a mod JAR in. It works. That's the goal.

---

## Important: Forge mod compatibility

Vanilla Lunar Client does not ship any Forge classes. Forge mods will only work when running the **Lunar Client + Forge module**, which provides FML and the Forge event bus. Attempting to inject a Forge mod into plain Lunar Client will fail at class resolution.

---

## How it works

1. **`premain` fires before `main()`** — The JVM calls the agent's `premain` before anything else. It deletes the Lunar bake cache so Mixin re-runs on the next launch, reads your `agent-mods.json`, scans each mod JAR for `@Mod` annotated classes, registers bytecode transformers, and encodes the mod list into a system property for later.

2. **Bytecode transformers patch mod classes on load** — Transformers run on every mod class as it loads:
   - `MinecraftBootstrapTransformer` — injects the agent bootstrap into the runtime `Minecraft.startGame` method, including obfuscated `ave.am()V` on vanilla-style clients.
   - `RuntimeRemapper` — translates class, method, field, and descriptor references from obf, SRG, or MCP into the detected runtime namespace using `mappings/combined.csv`.
   - `MixinAnnotationPatcher` — remaps mixin string selectors, forces `remap = false`, and clamps `@Mixin` priority to 100, safely below Lunar's own mixins.
   - `EventConstructorPatcher` — injects a synthetic no-args constructor into any `Event` subclass that's missing one, so the Forge event bus can instantiate it.

3. **Mixin registrar waits for Ichor when Mixin exists** — On Lunar, a background thread polls every 50ms until `MixinEnvironment` appears in the loaded classes. It then adds the agent JAR and each mod JAR to Ichor's classloader and registers their mixin configs. On vanilla-style clients without Mixin, the direct `startGame` transformer is the bootstrap path.

4. **The shared bootstrap fires mod init** — When `Minecraft.startGame` returns, the agent reads the mod list from the system property, builds a `URLClassLoader`, instantiates each discovered Forge `@Mod` class, and calls the `@EventHandler` init methods. A system-property guard keeps the direct transformer and Lunar mixin fallback from firing twice.

5. **The system property gates Forge mod init** — Before initialising a Forge `@Mod`, the bootstrap checks that the mod's agent property is set. If the mod is loaded normally through FML, the property is never set, the agent does nothing, and Forge's own lifecycle handles init as usual.

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
| `property` | no | System property set to `true` when running via agent. Used to gate Forge mod init so the mod still works on other launchers. |

To inject multiple mods, add more entries to the `mods` array.

### 3. Add the JVM argument to Lunar Client

In the Lunar Client launcher go to **Settings → JVM Arguments** and add:

```
-javaagent:C:\Users\<User>\Documents\lunar-agent\lunar-agent-1.0.0.jar=C:\Users\<User>\Documents\lunar-agent\agent-mods.json
```

Replace both paths with wherever you placed the files.

**Enable Advanced Settings first, otherwise the JVM Arguments field won't be visible.**

---

## What the agent handles automatically

You do not need to do any of the following in your mod source:

- `remap = false` on every mixin annotation
- `priority` clamped to 100 on every `@Mixin`
- obf/SRG/MCP name translation on class, method, field, descriptor, and mixin selector references
- No-args constructor on custom `Event` subclasses
- Bake cache invalidation on every launch
- Mod instantiation and init call on the first game tick



## Troubleshooting

**`[Mod-Agent] JAR not found, skipping`** — The path in `agent-mods.json` is wrong or the file doesn't exist at that location.

**`[Mod-Agent] Error in mixin registrar`** — The mixin config filename doesn't match what's at the root of the JAR, or the JAR wasn't built with the config on its classpath root.

**`[Mod-Agent] No mods to bootstrap`** — No `@Mod` classes were found during premain scanning. Check that your mod has the annotation and that the JAR path in `agent-mods.json` is correct. Also check you're running `shadowJar` and deploying the right output JAR. If ur mod mixins itself into startGame then you can safely ignore this warning

**Mod init fails with NPE on field access** — `Unsafe.allocateInstance` bypasses the constructor. Move all field initialisation into `init()`.

**Forge mod classes not found on vanilla Lunar** — Vanilla Lunar Client does not ship Forge classes. Forge mods only work with the Lunar Client + Forge module. Use standalone mods that don't depend on Forge.

**Works on Lunar but breaks on vanilla Forge** — Make sure `remapJar` is what you're dropping into the Forge mods folder, not `shadowJar`. The `shadowJar` output has MCP names; Forge expects SRG names.

**Injections silently do nothing** — Check that `mappings/combined.csv` is present in the agent JAR. If the agent logs `Mappings not found`, remapping isn't running.

**Mixin conflicts with Lunar** — Switch from `@Overwrite` to `@Inject` or `@Redirect` (although not always).

**`agent library failed to init` on startup** — You used `./gradlew jar` instead of `./gradlew shadowJar`. Always use `shadowJar`.
