package net.curxxed.dev.agent;

import net.curxxed.dev.agent.config.ModEntry;
import net.curxxed.dev.agent.transformer.EventConstructorPatcher;
import net.curxxed.dev.agent.transformer.MixinAnnotationPatcher;
import net.curxxed.dev.agent.transformer.RuntimeRemapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public class AgentBootstrap {

    private static final String MOD_LIST_PROPERTY = "lunar.agent.bootstrap.mods";
    private static final String JAR_PATHS_PROPERTY = "lunar.agent.bootstrap.jar.paths";

    private static final String FORGE_MOD_DESC = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String AGENT_MOD_DESC = "Lnet/curxxed/dev/agent/annotation/AgentMod;";

    public static void premain(String args, Instrumentation inst) throws Exception {
        try {
            Path cacheDir = Paths.get(System.getProperty("user.home"), ".lunarclient", "offline", "multiver", "cache");
            if (Files.exists(cacheDir)) {
                //noinspection resource
                Files.walk(cacheDir)
                        .filter(p -> p.getFileName().toString().equals("bake.zip"))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                                System.out.println("[Mod-Agent] Deleted bake cache: " + p);
                            } catch (IOException e) {
                                System.out.println("[Mod-Agent] Failed to delete bake cache: " + p + " -- " + e);
                            }
                        });
            }
        } catch (Exception e) {
            System.out.println("[Mod-Agent] Error clearing bake cache: " + e);
        }
        System.out.println("[Mod-Agent] premain fired, config: " + args);

        File agentJar = new File(
                AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        System.out.println("[Mod-Agent] Agent JAR located at: " + agentJar);

        if (args == null || args.isBlank()) {
            System.out.println("[Mod-Agent] No config file specified, nothing to inject.");
            return;
        }

        List<ModEntry> mods = parseConfig(args);
        if (mods.isEmpty()) {
            System.out.println("[Mod-Agent] No mods found in config.");
            return;
        }

        for (ModEntry mod : mods) {
            if (mod.property() != null && !mod.property().isBlank()) {
                System.setProperty(mod.property(), "true");
                System.out.println("[Mod-Agent] Set property: " + mod.property());
            }
        }

        String serialized = buildModListProperty(mods);
        if (!serialized.isBlank()) {
            System.setProperty(MOD_LIST_PROPERTY, serialized);
            System.out.println("[Mod-Agent] Mod list property set: " + serialized);
        }

        // store JAR paths so AgentMixinBootstrap can find class bytes at tick time.
        // separator is :: to avoid colliding with path separators on any OS.
        StringBuilder jarPaths = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (f.exists()) {
                if (!jarPaths.isEmpty()) jarPaths.append("::");
                jarPaths.append(f.getAbsolutePath());
            }
        }
        if (!jarPaths.isEmpty()) {
            System.setProperty(JAR_PATHS_PROPERTY, jarPaths.toString());
            System.out.println("[Mod-Agent] JAR paths property set: " + jarPaths);
        }

        Set<String> modClassNames = collectModClassNames(mods);
        System.out.println("[Mod-Agent] Tracking " + modClassNames.size() + " mod classes for transformation.");

        inst.addTransformer(new RuntimeRemapper(modClassNames), true);
        inst.addTransformer(new MixinAnnotationPatcher(modClassNames), true);
        inst.addTransformer(new EventConstructorPatcher(modClassNames), true);
        System.out.println("[Mod-Agent] Transformers registered.");

        Thread mixinRegistrar = new Thread(() -> {
            try {
                System.out.println("[Mod-Agent] Waiting for MixinEnvironment...");
                ClassLoader mixinLoader = null;

                do {
                    Thread.sleep(50);
                    for (Class<?> c : inst.getAllLoadedClasses()) {
                        if (c.getName().equals("org.spongepowered.asm.mixin.MixinEnvironment")
                                && c.getClassLoader() != null) {
                            mixinLoader = c.getClassLoader();
                            break;
                        }
                    }
                } while (mixinLoader == null);

                System.out.println("[Mod-Agent] Found Mixin loader: " + mixinLoader.getClass().getName());

                Method addURL    = mixinLoader.getClass().getMethod("addURL", java.net.URL.class);
                Class<?> mixins  = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader);
                Method addConfig = mixins.getMethod("addConfiguration", String.class);

                // all JARs into Ichor first, then all configs
                addURL.invoke(mixinLoader, agentJar.toURI().toURL());
                System.out.println("[Mod-Agent] Agent JAR added to IchorClassLoader.");

                for (ModEntry mod : mods) {
                    File jarFile = new File(mod.jar());
                    if (!jarFile.exists()) {
                        System.out.println("[Mod-Agent] JAR not found, skipping: " + mod.jar());
                        continue;
                    }
                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    System.out.println("[Mod-Agent] JAR added to IchorClassLoader: " + jarFile.getName());
                }

                addConfig.invoke(null, "mixins.agent.json");
                System.out.println("[Mod-Agent] Agent bootstrap mixin registered.");

                for (ModEntry mod : mods) {
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

    private static String buildModListProperty(List<ModEntry> mods) {
        StringBuilder sb = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : java.util.Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes = is.readAllBytes();
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);

                        String[] modClass    = {null};
                        String[] initMethod  = {null};
                        boolean[] isAgentMod = {false};

                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            private String className;

                            @Override
                            public void visit(int v, int a, String name, String sig, String sup, String[] i) {
                                this.className = name;
                            }

                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (FORGE_MOD_DESC.equals(desc)) {
                                    modClass[0] = className;
                                    isAgentMod[0] = false;
                                } else if (AGENT_MOD_DESC.equals(desc)) {
                                    modClass[0] = className;
                                    isAgentMod[0] = true;
                                }
                                return null;
                            }

                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                                if (!desc.contains("net/minecraftforge/fml/common/event/FMLInitializationEvent")) return null;
                                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                    @Override
                                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean v) {
                                        if (d.contains("EventHandler")) initMethod[0] = name;
                                        return null;
                                    }
                                };
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                        if (modClass[0] != null) {
                            if (!sb.isEmpty()) sb.append(",");
                            sb.append(modClass[0])
                                    .append("|").append(initMethod[0] != null ? initMethod[0] : "")
                                    .append("|").append(mod.property() != null ? mod.property() : "")
                                    .append("|").append(isAgentMod[0] ? "1" : "0");
                            System.out.println("[Mod-Agent] Discovered "
                                    + (isAgentMod[0] ? "@AgentMod" : "@Mod") + ": " + modClass[0]
                                    + " (init: " + initMethod[0] + ")");
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[Mod-Agent] Failed to scan JAR: " + mod.jar() + " -- " + e);
            }
        }
        return sb.toString();
    }

    private static Set<String> collectModClassNames(List<ModEntry> mods) {
        Set<String> names = new HashSet<>();
        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                jf.stream()
                        .filter(e -> e.getName().endsWith(".class"))
                        .map(e -> e.getName().replace(".class", ""))
                        .forEach(names::add);
            } catch (IOException e) {
                System.out.println("[Mod-Agent] Failed to index JAR: " + mod.jar() + " -- " + e);
            }
        }
        return names;
    }

    private static List<ModEntry> parseConfig(String configPath) {
        List<ModEntry> mods = new ArrayList<>();
        try {
            String content = Files.readString(new File(configPath).toPath());
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