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

// This Mixin is responsible for bootstrapping mods on the first tick of the game. If you init mods yourself via targeting startGame t
// his will do nothing provided your mod doesn't have a @Mod annotation if you do you will init the mod twice which
// is not ideal at all.
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class AgentMixinBootstrap {

    private static boolean agentInitialized = false;

    @Inject(method = "startGame", at = @At("RETURN"), remap = false, require = 0)
    private void agentBootstrap(CallbackInfo ci) {
        if (agentInitialized) return;
        agentInitialized = true;

        String modList = System.getProperty("lunar.agent.bootstrap.mods");
        if (modList == null || modList.isBlank()) {
            System.out.println("[Mod-Agent] No mods to bootstrap.");
            return;
        }

        System.out.println("[Mod-Agent] First tick, bootstrapping mods...");

        ClassLoader genesisLoader = this.getClass().getClassLoader();

        ClassLoader modLoader = buildModLoader(genesisLoader);

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 1 || parts[0].isBlank()) continue;

            String className   = parts[0];
            String initMethod  = parts.length > 1 ? parts[1] : "";
            String property    = parts.length > 2 ? parts[2] : "";

            try {
                initMod(className, initMethod, property, modLoader);
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
                                 ClassLoader modLoader) throws Exception {
        if (!property.isBlank() && !Boolean.getBoolean(property)) {
            System.out.println("[Mod-Agent] Skipping " + className + ", not in injected environment.");
            return;
        }

        String dotName = className.replace('/', '.');
        Class<?> modClass = Class.forName(dotName, true, modLoader);
        System.out.println("[Mod-Agent] Loaded: " + dotName + " via " + modClass.getClassLoader());

        Object instance;
        instance = modClass.getDeclaredConstructor().newInstance();
        System.out.println("[Mod-Agent] Instantiated: " + dotName);

       if (!initMethod.isBlank()) {
            Class<?> eventClass = Class.forName(
                    "net.minecraftforge.fml.common.event.FMLInitializationEvent", true, modLoader);
            Method m = modClass.getDeclaredMethod(initMethod, eventClass);
            m.setAccessible(true);
            m.invoke(instance, (Object) null);
            System.out.println("[Mod-Agent] Called init: " + initMethod + " on " + dotName);
        } else {
            System.out.println("[Mod-Agent] No init method, constructor-only init for " + dotName);
        }
    }
}