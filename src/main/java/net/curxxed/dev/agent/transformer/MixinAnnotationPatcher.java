package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.AgentLog;
import net.curxxed.dev.agent.mappings.MappingRegistry;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Supplier;

// Patches mixin annotations on mod classes at load time so the mod author
// does not have to match Lunar/vanilla runtime names manually.
public class MixinAnnotationPatcher implements ClassFileTransformer {

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String MODIFY_CONST = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";
    private static final String MODIFY_VAR = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

    private static final Set<String> REMAP_FALSE_TARGETS = new HashSet<>(
            Arrays.asList(
                    SHADOW_DESC,
                    ACCESSOR_DESC,
                    INVOKER_DESC,
                    INJECT_DESC,
                    REDIRECT_DESC,
                    MODIFY_ARG,
                    MODIFY_ARGS,
                    MODIFY_CONST,
                    MODIFY_VAR,
                    OVERWRITE
            )
    );

    private static final Set<String> METHOD_SELECTOR_ANNOTATIONS = new HashSet<>(
            Arrays.asList(
                    INJECT_DESC,
                    REDIRECT_DESC,
                    MODIFY_ARG,
                    MODIFY_ARGS,
                    MODIFY_VAR
            )
    );

    private static final int MAX_PRIORITY = 100;

    private final Set<String> modClassNames;
    private final MappingRegistry mappings;
    private final Supplier<Environment> environmentSupplier;

    public MixinAnnotationPatcher(Set<String> modClassNames,
                                  MappingRegistry mappings,
                                  Supplier<Environment> environmentSupplier) {
        this.modClassNames = modClassNames;
        this.mappings = mappings;
        this.environmentSupplier = environmentSupplier;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        byte[] patched = apply(classfileBuffer, mappings, environmentSupplier.get());
        if (patched == classfileBuffer) return null;

        AgentLog.log("Patched mixin annotations in: " + className);
        return patched;
    }

