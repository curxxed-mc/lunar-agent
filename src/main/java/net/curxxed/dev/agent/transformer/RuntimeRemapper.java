package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.mappings.MappingRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

// Translates mod bytecode from any known mapping namespace (obf, SRG, MCP)
// into the namespace used by the current runtime.
public class RuntimeRemapper implements ClassFileTransformer {

    private final MappingRegistry mappings;
    private final Supplier<Environment> environmentSupplier;
    private final Set<String> modClassNames;

    public RuntimeRemapper(Set<String> modClassNames,
                           MappingRegistry mappings,
                           Supplier<Environment> environmentSupplier) {
        this.modClassNames = modClassNames;
        this.mappings = mappings;
        this.environmentSupplier = environmentSupplier;
        System.out.println("[Mod-Agent] RuntimeRemapper ready: "
                + mappings.classCount() + " classes, "
                + mappings.methodCount() + " methods, "
                + mappings.fieldCount() + " fields.");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        Environment environment = environmentSupplier.get();
        byte[] remapped = remapBytes(classfileBuffer, mappings, environment, modClassNames, loader);
        if (remapped == classfileBuffer) return null;

        System.out.println("[Mod-Agent] Remapped " + className + " -> " + environment + " runtime names");
        return remapped;
    }

    public static byte[] remapBytes(byte[] bytes,
                                    MappingRegistry mappings,
                                    Environment environment,
                                    Set<String> protectedClassNames,
                                    ClassLoader loader) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        NamespaceRemapper remapper = new NamespaceRemapper(
                loader, mappings, environment.namespace(), protectedClassNames);
        cr.accept(new ClassRemapper(cw, remapper), 0);
        return remapper.changed() ? cw.toByteArray() : bytes;
    }

    private static class NamespaceRemapper extends Remapper {
        private final ClassLoader loader;
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final Set<String> protectedClassNames;

        // Tracks classes whose superclass chain has already been seeded into the registry
        // so we only pay the getResourceAsStream cost once per unique owner class.
        private final Set<String> seededClasses = new HashSet<>();

        private boolean changed;

        NamespaceRemapper(ClassLoader loader,
                          MappingRegistry mappings,
                          MappingRegistry.Namespace targetNamespace,
                          Set<String> protectedClassNames) {
            this.loader = loader;
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.protectedClassNames = protectedClassNames;
        }

        boolean changed() {
            return changed;
        }

        @Override
        public String map(String internalName) {
            if (internalName == null || protectedClassNames.contains(internalName)) return internalName;
            String mapped = mappings.mapClass(internalName, targetNamespace);
            if (!mapped.equals(internalName)) changed = true;
            return mapped;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            seedSuperClassChain(owner);
            String mapped = mappings.mapMethodName(owner, name, descriptor, targetNamespace);
            if (!mapped.equals(name)) changed = true;
            return mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            seedSuperClassChain(owner);
            String mapped = mappings.mapFieldName(owner, name, descriptor, targetNamespace);
            if (!mapped.equals(name)) changed = true;
            return mapped;
        }

        // Walk the runtime class hierarchy rooted at internalName and register every
        // parent relationship into MappingRegistry.superClasses so that findMethod's
        // while-loop chain walk can follow them.
        //
        // This only helps when the class loader exposes .class resources (e.g. vanilla
        // Forge/MCP environments). It silently no-ops when bytes are unavailable.
        // The primary fix for the inherited-method case is in MappingRegistry.findMethod
        // via the SRG-name direct lookup.
        private void seedSuperClassChain(String internalName) {
            if (internalName == null || !seededClasses.add(internalName)) return;

            ClassInfo info = readClassInfo(internalName);
            if (info == null) return;

            if (info.superName != null && !"java/lang/Object".equals(info.superName)) {
                mappings.registerSuperClass(internalName, info.superName);
                seedSuperClassChain(info.superName);
            }
            for (String iface : info.interfaces) {
                mappings.registerSuperClass(internalName, iface);
                seedSuperClassChain(iface);
            }
        }

        private ClassInfo readClassInfo(String internalName) {
            byte[] bytes = readClassBytes(internalName);
            if (bytes == null) return null;

            final ClassInfo[] out = {null};
            ClassReader cr = new ClassReader(bytes);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    out[0] = new ClassInfo(superName, interfaces == null ? new String[0] : interfaces);
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return out[0];
        }

        private byte[] readClassBytes(String internalName) {
            String resource = internalName + ".class";
            try {
                InputStream is = loader != null ? loader.getResourceAsStream(resource) : null;
                if (is == null) is = ClassLoader.getSystemResourceAsStream(resource);
                if (is == null) return null;
                try (InputStream in = is) {
                    return in.readAllBytes();
                }
            } catch (Exception ignored) {
                return null;
            }
        }

        private static final class ClassInfo {
            final String superName;
            final String[] interfaces;

            ClassInfo(String superName, String[] interfaces) {
                this.superName = superName;
                this.interfaces = interfaces;
            }
        }
    }
}