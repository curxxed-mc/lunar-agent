package net.curxxed.dev.agent;

import net.curxxed.dev.agent.config.ModEntry;
import net.curxxed.dev.agent.transformer.AccessorConflictPatcher;
import net.curxxed.dev.agent.transformer.EventConstructorPatcher;
import net.curxxed.dev.agent.transformer.MixinAnnotationPatcher;
import net.curxxed.dev.agent.transformer.RuntimeRemapper;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;

public class AgentBootstrap {

    private static final String MOD_LIST_PROPERTY  = "lunar.agent.bootstrap.mods";
    private static final String JAR_PATHS_PROPERTY = "lunar.agent.bootstrap.jar.paths";
    private static final String FORGE_MOD_DESC     = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String AGENT_MOD_DESC     = "Lnet/curxxed/dev/agent/annotation/AgentMod;";

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

        // Load mappings once and share them with both RuntimeRemapper (which handles
        // normal class loads) and the pre-remap step below (which handles mixin classes
        // that Mixin reads via getResourceAsStream and which never go through defineClass).
        Map<String, String> methodMap = new HashMap<>();
        Map<String, String> fieldMap  = new HashMap<>();
        loadMappings(methodMap, fieldMap);

        // Scan every mod JAR right now at premain time to build the accessor rename map.
        // This must happen before any mod classes are loaded so AccessorConflictPatcher
        // has the full map ready the moment IchorClassLoader asks for the first class.
        Map<String, String> accessorRenames = buildAccessorRenameMap(mods);
        System.out.println("[Mod-Agent] Accessor renames planned: " + accessorRenames.size());

        inst.addTransformer(new RuntimeRemapper(modClassNames, methodMap, fieldMap), true);
        inst.addTransformer(new MixinAnnotationPatcher(modClassNames), true);
        inst.addTransformer(new EventConstructorPatcher(modClassNames), true);
        inst.addTransformer(new AccessorConflictPatcher(modClassNames, accessorRenames), true);
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

                // ===== MIXIN CLASS PRE-PATCH =====
                //
                // WHY this is needed (not just stripping the refmap field from JSON):
                //
                // Mixin reads mixin class bytes via ClassLoader.getResourceAsStream("Foo.class"),
                // NOT through ClassLoader.defineClass. This means ClassFileTransformers registered
                // with Instrumentation.addTransformer never fire for mixin class bytes. So:
                //   - RuntimeRemapper never sees MixinMinecraft.class
                //   - @Shadow field field_71428_T stays as the SRG name in the bytecode
                //   - Mixin looks for a field named field_71428_T in Lunar's Minecraft
                //   - Lunar's Minecraft only has MCP names → field not found → crash
                //
                // We fix this by reading each mixin class out of the JAR ourselves, applying
                // all the same transforms (SRG→MCP remap + accessor renames), and writing the
                // results into a temp directory that is added to IchorClassLoader BEFORE the
                // mod JAR. When Mixin calls getResourceAsStream it finds our pre-patched bytes.
                //
                // We ALSO strip the "refmap" field from the mixin JSON while we're at it.
                // Ichor's classloader returns null for any resource whose name contains "refmap",
                // so a mixin config that declares a refmap puts Mixin into a broken half-state
                // where it expects a refmap to exist but can't load it.

                Path patchDir = Files.createTempDirectory("agent-mixin-patch-");
                patchDir.toFile().deleteOnExit();

                for (ModEntry mod : mods) {
                    if (mod.mixin() == null || mod.mixin().isBlank()) continue;
                    File jarFile = new File(mod.jar());
                    if (!jarFile.exists()) continue;

                    try (JarFile jf = new JarFile(jarFile)) {

                        // 1. Patch the mixin JSON: strip the refmap field
                        java.util.jar.JarEntry jsonEntry = jf.getJarEntry(mod.mixin());
                        if (jsonEntry != null) {
                            try (InputStream is = jf.getInputStream(jsonEntry)) {
                                String json    = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                                String patched = stripRefmapField(json);
                                Files.writeString(patchDir.resolve(mod.mixin()), patched, StandardCharsets.UTF_8);
                                System.out.println("[Mod-Agent] Stripped refmap from mixin config: " + mod.mixin());
                            }
                        } else {
                            System.out.println("[Mod-Agent] Mixin JSON not found in JAR: " + mod.mixin());
                        }

                        // 2. Pre-remap every class listed in the mixin JSON
                        List<String> classPaths = resolveMixinClassPaths(jf, mod.mixin());
                        for (String classPath : classPaths) {
                            java.util.jar.JarEntry classEntry = jf.getJarEntry(classPath);
                            if (classEntry == null) {
                                System.out.println("[Mod-Agent] Mixin class not found in JAR: " + classPath);
                                continue;
                            }
                            try (InputStream is = jf.getInputStream(classEntry)) {
                                byte[] original  = is.readAllBytes();
                                byte[] processed = prePatchMixinClass(original, methodMap, fieldMap, accessorRenames);
                                Path   outPath   = patchDir.resolve(classPath);
                                Files.createDirectories(outPath.getParent());
                                Files.write(outPath, processed);
                                System.out.println("[Mod-Agent] Pre-patched mixin class: " + classPath);
                            }
                        }

                    } catch (IOException e) {
                        System.out.println("[Mod-Agent] Failed to pre-patch mod: " + mod.jar() + " -- " + e);
                    }
                }