    public static byte[] apply(byte[] bytes, MappingRegistry mappings, Environment environment) {
        ClassReader cr = new ClassReader(bytes);
        if (!hasMixinAnnotation(cr)) return bytes;

        // Pre-scan pass: collect which methods carry @Overwrite / @Shadow so we can rename
        // them in visitMethod below. We must do this as a separate pass because ASM visits
        // annotations *after* visitMethod returns, so we can't know at visitMethod time
        // whether a method is annotated without having read the bytecode first.
        Set<String> overwriteMethods = collectMethodsWithAnnotation(cr, OVERWRITE);
        Set<String> shadowMethods = collectMethodsWithAnnotation(cr, SHADOW_DESC);

        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new MixinPatchingVisitor(
                cw, mappings, environment.namespace(), overwriteMethods, shadowMethods), 0);
        return cw.toByteArray();
    }

    private static Set<String> collectMethodsWithAnnotation(ClassReader cr, String annotationDesc) {
        Set<String> result = new HashSet<>();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (annotationDesc.equals(desc)) result.add(name + descriptor);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static boolean hasMixinAnnotation(ClassReader cr) {
        boolean[] found = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (MIXIN_DESC.equals(desc)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static class MixinPatchingVisitor extends ClassVisitor {
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final List<String> mixinTargets = new ArrayList<>();
        private final Set<String> overwriteMethods;
        private final Set<String> shadowMethods;
        private final Map<String, String> renamedMethods = new HashMap<>();
        private String currentClass;

        MixinPatchingVisitor(ClassVisitor cv,
                             MappingRegistry mappings,
                             MappingRegistry.Namespace targetNamespace,
                             Set<String> overwriteMethods,
                             Set<String> shadowMethods) {
            super(Opcodes.ASM9, cv);
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.overwriteMethods = overwriteMethods;
            this.shadowMethods = shadowMethods;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.currentClass = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(desc, visible);
            if (MIXIN_DESC.equals(desc)) {
                return new MixinClassAnnotationVisitor(av, mappings, targetNamespace, mixinTargets);
            }
            return av;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            return new FieldVisitor(Opcodes.ASM9,
                    super.visitField(access, name, descriptor, signature, value)) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    AnnotationVisitor av = super.visitAnnotation(desc, visible);
                    if (REMAP_FALSE_TARGETS.contains(desc)) {
                        return new MemberAnnotationVisitor(
                                av, desc, mappings, targetNamespace, mixinTargets, true);
                    }
                    return av;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            String outName = name;
            String key = name + descriptor;

            // @Overwrite: rename the declaration itself to the mapped runtime name.
            if (overwriteMethods.contains(key) && !mixinTargets.isEmpty()) {
                String target = mixinTargets.get(0);
                String mapped = mappings.mapMethodName(target, name, descriptor, targetNamespace);
                if (!mapped.equals(name)) {
                    AgentLog.log("Remapping @Overwrite method: "
                            + name + " → " + mapped + " (target: " + target + ")");
                    outName = mapped;
                    renamedMethods.put(key, mapped);
                }
            }

            // @Shadow: do the same agent-side rename, but keep it separate so the overwrite
            // path remains untouched.
            if (shadowMethods.contains(key) && !mixinTargets.isEmpty()) {
                String target = mixinTargets.get(0);
                String mapped = mappings.mapMethodName(target, name, descriptor, targetNamespace);
                if (!mapped.equals(name)) {
                    AgentLog.log("Remapping @Shadow method: "
                            + name + " → " + mapped + " (target: " + target + ")");
                    outName = mapped;
                    renamedMethods.put(key, mapped);
                }
            }

            final String resolvedName = outName;
            final String originalName = name;
            final String methodDesc = descriptor;

            return new MethodVisitor(Opcodes.ASM9,
                    super.visitMethod(access, resolvedName, descriptor, signature, exceptions)) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    AnnotationVisitor av = super.visitAnnotation(desc, visible);
                    if (REMAP_FALSE_TARGETS.contains(desc)) {
                        return new MemberAnnotationVisitor(
                                av, desc, mappings, targetNamespace, mixinTargets, false);
                    }
                    return av;
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String mName,
                                            String desc, boolean isInterface) {
                    if (currentClass != null && currentClass.equals(owner)) {
                        String mapped = renamedMethods.get(mName + desc);
                        if (mapped != null) {
                            super.visitMethodInsn(opcode, owner, mapped, desc, isInterface);
                            return;
                        }
                    }
                    super.visitMethodInsn(opcode, owner, mName, desc, isInterface);
                }
            };
        }
    }

    private static class MixinClassAnnotationVisitor extends AnnotationVisitor {
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final List<String> mixinTargets;
        private boolean sawRemap;
        private boolean sawPriority;

        MixinClassAnnotationVisitor(AnnotationVisitor av,
                                    MappingRegistry mappings,
                                    MappingRegistry.Namespace targetNamespace,
                                    List<String> mixinTargets) {
            super(Opcodes.ASM9, av);
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.mixinTargets = mixinTargets;
        }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                sawRemap = true;
                super.visit(name, false);
                return;
            }
            if ("priority".equals(name)) {
                sawPriority = true;
                int priority = (int) value;
                if (priority > MAX_PRIORITY) {
                    AgentLog.log("Clamping mixin priority from "
                            + priority + " to " + MAX_PRIORITY);
                }
                super.visit(name, Math.min(priority, MAX_PRIORITY));
                return;
            }
            if ("value".equals(name) && value instanceof Type) {
                Type type = (Type) value;

                Type mapped = mapTargetType(type);
                super.visit(name, mapped);
                return;
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor av = super.visitArray(name);
            if ("value".equals(name) || "targets".equals(name)) {
                return new TargetArrayVisitor(av, mappings, targetNamespace, mixinTargets);
            }
            return av;
        }

        @Override
        public void visitEnd() {
            if (!sawRemap) super.visit("remap", false);
            if (!sawPriority) super.visit("priority", MAX_PRIORITY);
            super.visitEnd();
        }

        private Type mapTargetType(Type type) {
            String mapped = mappings.mapClass(type.getInternalName(), targetNamespace);
            mixinTargets.add(mapped);
            return Type.getObjectType(mapped);
        }
    }

    private static class TargetArrayVisitor extends AnnotationVisitor {
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final List<String> mixinTargets;

        TargetArrayVisitor(AnnotationVisitor av,
                           MappingRegistry mappings,
                           MappingRegistry.Namespace targetNamespace,
                           List<String> mixinTargets) {
            super(Opcodes.ASM9, av);
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.mixinTargets = mixinTargets;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type) {
                Type type = (Type) value;

                String mapped = mappings.mapClass(type.getInternalName(), targetNamespace);
                mixinTargets.add(mapped);
                super.visit(name, Type.getObjectType(mapped));
                return;
            }
            if (value instanceof String) {
                String target = (String) value;

                String mapped = mappings.mapClass(target.replace('.', '/'), targetNamespace);
                mixinTargets.add(mapped);
                super.visit(name, mapped.replace('/', '.'));
                return;
            }
            super.visit(name, value);
        }
    }

    private static class MemberAnnotationVisitor extends AnnotationVisitor {
        private final String annotationDesc;
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final List<String> mixinTargets;
        private final boolean fieldContext;
        private boolean sawRemap;

        MemberAnnotationVisitor(AnnotationVisitor av,
                                String annotationDesc,
                                MappingRegistry mappings,
                                MappingRegistry.Namespace targetNamespace,
                                List<String> mixinTargets,
                                boolean fieldContext) {
            super(Opcodes.ASM9, av);
            this.annotationDesc = annotationDesc;
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.mixinTargets = mixinTargets;
            this.fieldContext = fieldContext;
        }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                sawRemap = true;
                super.visit(name, false);
                return;
            }
            if (value instanceof String) {
                String text = (String) value;

                super.visit(name, mapStringValue(name, text));
                return;
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor av = super.visitArray(name);
            if ("method".equals(name)) {
                return new SelectorArrayVisitor(av, mappings, targetNamespace, mixinTargets);
            }
            return av;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            AnnotationVisitor av = super.visitAnnotation(name, descriptor);
            if (AT_DESC.equals(descriptor)) {
                return new AtAnnotationVisitor(av, mappings, targetNamespace);
            }
            return av;
        }

        @Override
        public void visitEnd() {
            if (!sawRemap) super.visit("remap", false);
            super.visitEnd();
        }

        private String mapStringValue(String name, String value) {
            String owner = firstMixinTarget();
            if ("method".equals(name) && METHOD_SELECTOR_ANNOTATIONS.contains(annotationDesc)) {
                return mappings.remapMethodSelector(owner, value, targetNamespace);
            }
            if ("value".equals(name) && ACCESSOR_DESC.equals(annotationDesc)) {
                return mappings.mapFieldName(owner, value, null, targetNamespace);
            }
            if ("value".equals(name) && INVOKER_DESC.equals(annotationDesc)) {
                return mappings.remapMethodSelector(owner, value, targetNamespace);
            }
            if ("aliases".equals(name) && SHADOW_DESC.equals(annotationDesc)) {
                return fieldContext
                        ? mappings.mapFieldName(owner, value, null, targetNamespace)
                        : mappings.remapMethodSelector(owner, value, targetNamespace);
            }
            return value;
        }

        private String firstMixinTarget() {
            return mixinTargets.isEmpty() ? null : mixinTargets.get(0);
        }
    }

    private static class SelectorArrayVisitor extends AnnotationVisitor {
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;
        private final List<String> mixinTargets;

        SelectorArrayVisitor(AnnotationVisitor av,
                             MappingRegistry mappings,
                             MappingRegistry.Namespace targetNamespace,
                             List<String> mixinTargets) {
            super(Opcodes.ASM9, av);
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
            this.mixinTargets = mixinTargets;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String) {
                String selector = (String) value;

                String owner = mixinTargets.isEmpty() ? null : mixinTargets.get(0);
                super.visit(name, mappings.remapMethodSelector(owner, selector, targetNamespace));
                return;
            }
            super.visit(name, value);
        }
    }

    private static class AtAnnotationVisitor extends AnnotationVisitor {
        private final MappingRegistry mappings;
        private final MappingRegistry.Namespace targetNamespace;

        AtAnnotationVisitor(AnnotationVisitor av,
                            MappingRegistry mappings,
                            MappingRegistry.Namespace targetNamespace) {
            super(Opcodes.ASM9, av);
            this.mappings = mappings;
            this.targetNamespace = targetNamespace;
        }

        @Override
        public void visit(String name, Object value) {
            if ("target".equals(name) && value instanceof String) {
                String reference = (String) value;

                super.visit(name, mappings.remapMemberReference(reference, targetNamespace));
                return;
            }
            super.visit(name, value);
        }
    }
}