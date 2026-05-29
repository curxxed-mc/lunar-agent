package net.curxxed.dev.agent;

import net.curxxed.dev.agent.config.ModEntry;
import net.curxxed.dev.agent.invoke.ModLifecycleInvoker;
import net.curxxed.dev.agent.mappings.MappingRegistry;
import net.curxxed.dev.agent.transformer.AccessorConflictPatcher;
import net.curxxed.dev.agent.transformer.Environment;
import net.curxxed.dev.agent.transformer.EventConstructorPatcher;
import net.curxxed.dev.agent.transformer.MinecraftBootstrapTransformer;
import net.curxxed.dev.agent.transformer.MixinAnnotationPatcher;
import net.curxxed.dev.agent.transformer.RuntimeRemapper;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IMixinService;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.concurrent.atomic.AtomicReference;

public class AgentBootstrap {

    private static final String MOD_LIST_PROPERTY  = "lunar.agent.bootstrap.mods";
    private static final String JAR_PATHS_PROPERTY = "lunar.agent.bootstrap.jar.paths";
    private static final String BOOTSTRAPPED_PROPERTY = "lunar.agent.bootstrap.initialized";
    private static final String FORGE_MOD_DESC     = "Lnet/minecraftforge/fml/common/Mod;";

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
                                AgentLog.log("Deleted bake cache: " + p);
                            } catch (IOException e) {
                                AgentLog.log("Failed to delete bake cache: " + p + " -- " + e);
                            }
                        });
            }
        } catch (Exception e) {
            AgentLog.log("Error clearing bake cache: " + e);
        }

        AgentLog.log("premain fired, config: " + args);

        File agentJar = new File(
                AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        );
        AgentLog.log("Agent JAR located at: " + agentJar);

        if (args == null || args.isEmpty()) {
            AgentLog.log("No config file specified, nothing to inject.");
            return;
        }

        List<ModEntry> mods = parseConfig(args);
        if (mods.isEmpty()) {
            AgentLog.log("No mods found in config.");
            return;
        }

        for (ModEntry mod : mods) {
            if (mod.getProperty() != null && !mod.getProperty().isEmpty()) {
                System.setProperty(mod.getProperty(), "true");
                AgentLog.log("Set property: " + mod.getProperty());
            }
        }

        // Only forge mods (those with @Mod) are added to the mod list — forgeless mods
        // (OptiFine-only etc.) only need their JARs on the classpath and their mixins
        // registered; they don't go through lifecycle initialization.
        String serialized = buildModListProperty(mods);
        if (!serialized.isEmpty()) {
            AgentLog.log("Setting MOD_LIST_PROPERTY = " + serialized);
            System.setProperty(MOD_LIST_PROPERTY, serialized);
            AgentLog.log("Mod list property set: " + serialized);
        }

        StringBuilder jarPaths = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (f.exists()) {
                if (jarPaths.length() > 0) jarPaths.append("::");
                jarPaths.append(f.getAbsolutePath());
            }
        }

        if (jarPaths.length() > 0) {
            System.setProperty(JAR_PATHS_PROPERTY, jarPaths.toString());
            AgentLog.log("JAR paths property set: " + jarPaths);
        }

        Set<String> modClassNames = collectModClassNames(mods);
        AgentLog.log("Tracking " + modClassNames.size() + " mod classes for transformation.");

        // Load combined mappings once and share them with RuntimeRemapper, mixin pre-patching,
        // and the direct Minecraft bootstrap transformer.
        MappingRegistry mappings = loadMappings();
        AtomicReference<Environment> runtimeEnvironment =
                new AtomicReference<>(Environment.detectRuntimeEnvironment());

        // Scan every mod JAR right now at premain time to build the accessor rename map.
        // This must happen before any mod classes are loaded so AccessorConflictPatcher
        // has the full map ready the moment IchorClassLoader asks for the first class.
        Map<String, String> accessorRenames = buildAccessorRenameMap(mods);
        AgentLog.log("Accessor renames planned: " + accessorRenames.size());

        inst.addTransformer(new MinecraftBootstrapTransformer(mappings, runtimeEnvironment), true);
        inst.addTransformer(new MixinAnnotationPatcher(modClassNames, mappings, runtimeEnvironment::get), true);
        inst.addTransformer(new RuntimeRemapper(modClassNames, mappings, runtimeEnvironment::get), true);
        inst.addTransformer(new EventConstructorPatcher(modClassNames), true);
        inst.addTransformer(new AccessorConflictPatcher(modClassNames, accessorRenames), true);
        AgentLog.log("Transformers registered.");

        if (shouldBootstrapMixin(inst, runtimeEnvironment.get())) {
            bootstrapVanillaMixin();
        }

        Thread mixinRegistrar = new Thread(() -> {
            try {
                AgentLog.log("Waiting for MixinEnvironment...");
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

                AgentLog.log("Found Mixin loader: " + mixinLoader.getClass().getName());

                Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addURL.setAccessible(true);

                Class<?> mixins  = Class.forName("org.spongepowered.asm.mixin.Mixins", true, mixinLoader);
                Method addConfig = mixins.getMethod("addConfiguration", String.class);

                Path patchDir = Files.createTempDirectory("agent-mixin-patch-");
                patchDir.toFile().deleteOnExit();

                for (ModEntry mod : mods) {
                    if (mod.getMixin() == null || mod.getMixin().isEmpty()) continue;
                    File jarFile = new File(mod.getJar());
                    if (!jarFile.exists()) continue;

                    try (JarFile jf = new JarFile(jarFile)) {

                        // 1. Patch the mixin JSON: strip the refmap field
                        JarEntry jsonEntry = jf.getJarEntry(mod.getMixin());
                        if (jsonEntry != null) {
                            try (InputStream is = jf.getInputStream(jsonEntry)) {
                                String json = new String(toByteArray(is), StandardCharsets.UTF_8);
                                String patched = stripRefmapField(json);
                                Files.write(patchDir.resolve(mod.getMixin()), patched.getBytes(StandardCharsets.UTF_8));
                                AgentLog.log("Stripped refmap from mixin config: " + mod.getMixin());
                            }
                        } else {
                            AgentLog.log("Mixin JSON not found in JAR: " + mod.getMixin());
                        }

                        // 2. Pre-remap every class listed in the mixin JSON
                        List<String> classPaths = resolveMixinClassPaths(jf, mod.getMixin());
                        for (String classPath : classPaths) {
                            java.util.jar.JarEntry classEntry = jf.getJarEntry(classPath);
                            if (classEntry == null) {
                                AgentLog.log("Mixin class not found in JAR: " + classPath);
                                continue;
                            }
                            try (InputStream is = jf.getInputStream(classEntry)) {
                                byte[] original = toByteArray(is);
                                byte[] processed = prePatchMixinClass(
                                        original, mappings, runtimeEnvironment.get(), modClassNames, accessorRenames, mixinLoader);
                                Path outPath = patchDir.resolve(classPath);
                                Files.createDirectories(outPath.getParent());
                                Files.write(outPath, processed);
                                AgentLog.log("Pre-patched mixin class: " + classPath);
                            }
                        }

                        // 3. ALSO pre-patch every accessor interface that appears in the rename map.
                        //    Accessor interfaces are NOT listed in the mixin JSON "mixins" array —
                        //    they're mixin interfaces, not mixin classes. Mixin reads them via
                        //    getResourceAsStream BEFORE the ClassFileTransformer fires, so without
                        //    this step Mixin generates implementations with the un-renamed method
                        //    names, then our transformer renames the interface declarations afterwards
                        //    — causing AbstractMethodError because interface and implementation diverge.
                        Set<String> alreadyPatched = new HashSet<>(classPaths);
                        for (String renameKey : accessorRenames.keySet()) {
                            // renameKey format: "ownerInternalName\nmethodName\ndescriptor"
                            String owner = renameKey.split("\n")[0];
                            // Only process mod classes (not Minecraft target classes)
                            if (!modClassNames.contains(owner)) continue;
                            String classPath2 = owner + ".class";
                            if (alreadyPatched.contains(classPath2)) continue;
                            alreadyPatched.add(classPath2);

                            java.util.jar.JarEntry classEntry = jf.getJarEntry(classPath2);
                            if (classEntry == null) continue;
                            try (InputStream is = jf.getInputStream(classEntry)) {
                                byte[] original  = toByteArray(is);
                                byte[] processed = prePatchMixinClass(original, mappings, runtimeEnvironment.get(), modClassNames, accessorRenames, mixinLoader);
                                Path outPath = patchDir.resolve(classPath2);
                                Files.createDirectories(outPath.getParent());
                                Files.write(outPath, processed);
                                AgentLog.log("Pre-patched accessor interface: " + classPath2);
                            } catch (Exception e2) {
                                AgentLog.log("Failed to pre-patch accessor: " + classPath2 + " -- " + e2);
                            }
                        }

                    } catch (IOException e) {
                        AgentLog.log("Failed to pre-patch mod: " + mod.getJar() + " -- " + e);
                    }
                }

                // Defensively patch the agent's own bootstrap mixin JSON too
                try (JarFile agentJf = new JarFile(agentJar)) {
                    JarEntry e = agentJf.getJarEntry("mixins.agent.json");
                    if (e != null) {
                        try (InputStream is = agentJf.getInputStream(e)) {
                            String json = new String(toByteArray(is), StandardCharsets.UTF_8);
                            String patched = stripRefmapField(json);
                            Files.write(patchDir.resolve("mixins.agent.json"), patched.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {}

                // patchDir goes in FIRST so IchorClassLoader finds our pre-patched .class
                // and .json files before seeing the originals inside the mod JARs.
                addURL.invoke(mixinLoader, patchDir.toUri().toURL());
                AgentLog.log("Pre-patch dir added first to IchorClassLoader: " + patchDir);

                addURL.invoke(mixinLoader, agentJar.toURI().toURL());
                AgentLog.log("Agent JAR added to IchorClassLoader.");

                for (ModEntry mod : mods) {
                    File jarFile = new File(mod.getJar());
                    if (!jarFile.exists()) {
                        AgentLog.log("JAR not found, skipping: " + mod.getJar());
                        continue;
                    }
                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    AgentLog.log("Mod JAR added to IchorClassLoader: " + jarFile.getName());
                }

                // TODO: This is NOT supposed to fail, but we continue anyways
                try {
                    addConfig.invoke(null, "mixins.agent.json");
                    AgentLog.log("Agent bootstrap mixin registered.");
                } catch (Throwable t) {
                    AgentLog.log("Agent mixin bootstrap failed, continuing: " + t);
                }

                for (ModEntry mod : mods) {
                    if (mod.getMixin() != null && !mod.getMixin().isEmpty()) {
                        addConfig.invoke(null, mod.getMixin());
                        AgentLog.log("Mixin config registered: " + mod.getMixin());
                    }
                }

            } catch (Exception e) {
                AgentLog.log("Error in mixin registrar: " + e);
                e.printStackTrace();
            }
        });
        mixinRegistrar.setDaemon(true);
        mixinRegistrar.start();
    }

    private static byte[] prePatchMixinClass(byte[] bytes,
                                             MappingRegistry mappings,
                                             Environment runtimeEnvironment,
                                             Set<String> modClassNames,
                                             Map<String, String> accessorRenames,
                                             ClassLoader loader) {
        bytes = RuntimeRemapper.remapBytes(
                bytes,
                mappings,
                runtimeEnvironment,
                modClassNames,
                loader
        );

        bytes = injectExplicitAccessorValues(bytes);

        if (!accessorRenames.isEmpty()) {
            bytes = AccessorConflictPatcher.applyRenames(bytes, accessorRenames);
        }

        bytes = MixinAnnotationPatcher.apply(bytes, mappings, runtimeEnvironment);
        return bytes;
    }


    // For every @Accessor or @Invoker method that does NOT already have an explicit
    // value= attribute, injects one by inflecting from the current (pre-rename) method name.
    // This must run BEFORE applyRenames so the inflection still produces the correct field name.
    private static byte[] injectExplicitAccessorValues(byte[] bytes) {
        final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
        final String INVOKER  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

        // Quick pre-scan to avoid a full ASM pass on classes that have no accessors/invokers.
        if (!containsUtf8(bytes, "Accessor") && !containsUtf8(bytes, "Invoker")) return bytes;

        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        AnnotationVisitor av = super.visitAnnotation(desc, visible);
                        if (ACCESSOR.equals(desc)) {
                            String target = inflectAccessor(name);
                            if (target != null) {
                                AgentLog.log("Injecting @Accessor value=\"" + target + "\" for method: " + name);
                                return new ValueInjectingAnnotationVisitor(av, target);
                            }
                        } else if (INVOKER.equals(desc)) {
                            String target = inflectInvoker(name);
                            if (target != null) {
                                AgentLog.log("Injecting @Invoker value=\"" + target + "\" for method: " + name);
                                return new ValueInjectingAnnotationVisitor(av, target);
                            }
                        }
                        return av;
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }

    // Injects value= into an @Accessor or @Invoker annotation only if the mod author
    // didn't already specify one explicitly. If they did, we leave it alone.
    private static class ValueInjectingAnnotationVisitor extends AnnotationVisitor {
        private final String targetName;
        private boolean sawValue = false;

        ValueInjectingAnnotationVisitor(AnnotationVisitor av, String targetName) {
            super(Opcodes.ASM9, av);
            this.targetName = targetName;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name)) sawValue = true;
            super.visit(name, value);
        }

        @Override
        public void visitEnd() {
            if (!sawValue) super.visit("value", targetName);
            super.visitEnd();
        }
    }

    // Inflects the target field name from an @Accessor method name.
    // Strips get/set/is prefix and lowercases the first remaining character.
    // Returns null if the method name doesn't match any known prefix
    // (in which case the mod author must have provided an explicit value= themselves).
    private static String inflectAccessor(String methodName) {
        for (String prefix : new String[]{"get", "set", "is"}) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String s = methodName.substring(prefix.length());
                return Character.toLowerCase(s.charAt(0)) + s.substring(1);
            }
        }
        return null;
    }

    // Inflects the target method name from an @Invoker method name.
    // Strips call/invoke prefix and lowercases the first remaining character.
    private static String inflectInvoker(String methodName) {
        for (String prefix : new String[]{"call", "invoke"}) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String s = methodName.substring(prefix.length());
                return Character.toLowerCase(s.charAt(0)) + s.substring(1);
            }
        }
        return null;
    }

    // Parses the mixin JSON from inside the JAR to get the full list of mixin class resource paths.
    private static List<String> resolveMixinClassPaths(JarFile jf, String mixinJsonName) {
        List<String> result = new ArrayList<>();
        java.util.jar.JarEntry entry = jf.getJarEntry(mixinJsonName);
        if (entry == null) return result;
        try (InputStream is = jf.getInputStream(entry)) {
            String json    = new String(toByteArray(is), StandardCharsets.UTF_8);
            String pkg     = extractJsonString(json, "package");
            String pkgPath = (pkg != null) ? pkg.replace('.', '/') : "";
            for (String arrayKey : new String[]{"mixins", "client", "server"}) {
                for (String name : extractJsonStringArray(json, arrayKey)) {
                    // Class names in mixin JSON arrays may use dots as package separators
                    // (e.g. "entity.MixinEntityPlayerSP") — convert to slashes for JAR paths.
                    String namePath = name.replace('.', '/');
                    result.add((pkgPath.isEmpty() ? "" : pkgPath + "/") + namePath + ".class");
                }
            }
        } catch (IOException e) {
            AgentLog.log("Failed to resolve mixin class paths from: " + mixinJsonName + " -- " + e);
        }
        return result;
    }

    // Scans all @Mixin-annotated classes in every mod JAR for @Accessor/@Invoker methods
    // and builds a rename map: "ownerInternalName\nmethodName\ndescriptor" → "methodName_modPrefix".
    private static Map<String, String> buildAccessorRenameMap(List<ModEntry> mods) {
        Map<String, String> renames = new HashMap<>();
        final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
        final String INVOKER  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
        final String MIXIN    = "Lorg/spongepowered/asm/mixin/Mixin;";

        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;
            String prefix = deriveModPrefix(mod.getMixin());

            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes       = toByteArray(is);
                        String internalName = entry.getName().replace(".class", "");
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
                        boolean[] isMixin = {false};
                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (MIXIN.equals(desc)) isMixin[0] = true;
                                return null;
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                        if (!isMixin[0]) continue;

                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(
                                    int access, String name, String descriptor, String sig, String[] ex) {
                                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                    @Override
                                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean v) {
                                        if (!ACCESSOR.equals(desc) && !INVOKER.equals(desc)) return null;

                                        // Suffix the mod prefix so the get/set/is/call/invoke prefix
                                        // stays at position 0 — Mixin inflects from the start of the
                                        // name and would fail if the prefix came first.
                                        String newName = name + "_" + prefix;

                                        String interfaceKey = internalName + "\n" + name + "\n" + descriptor;
                                        renames.put(interfaceKey, newName);
                                        AgentLog.log("Accessor rename planned: "
                                                + name + " → " + newName + " in " + internalName);

                                        return null;
                                    }
                                };
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                    } catch (Exception ignored) { /* single class scan failure is not fatal */ }
                }
            } catch (IOException e) {
                AgentLog.log("Failed to scan JAR for accessors: " + mod.getJar() + " -- " + e);
            }
        }
        return renames;
    }


    static String deriveModPrefix(String mixinConfig) {
        if (mixinConfig == null || mixinConfig.isEmpty()) return "mod";
        String s = mixinConfig;
        if (s.startsWith("mixins.")) s = s.substring("mixins.".length());
        if (s.endsWith(".json"))     s = s.substring(0, s.length() - ".json".length());
        int dot = s.indexOf('.');
        return (dot > 0 ? s.substring(0, dot) : s).replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // Strips the "refmap": "..." field from a mixin JSON string in all its valid forms.
    private static String stripRefmapField(String json) {
        String r = json.replaceAll(",\\s*\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        r = r.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"\\s*,", "");
        r = r.replaceAll("\"refmap\"\\s*:\\s*\"[^\"]*\"", "");
        return r;
    }

    private static boolean containsUtf8(byte[] bytes, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (bytes[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        premain(args, inst);
    }

    public static void bootstrapLoadedMods(ClassLoader parentLoader) {
        synchronized (AgentBootstrap.class) {
            if (Boolean.getBoolean(BOOTSTRAPPED_PROPERTY)) return;
            System.setProperty(BOOTSTRAPPED_PROPERTY, "true");
        }

        String modList = System.getProperty(MOD_LIST_PROPERTY);
        AgentLog.log("bootstrap property = " + System.getProperty(MOD_LIST_PROPERTY));
        if (modList == null || modList.isEmpty()) {
            AgentLog.log("No mods to bootstrap.");
            return;
        }

        AgentLog.log("Minecraft startGame reached, bootstrapping mods...");

        ClassLoader modLoader = buildModLoader(parentLoader);
        File configDir = deriveConfigDir();

        for (String entry : modList.split(",")) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length < 1 || parts[0].isEmpty()) continue;

            String className = parts[0];
            String property = parts.length > 1 ? parts[1] : "";

            try {
                initMod(className, property, modLoader, configDir);
            } catch (Throwable t) {
                AgentLog.log("Failed to init mod: " + className + " -- " + t);
                t.printStackTrace();
            }
        }
    }

    private static ClassLoader buildModLoader(ClassLoader parent) {
        AgentLog.log("buildModLoader called, jarPaths: "
                + System.getProperty(JAR_PATHS_PROPERTY));
        String jarPathsProp = System.getProperty(JAR_PATHS_PROPERTY);
        if (jarPathsProp == null || jarPathsProp.isEmpty()) {
            AgentLog.log("No JAR paths registered, mod loading will fail.");
            return parent;
        }

        try {
            String[] paths = jarPathsProp.split("::");
            URL[] urls = new URL[paths.length];
            for (int i = 0; i < paths.length; i++) {
                urls[i] = new File(paths[i]).toURI().toURL();
                AgentLog.log("Mod loader URL: " + urls[i]);
            }
            return new URLClassLoader(urls, parent);
        } catch (Exception e) {
            AgentLog.log("Failed to build mod loader: " + e);
            return parent;
        }
    }

    private static File deriveConfigDir() {
        String jarPaths = System.getProperty(JAR_PATHS_PROPERTY);
        if (jarPaths != null && !jarPaths.isEmpty()) {
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
        if (!property.isEmpty() && !Boolean.getBoolean(property)) {
            AgentLog.log("Skipping " + className
                    + ", property guard not set: " + property);
            return;
        }

        String dotName = className.replace('/', '.');
        Class<?> modClass = Class.forName(dotName, true, modLoader);
        AgentLog.log("Loaded: " + dotName + " via " + modClass.getClassLoader());

        Object instance = modClass.getDeclaredConstructor().newInstance();
        AgentLog.log("Instantiated: " + dotName);

        ModLifecycleInvoker.invokeLifecycle(instance, configDir, modLoader);
    }

    static MappingRegistry loadMappings() {
        String resource = "/mappings/combined.csv";
        try (InputStream is = AgentBootstrap.class.getResourceAsStream(resource)) {
            if (is == null) {
                AgentLog.log("Mappings not found: " + resource
                        + " -- runtime remapping will not work!");
                return new MappingRegistry();
            }
            MappingRegistry mappings = MappingRegistry.load(is);
            AgentLog.log("Loaded combined mappings: "
                    + mappings.classCount() + " classes, "
                    + mappings.methodCount() + " methods, "
                    + mappings.fieldCount() + " fields.");
            return mappings;
        } catch (Exception e) {
            AgentLog.log("Failed to load mappings " + resource + ": " + e);
            return new MappingRegistry();
        }
    }

    private static String buildModListProperty(List<ModEntry> mods) {
        StringBuilder sb = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes = toByteArray(is);
                        ClassReader cr = new ClassReader(bytes);
                        String[] modClass = {null};
                        cr.accept(new ClassVisitor(Opcodes.ASM9) {
                            private String className;
                            @Override public void visit(int v, int a, String name, String sig, String sup, String[] i) {
                                this.className = name;
                            }
                            @Override
                            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (FORGE_MOD_DESC.equals(desc)) modClass[0] = className;
                                return null;
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

                        if (modClass[0] != null) {
                            if (sb.length() > 0) sb.append(",");
                            sb.append(modClass[0])
                                    .append("|").append(mod.getProperty() != null ? mod.getProperty() : "");
                            AgentLog.log("Discovered @Mod: " + modClass[0]);
                        }
                    }
                }
            } catch (IOException e) {
                AgentLog.log("Failed to scan JAR: " + mod.getJar() + " -- " + e);
            }
        }
        return sb.toString();
    }

    private static Set<String> collectModClassNames(List<ModEntry> mods) {
        Set<String> names = new HashSet<>();
        for (ModEntry mod : mods) {
            File f = new File(mod.getJar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                jf.stream().filter(e -> e.getName().endsWith(".class"))
                        .map(e -> e.getName().replace(".class", "")).forEach(names::add);
            } catch (IOException e) {
                AgentLog.log("Failed to index JAR: " + mod.getJar() + " -- " + e);
            }
        }
        return names;
    }

    private static List<ModEntry> parseConfig(String configPath) {
        List<ModEntry> mods = new ArrayList<>();
        try {
            String content = new String(Files.readAllBytes(new File(configPath).toPath()), StandardCharsets.UTF_8);
            String[] blocks = content.split("\\{");
            for (int i = 1; i < blocks.length; i++) {
                String block = blocks[i];
                if (!block.contains("jar")) continue;
                String jar      = extractValue(block, "jar");
                String mixin    = extractValue(block, "mixin");
                String property = extractValue(block, "property");
                if (jar != null) mods.add(new ModEntry(jar, mixin, property));
            }
        } catch (IOException e) {
            AgentLog.log("Failed to read config: " + e);
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

    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki == -1) return null;
        int colon = json.indexOf(':', ki + search.length());
        if (colon == -1) return null;
        int start = json.indexOf('"', colon + 1);
        if (start == -1) return null;
        int end = json.indexOf('"', start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\"";
        int ki = json.indexOf(search);
        if (ki == -1) return result;
        int colon = json.indexOf(':', ki + search.length());
        if (colon == -1) return result;
        int arrayStart = json.indexOf('[', colon + 1);
        if (arrayStart == -1) return result;
        int arrayEnd = json.indexOf(']', arrayStart + 1);
        if (arrayEnd == -1) return result;
        for (String token : json.substring(arrayStart + 1, arrayEnd).split(",")) {
            token = token.trim();
            if (token.startsWith("\"") && token.endsWith("\""))
                result.add(token.substring(1, token.length() - 1));
        }
        return result;
    }

    private static byte[] toByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;

        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }

        return out.toByteArray();
    }

    private static boolean shouldBootstrapMixin(Instrumentation inst, Environment env) {
        if (env != Environment.OBF) {
            return false;
        }

        for (Class<?> c : inst.getAllLoadedClasses()) {

            // Existing mixin env means client already bootstrapped it
            if (c.getName().equals("org.spongepowered.asm.mixin.MixinEnvironment")) {
                return false;
            }

            if (c.getName().startsWith("net.minecraftforge.") || c.getName().startsWith("net.minecraft.launchwrapper.")) {
                return false;
            }
        }

        return true;
    }

    private static void bootstrapVanillaMixin() {

        try {
            AgentLog.log("Vanilla environment detected, bootstrapping Sponge Mixin.");

            Thread.currentThread().setContextClassLoader(AgentBootstrap.class.getClassLoader());

            ServiceLoader<IMixinService> loader = ServiceLoader.load(IMixinService.class, AgentBootstrap.class.getClassLoader());

            for (IMixinService service : loader) {
                AgentLog.log("Found mixin service: " + service.getClass().getName());
            }

            MixinBootstrap.init();

            MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();

            env.setSide(MixinEnvironment.Side.CLIENT);

            env.setObfuscationContext("notch");

            AgentLog.log("MixinEnvironment created successfully.");

        } catch (Throwable t) {
            AgentLog.log("Failed vanilla mixin bootstrap: " + t);
            t.printStackTrace();
        }
    }
}
