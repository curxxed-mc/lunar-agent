package net.curxxed.dev.agent.transformer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class AgentMixinBootstrap {

    private static boolean agentInitialized = false;

    @Inject(method = "runTick", at = @At("HEAD"), remap = false, require = 0)
    private void agentBootstrap(CallbackInfo ci) {
        if (agentInitialized) return;
        agentInitialized = true;

        String modList = System.getProperty("lunar.agent.bootstrap.mods");
        if (modList == null || modList.isBlank()) {
            System.out.println("[Mod-Agent] No mods to bootstrap.");
            return;
        }

        System.out.println("[Mod-Agent] First tick -- bootstrapping mods...");

        // After Mixin merges this into Minecraft, 'this' is a Minecraft instance.
        // this.getClass().getClassLoader() == Genesis — reliable, no string lookups needed.
        ClassLoader genesisLoader = this.getClass().getClassLoader();

        ClassLoader modLoader = buildModLoader(genesisLoader);

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 1 || parts[0].isBlank()) continue;

            String className   = parts[0];
            String initMethod  = parts.length > 1 ? parts[1] : "";
            String property    = parts.length > 2 ? parts[2] : "";
            boolean isAgentMod = parts.length > 3 && "1".equals(parts[3]);

            try {
                initMod(className, initMethod, property, isAgentMod, modLoader);
            } catch (Throwable t) {
                System.out.println("[Mod-Agent] Failed to init mod: " + className + " -- " + t);
                t.printStackTrace();
            }
        }
    }

    private static ClassLoader buildModLoader(ClassLoader parent) {
        System.out.println("[Mod-Agent] buildModLoader called, jarPaths: "
                + System.getProperty("lunar.agent.bootstrap.jar.paths"));
        String jarPathsProp = System.getProperty("lunar.agent.bootstrap.jar.paths");
        if (jarPathsProp == null || jarPathsProp.isBlank()) {
            System.out.println("[Mod-Agent] No JAR paths registered, mod loading will fail.");
            return parent;
        }

        try {
            String[] paths = jarPathsProp.split("::");
            URL[] urls = new URL[paths.length];
            for (int i = 0; i < paths.length; i++) {
                urls[i] = new File(paths[i]).toURI().toURL();
                System.out.println("[Mod-Agent] Mod loader URL: " + urls[i]);
            }
            return new URLClassLoader(urls, parent);
        } catch (Exception e) {
            System.out.println("[Mod-Agent] Failed to build mod loader: " + e);
            return parent;
        }
    }

    private static void initMod(String className, String initMethod, String property,
                                boolean isAgentMod, ClassLoader modLoader) throws Exception {
        if (!isAgentMod && !property.isBlank() && !Boolean.getBoolean(property)) {
            System.out.println("[Mod-Agent] Skipping " + className + " -- not in injected environment.");
            return;
        }

        String dotName = className.replace('/', '.');
        Class<?> modClass = Class.forName(dotName, true, modLoader);
        System.out.println("[Mod-Agent] Loaded: " + dotName + " via " + modClass.getClassLoader());

        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Object instance = unsafe.allocateInstance(modClass);
        System.out.println("[Mod-Agent] Instantiated: " + dotName);

        if (isAgentMod) {
            try {
                Method m = modClass.getDeclaredMethod("init");
                m.setAccessible(true);
                m.invoke(instance);
                System.out.println("[Mod-Agent] Called init() on @AgentMod: " + dotName);
            } catch (NoSuchMethodException ignored) {
                System.out.println("[Mod-Agent] No init() found -- constructor-only init for " + dotName);
            }
        } else if (!initMethod.isBlank()) {
            Class<?> eventClass = Class.forName(
                    "net.minecraftforge.fml.common.event.FMLInitializationEvent", true, modLoader);
            Method m = modClass.getDeclaredMethod(initMethod, eventClass);
            m.setAccessible(true);
            m.invoke(instance, (Object) null);
            System.out.println("[Mod-Agent] Called init: " + initMethod + " on " + dotName);
        } else {
            System.out.println("[Mod-Agent] No init method -- constructor-only init for " + dotName);
        }
    }
}