package net.curxxed.dev.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public class AgentBootstrap {

    record ModEntry(String jar, String mixin, String property) {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        System.out.println("[Mod-Agent] premain fired, config: " + args);

        if (args == null || args.isBlank()) {
            System.out.println("[Mod-Agent] No config file specified, nothing to inject.");
            return;
        }

        List<ModEntry> mods = parseConfig(args);
        if (mods.isEmpty()) {
            System.out.println("[Mod-Agent] No mods found in config.");
            return;
        }

        // Set all properties immediately in premain so mixins can gate on them
        for (ModEntry mod : mods) {
            if (mod.property() != null && !mod.property().isBlank()) {
                System.setProperty(mod.property(), "true");
                System.out.println("[Mod-Agent] Set property: " + mod.property());
            }
        }

        // Add all jars to bootstrap classloader immediately
        for (ModEntry mod : mods) {
            File jarFile = new File(mod.jar());
            if (!jarFile.exists()) {
                System.out.println("[Mod-Agent] JAR not found, skipping: " + mod.jar());
                continue;
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(jarFile));
            System.out.println("[Mod-Agent] JAR added to bootstrap: " + jarFile.getName());
        }

        // Register mixin configs once Ichor classloader is ready
        Thread mixinRegistrar = new Thread(() -> {
            try {
                System.out.println("[Mod-Agent] Waiting for MixinEnvironment...");
                ClassLoader mixinLoader = null;

                while (true) {
                    Thread.sleep(50);
                    for (Class<?> c : inst.getAllLoadedClasses()) {
                        if (c.getName().equals("org.spongepowered.asm.mixin.MixinEnvironment")
                                && c.getClassLoader() != null) {
                            mixinLoader = c.getClassLoader();
                            break;
                        }
                    }
                    if (mixinLoader != null) break;
                }

                System.out.println("[Mod-Agent] Found Mixin loader: " + mixinLoader.getClass().getName());

                Method addURL = mixinLoader.getClass().getMethod("addURL", java.net.URL.class);
                Class<?> mixinsClass = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader);
                Method addConfig = mixinsClass.getMethod("addConfiguration", String.class);

                for (ModEntry mod : mods) {
                    File jarFile = new File(mod.jar());
                    if (!jarFile.exists()) continue;

                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    System.out.println("[Mod-Agent] JAR added to IchorClassLoader: " + jarFile.getName());

                    if (mod.mixin() != null && !mod.mixin().isBlank()) {
                        addConfig.invoke(null, mod.mixin());
                        System.out.println("[Mod-Agent] Mixin config registered: " + mod.mixin());
                    }
                }

            } catch (Exception e) {
                System.out.println("[Mod-Agent] Error in mixin registrar: " + e);
                e.printStackTrace();
            }
        });
        mixinRegistrar.setDaemon(true);
        mixinRegistrar.start();
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        premain(args, inst);
    }

    private static List<ModEntry> parseConfig(String configPath) {
        List<ModEntry> mods = new ArrayList<>();
        try {
            String content = Files.readString(new File(configPath).toPath());
            // Parse each mod block between { } after "mods": [
            String[] blocks = content.split("\\{");
            for (int i = 1; i < blocks.length; i++) {
                String block = blocks[i];
                if (!block.contains("jar")) continue;
                String jar      = extractValue(block, "jar");
                String mixin    = extractValue(block, "mixin");
                String property = extractValue(block, "property");
                if (jar != null) {
                    mods.add(new ModEntry(jar, mixin, property));
                }
            }
        } catch (IOException e) {
            System.out.println("[Mod-Agent] Failed to read config: " + e);
        }
        return mods;
    }

    private static String extractValue(String block, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = block.indexOf(search);
        if (keyIdx == -1) return null;
        int colon = block.indexOf(":", keyIdx);
        if (colon == -1) return null;
        int start = block.indexOf("\"", colon + 1);
        if (start == -1) return null;
        int end = block.indexOf("\"", start + 1);
        if (end == -1) return null;
        return block.substring(start + 1, end);
    }
}