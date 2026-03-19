package net.curxxed.dev.agent.transformer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// we target Minecraft by string because we don't have it on the compile classpath.
// intellij may say "Cannot resolve method 'runTick' in target class but
// it exists. it will exist. it has always existed. runTick is fine.
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class AgentMixinBootstrap {

    private static boolean agentInitialized = false;

    @Inject(method = "runTick", at = @At("HEAD"), remap = false, require = 0)
    private void agentBootstrap(CallbackInfo ci) {
        if (agentInitialized) return;
        agentInitialized = true;

        // read the mod list that AgentBootstrap.premain encoded into a system property.
        // format: "className|initMethod|property,className|initMethod|property,..."
        // system properties are the only shared state that survives classloader isolation,
        // which is why we're doing this instead of something reasonable.
        String modList = System.getProperty("lunar.agent.bootstrap.mods");
        if (modList == null || modList.isBlank()) {
            System.out.println("[Mod-Agent] No mods to bootstrap.");
            return;
        }

        System.out.println("[Mod-Agent] First tick -- bootstrapping mods...");

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1); // -1 keeps trailing empty strings, don't remove it
            if (parts.length < 1 || parts[0].isBlank()) continue;

            String className  = parts[0];
            String initMethod = parts.length > 1 ? parts[1] : "";
            String property   = parts.length > 2 ? parts[2] : "";

            try {
                initMod(className, initMethod, property);
            } catch (Throwable t) {
                // at least you'll see it now. previous iterations swallowed this silently
                // and we spent a very long time staring at logs wondering why nothing happened.
                System.out.println("[Mod-Agent] Failed to init mod: " + className + " -- " + t);
                t.printStackTrace();
            }
        }
    }

    private static void initMod(String className, String initMethod, String property) throws Exception {
        // if the property isn't set we're not running as an injected mod.
        // FML's own lifecycle will handle init, so we do nothing and get out of the way.
        if (!property.isBlank() && !Boolean.getBoolean(property)) {
            System.out.println("[Mod-Agent] Skipping " + className + " -- not in injected environment.");
            return;
        }

        // context classloader at tick time is Ichor, which is where the mod class actually lives.
        // don't use Class.forName(String) here, it'll use the wrong loader and fail quietly.
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Class<?> modClass = Class.forName(className.replace('/', '.'), true, cl);

        // allocateInstance bypasses the constructor entirely.
        // this means we don't need a no-args constructor on the @Mod class, which mod authors
        // never write because FML normally handles instantiation for them.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Object instance = unsafe.allocateInstance(modClass);
        System.out.println("[Mod-Agent] Instantiated: " + className);

        if (!initMethod.isBlank()) {
            // FMLInitializationEvent also lives in Ichor, load it through the same classloader.
            Class<?> eventClass = Class.forName(
                    "net.minecraftforge.fml.common.event.FMLInitializationEvent", true, cl);
            Method m = modClass.getDeclaredMethod(initMethod, eventClass);
            m.setAccessible(true);
            // passing null for the event because FML never fires it and we don't have one.
            // if your init method crashes on a null event: add a null check. it is one line.
            m.invoke(instance, (Object) null);
            System.out.println("[Mod-Agent] Called init: " + initMethod + " on " + className);
        } else {
            // no @EventHandler init found, constructor-only init.
            // allocateInstance already handled it. we're done.
            System.out.println("[Mod-Agent] No init method -- constructor-only init for " + className);
        }
    }
}