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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public class AgentBootstrap {

    // the key we shove the mod list into as a system property.
    // why a system property? because it's the only piece of shared state that's
    // actually visible to both the agent classloader AND ichor. everything else
    // either gets blocked by classloader isolation or causes a LinkageError.
    // i tried everything else first. this was like the fifth attempt.
    private static final String MOD_LIST_PROPERTY = "lunar.agent.bootstrap.mods";

    public static void premain(String args, Instrumentation inst) throws Exception {
        System.out.println("[Mod-Agent] premain fired, config: " + args);

        // we need the agent JAR path so the mixin registrar can addURL it into Ichor later.
        // we do NOT appendToBootstrapClassLoaderSearch it anymore because that caused a
        // LinkageError when bootstrap tried to load our relocated ASM classes that the app
        // classloader already had. one loader, one copy, no conflicts. learned this the hard way.
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

        // scan the mod JARs now, before anything starts loading, and serialize the result
        // into a system property so AgentMixinBootstrap can read it from inside Ichor.
        // format: "className|initMethod|property,className|initMethod|property,..."
        // this is a terrible serialization format. but i'm not adding a JSON parser to the agent.
        String serialized = buildModListProperty(mods);
        if (!serialized.isBlank()) {
            System.setProperty(MOD_LIST_PROPERTY, serialized);
            System.out.println("[Mod-Agent] Mod list property set: " + serialized);
        }

        // index every class name from every mod JAR so the transformers know what's ours.
        // we don't want to accidentally remap or patch Lunar's own classes.
        // that would be bad. very bad. don't do that.
        Set<String> modClassNames = collectModClassNames(mods);
        System.out.println("[Mod-Agent] Tracking " + modClassNames.size() + " mod classes for transformation.");

        // transformer registration order matters a lot here:
        // 1. SRG->MCP remapping first, so downstream transformers see MCP names
        // 2. mixin annotation patching (remap=false, priority clamping)
        // 3. event constructor injection
        // if you change this order and things break, put it back.
        inst.addTransformer(new RuntimeRemapper(modClassNames), true);
        inst.addTransformer(new MixinAnnotationPatcher(modClassNames), true);
        inst.addTransformer(new EventConstructorPatcher(modClassNames), true);
        System.out.println("[Mod-Agent] Transformers registered.");

        // start a background thread that waits for Ichor to wake up, then shoves
        // everything into it. we can't do this synchronously because Ichor doesn't
        // exist yet when premain fires. so we poll. like animals.
        Thread mixinRegistrar = new Thread(() -> {
            try {
                System.out.println("[Mod-Agent] Waiting for MixinEnvironment...");
                ClassLoader mixinLoader = null;

                // no lifecycle hook to tap into, so we brute force it.
                // scan every loaded class every 50ms until MixinEnvironment shows up.
                // this scans the entire loaded class list on every iteration..
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

                // addURL and addConfiguration aren't on any public interface we can reference,
                // so reflection it is. at least it's only done once.
                Method addURL = mixinLoader.getClass().getMethod("addURL", java.net.URL.class);
                Class<?> mixinsClass = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader);
                Method addConfig = mixinsClass.getMethod("addConfiguration", String.class);

                // the agent JAR goes to Ichor so it can load AgentMixinBootstrap.
                // NOT to bootstrap, that caused the LinkageError. just Ichor. only Ichor.
                addURL.invoke(mixinLoader, agentJar.toURI().toURL());
                System.out.println("[Mod-Agent] Agent JAR added to IchorClassLoader.");

                // register our own bootstrap mixin before any mod mixins.
                // ordering probably doesn't matter here but i'm not gambling on it.
                addConfig.invoke(null, "mixins.agent.json");
                System.out.println("[Mod-Agent] Agent bootstrap mixin registered.");

                for (ModEntry mod : mods) {
                    File jarFile = new File(mod.jar());
                    if (!jarFile.exists()) continue;

                    // mod JARs go to Ichor only. NOT bootstrap. we learned this lesson.
                    // one classloader, one Class object per class, no double init.
                    // the bootstrap path caused Ichor to load a second copy of every mod class
                    // independently because it doesn't do parent-first delegation. never again.
                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    System.out.println("[Mod-Agent] JAR added to IchorClassLoader: " + jarFile.getName());

                    if (mod.mixin() != null && !mod.mixin().isBlank()) {
                        // if nothing happens and you don't know why: remap = false.
                        // it is ALWAYS remap = false. the patcher handles it now, but the
                        // comment stays as a reminder of the pain that led us here.
                        addConfig.invoke(null, mod.mixin());
                        System.out.println("[Mod-Agent] Mixin config registered: " + mod.mixin());
                    }
                }

            } catch (Exception e) {
                // if you're seeing this, something went wrong with Ichor's internals.
                // good luck. the class names are 29 characters of RCOIH garbage and
                // nothing is documented. you're on your own.
                System.out.println("[Mod-Agent] Error in mixin registrar: " + e);
                e.printStackTrace();
            }
        });
        mixinRegistrar.setDaemon(true); // daemon so it doesn't keep the JVM alive if everything else dies
        mixinRegistrar.start();
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        premain(args, inst);
    }

    // walks every mod JAR looking for @Mod-annotated classes and their @EventHandler init methods,
    // then encodes the result as a flat string for AgentMixinBootstrap to deserialize.
    // doing this at premain time means all the scanning happens before any class loading,
    // which is exactly when we want it.
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

                        String[] modClass   = {null};
                        String[] initMethod = {null};

                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            private String className;

                            @Override
                            public void visit(int v, int a, String name, String sig, String sup, String[] i) {
                                this.className = name;
                            }

                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if ("Lnet/minecraftforge/fml/common/Mod;".equals(desc)) modClass[0] = className;
                                return null;
                            }

                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                                // looking for the method that takes FMLInitializationEvent and is annotated @EventHandler
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
                                    .append("|").append(mod.property() != null ? mod.property() : "");
                            System.out.println("[Mod-Agent] Discovered @Mod: " + modClass[0]
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

    // collects class names in internal form (slashes not dots) because that's what
    // ClassFileTransformer.transform() receives as its className argument.
    // if you change this to dots the transformers will silently skip everything.
    private static Set<String> collectModClassNames(List<ModEntry> mods) {
        Set<String> names = new HashSet<>();
        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                jf.stream()
                        .filter(e -> e.getName().endsWith(".class"))
                        .map(e -> e.getName().replace(".class", "")) // slashes stay. intentional.
                        .forEach(names::add);
            } catch (IOException e) {
                System.out.println("[Mod-Agent] Failed to index JAR: " + mod.jar() + " -- " + e);
            }
        }
        return names;
    }

    // hand-rolled JSON parser because I refuse to shade Gson into the agent just for config.
    // it handles the format we produce. don't throw anything weird at it.
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