                // Defensively patch the agent's own bootstrap mixin JSON too
                try (JarFile agentJf = new JarFile(agentJar)) {
                    java.util.jar.JarEntry e = agentJf.getJarEntry("mixins.agent.json");
                    if (e != null) {
                        try (InputStream is = agentJf.getInputStream(e)) {
                            String json    = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                            String patched = stripRefmapField(json);
                            Files.writeString(patchDir.resolve("mixins.agent.json"), patched, StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception ignored) {}

                // patchDir goes in FIRST so IchorClassLoader finds our pre-patched .class
                // and .json files before seeing the originals inside the mod JARs.
                addURL.invoke(mixinLoader, patchDir.toUri().toURL());
                System.out.println("[Mod-Agent] Pre-patch dir added first to IchorClassLoader: " + patchDir);

                addURL.invoke(mixinLoader, agentJar.toURI().toURL());
                System.out.println("[Mod-Agent] Agent JAR added to IchorClassLoader.");

                for (ModEntry mod : mods) {
                    File jarFile = new File(mod.jar());
                    if (!jarFile.exists()) {
                        System.out.println("[Mod-Agent] JAR not found, skipping: " + mod.jar());
                        continue;
                    }
                    addURL.invoke(mixinLoader, jarFile.toURI().toURL());
                    System.out.println("[Mod-Agent] Mod JAR added to IchorClassLoader: " + jarFile.getName());
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

    // Applies SRG→MCP rename + accessor renames to a raw mixin class byte array.
    // Both transforms must happen here because Mixin reads these bytes directly via
    // getResourceAsStream — they never go through ClassFileTransformer.
    private static byte[] prePatchMixinClass(byte[] bytes,
                                             Map<String, String> methodMap,
                                             Map<String, String> fieldMap,
                                             Map<String, String> accessorRenames) {
        if (containsSrgNames(bytes)) {
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cr.accept(new ClassRemapper(cw, new Remapper() {
                @Override public String mapMethodName(String owner, String name, String desc) {
                    String m = methodMap.get(name); return m != null ? m : name;
                }
                @Override public String mapFieldName(String owner, String name, String desc) {
                    String f = fieldMap.get(name); return f != null ? f : name;
                }
            }), 0);
            bytes = cw.toByteArray();
        }

        if (!accessorRenames.isEmpty()) {
            bytes = AccessorConflictPatcher.applyRenames(bytes, accessorRenames);
        }

        return bytes;
    }

    // Parses the mixin JSON from inside the JAR to get the full list of mixin class resource paths.
    // Handles "package" + "mixins", "client", "server" arrays.
    private static List<String> resolveMixinClassPaths(JarFile jf, String mixinJsonName) {
        List<String> result = new ArrayList<>();
        java.util.jar.JarEntry entry = jf.getJarEntry(mixinJsonName);
        if (entry == null) return result;
        try (InputStream is = jf.getInputStream(entry)) {
            String json    = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String pkg     = extractJsonString(json, "package");
            String pkgPath = (pkg != null) ? pkg.replace('.', '/') : "";
            for (String arrayKey : new String[]{"mixins", "client", "server"}) {
                for (String name : extractJsonStringArray(json, arrayKey)) {
                    result.add((pkgPath.isEmpty() ? "" : pkgPath + "/") + name + ".class");
                }
            }
        } catch (IOException e) {
            System.out.println("[Mod-Agent] Failed to resolve mixin class paths from: " + mixinJsonName + " -- " + e);
        }
        return result;
    }

    // Scans all @Mixin-annotated classes in every mod JAR for @Accessor/@Invoker methods
    // and builds a rename map: "ownerInternalName\nmethodName\ndescriptor" → "modPrefix_methodName".
    // Called at premain time so the full map exists before the first class is loaded.
    private static Map<String, String> buildAccessorRenameMap(List<ModEntry> mods) {
        Map<String, String> renames = new HashMap<>();
        final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
        final String INVOKER  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
        final String MIXIN    = "Lorg/spongepowered/asm/mixin/Mixin;";

        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (!f.exists()) continue;
            String prefix = deriveModPrefix(mod.mixin());

            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes    = is.readAllBytes();
                        String internalName = entry.getName().replace(".class", "");
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);

                        boolean[] isMixin = {false};
                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean v) {
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
                                        if (ACCESSOR.equals(desc) || INVOKER.equals(desc)) {
                                            String key     = internalName + "\n" + name + "\n" + descriptor;
                                            String newName = prefix + "_" + name;
                                            renames.put(key, newName);
                                            System.out.println("[Mod-Agent] Accessor rename planned: "
                                                    + name + " → " + newName + " in " + internalName);
                                        }
                                        return null;
                                    }
                                };
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);

                    } catch (Exception ignored) { /* single class scan failure is not fatal */ }
                }
            } catch (IOException e) {
                System.out.println("[Mod-Agent] Failed to scan JAR for accessors: " + mod.jar() + " -- " + e);
            }
        }
        return renames;
    }

    // "mod.meowtils.json" → "mod"
    static String deriveModPrefix(String mixinConfig) {
        if (mixinConfig == null || mixinConfig.isBlank()) return "mod";
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

    private static boolean containsSrgNames(byte[] bytes) {
        for (int i = 0; i < bytes.length - 6; i++) {
            if (bytes[i] == 'f') {
                if (bytes[i+1]=='u' && bytes[i+2]=='n' && bytes[i+3]=='c' && bytes[i+4]=='_') return true;
                if (bytes[i+1]=='i' && bytes[i+2]=='e' && bytes[i+3]=='l' && bytes[i+4]=='d' && bytes[i+5]=='_') return true;
            }
        }
        return false;
    }

    // Minimal JSON helpers — no external dependency, no full parser needed
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

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        premain(args, inst);
    }

    // Shared mapping loader — called here for the pre-remap step and by RuntimeRemapper.
    // RuntimeRemapper now accepts pre-loaded maps via constructor rather than loading its own.
    static void loadMappings(Map<String, String> methodMap, Map<String, String> fieldMap) {
        parseMappingCsv("/mappings/methods.csv", methodMap);
        parseMappingCsv("/mappings/fields.csv",  fieldMap);
        System.out.println("[Mod-Agent] Loaded " + methodMap.size() + " method mappings, "
                + fieldMap.size() + " field mappings.");
    }

    private static void parseMappingCsv(String resource, Map<String, String> target) {
        try (InputStream is = AgentBootstrap.class.getResourceAsStream(resource)) {
            if (is == null) {
                System.out.println("[Mod-Agent] Mappings not found: " + resource
                        + " — SRG→MCP remapping will not work!");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) target.put(parts[0].trim(), parts[1].trim());
            }
        } catch (Exception e) {
            System.out.println("[Mod-Agent] Failed to load mappings " + resource + ": " + e);
        }
    }

    // ---- Everything below unchanged from original ----

    private static String buildModListProperty(List<ModEntry> mods) {
        StringBuilder sb = new StringBuilder();
        for (ModEntry mod : mods) {
            File f = new File(mod.jar());
            if (!f.exists()) continue;
            try (JarFile jf = new JarFile(f)) {
                for (java.util.jar.JarEntry entry : Collections.list(jf.entries())) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (InputStream is = jf.getInputStream(entry)) {
                        byte[] bytes = is.readAllBytes();
                        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
                        String[] modClass    = {null};
                        String[] initMethod  = {null};
                        boolean[] isAgentMod = {false};
                        cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            private String className;
                            @Override public void visit(int v, int a, String name, String sig, String sup, String[] i) { this.className = name; }
                            @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                                if (FORGE_MOD_DESC.equals(desc))      { modClass[0] = className; isAgentMod[0] = false; }
                                else if (AGENT_MOD_DESC.equals(desc)) { modClass[0] = className; isAgentMod[0] = true;  }
                                return null;
                            }
                            @Override public org.objectweb.asm.MethodVisitor visitMethod(int a, String name, String desc, String sig, String[] ex) {
                                if (!desc.contains("net/minecraftforge/fml/common/event/FMLInitializationEvent")) return null;
                                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                    @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String d, boolean v) {
                                        if (d.contains("EventHandler")) initMethod[0] = name;
                                        return null;
                                    }
                                };
                            }
                        }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);
                        if (modClass[0] != null) {
                            if (!sb.isEmpty()) sb.append(",");
                            sb.append(modClass[0]).append("|").append(initMethod[0] != null ? initMethod[0] : "")
                                    .append("|").append(mod.property() != null ? mod.property() : "")
                                    .append("|").append(isAgentMod[0] ? "1" : "0");
                            System.out.println("[Mod-Agent] Discovered " + (isAgentMod[0] ? "@AgentMod" : "@Mod") + ": " + modClass[0] + " (init: " + initMethod[0] + ")");
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
                jf.stream().filter(e -> e.getName().endsWith(".class"))
                        .map(e -> e.getName().replace(".class", "")).forEach(names::add);
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
                if (jar != null) mods.add(new ModEntry(jar, mixin, property));
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