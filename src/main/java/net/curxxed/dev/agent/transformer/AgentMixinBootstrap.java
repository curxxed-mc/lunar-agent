package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.invoke.ModLifecycleInvoker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

// Bootstraps forge mods on the first tick of the game by invoking their FML lifecycle
// methods in order (preInit → init → postInit) via ModLifecycleInvoker.
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
        File configDir = deriveConfigDir();

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 1 || parts[0].isBlank()) continue;

            String className = parts[0];
            String property  = parts.length > 1 ? parts[1] : "";

            try {
                initMod(className, property, modLoader, configDir);
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

    private static File deriveConfigDir() {
        String jarPaths = System.getProperty("lunar.agent.bootstrap.jar.paths");
        if (jarPaths != null && !jarPaths.isBlank()) {
            File parent = new File(jarPaths.split("::")[0]).getParentFile();
            if (parent != null) {
                File cfg = new File(parent, "config");
                //noinspection ResultOfMethodCallIgnored
                cfg.mkdirs();
                return cfg;
            }
        }
        File fallback = new File(System.getProperty("user.home"),
                ".lunarclient" + File.separator + "offline"
                        + File.separator + "multiver" + File.separator + "config");
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    private static void initMod(String className, String property,
                                ClassLoader modLoader, File configDir) throws Exception {
        if (!property.isBlank() && !Boolean.getBoolean(property)) {
            System.out.println("[Mod-Agent] Skipping " + className + ", property guard not set: " + property);
            return;
        }

        String dotName = className.replace('/', '.');
        Class<?> modClass = Class.forName(dotName, true, modLoader);
        System.out.println("[Mod-Agent] Loaded: " + dotName + " via " + modClass.getClassLoader());

        Object instance = modClass.getDeclaredConstructor().newInstance();
        System.out.println("[Mod-Agent] Instantiated: " + dotName);

        ModLifecycleInvoker.invokeLifecycle(instance, configDir, modLoader);
    }
}