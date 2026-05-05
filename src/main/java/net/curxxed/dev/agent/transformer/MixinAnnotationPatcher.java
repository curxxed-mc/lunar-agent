package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

// patches mixin annotations on mod classes at load time so the mod author doesn't have to.
// specifically:
//   - forces remap=false on every mixin annotation that supports it
//   - clamps @Mixin priority to MAX_PRIORITY so we don't conflict with Lunar's own mixins
//
public class MixinAnnotationPatcher implements ClassFileTransformer {

    private static final String MIXIN_DESC    = "Lorg/spongepowered/asm/mixin/Mixin;";

    // member-level annotations that all accept remap= and need it forced to false.    
    private static final String SHADOW_DESC   = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC  = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String INJECT_DESC   = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG    = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String MODIFY_ARGS   = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String MODIFY_CONST  = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";
    private static final String MODIFY_VAR    = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String OVERWRITE     = "Lorg/spongepowered/asm/mixin/Overwrite;";

    // applied to both visitField and visitMethod annotations.
    // @Mixin is NOT in here because it needs the extra priority-clamping logic.
    private static final Set<String> REMAP_TARGETS = Set.of(
            SHADOW_DESC, ACCESSOR_DESC, INVOKER_DESC,
            INJECT_DESC, REDIRECT_DESC, MODIFY_ARG, MODIFY_ARGS,
            MODIFY_CONST, MODIFY_VAR, OVERWRITE
    );

    // Lunar's own mixins run at priority 200+. anything above 100 risks an overwrite conflict.
    // if you're STILL getting conflicts at 100: lower it, or stop using @Overwrite.
    // @Overwrite is a last resort and you probably don't actually need it.
    private static final int MAX_PRIORITY = 100;

    private final Set<String> modClassNames;

    public MixinAnnotationPatcher(Set<String> modClassNames) {
        this.modClassNames = modClassNames;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);

        // quick scan before we bother spinning up a ClassWriter.
        // most mod classes aren't mixin classes, so this skips a lot of unnecessary work.
        if (!hasMixinAnnotation(cr)) return null;

        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new MixinPatchingVisitor(cw), 0);

        byte[] patched = cw.toByteArray();
        System.out.println("[Mod-Agent] Patched mixin annotations in: " + className);
        return patched;
    }

    private boolean hasMixinAnnotation(ClassReader cr) {
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
        MixinPatchingVisitor(ClassVisitor cv) { super(Opcodes.ASM9, cv); }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            AnnotationVisitor av = super.visitAnnotation(desc, visible);
            // @Mixin on the class needs both remap=false and priority clamping
            if (MIXIN_DESC.equals(desc)) return new MixinClassAnnotationVisitor(av);
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
                    if (REMAP_TARGETS.contains(desc)) return new RemapFalseVisitor(av);
                    return av;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    AnnotationVisitor av = super.visitAnnotation(desc, visible);
                    // @Shadow/@Invoker on methods + all injection annotations
                    if (REMAP_TARGETS.contains(desc)) return new RemapFalseVisitor(av);
                    return av;
                }
            };
        }
    }

    // rewrites @Mixin to have remap=false and priority clamped to MAX_PRIORITY.
    // handles both the case where the value is already present (override it)
    // and where it's absent (inject it at visitEnd).
    private static class MixinClassAnnotationVisitor extends AnnotationVisitor {
        private boolean sawRemap    = false;
        private boolean sawPriority = false;

        MixinClassAnnotationVisitor(AnnotationVisitor av) { super(Opcodes.ASM9, av); }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                sawRemap = true;
                super.visit(name, false); // force false regardless of what was there
                return;
            }
            if ("priority".equals(name)) {
                sawPriority = true;
                int p = (int) value;
                if (p > MAX_PRIORITY) {
                    System.out.println("[Mod-Agent] Clamping mixin priority from " + p + " to " + MAX_PRIORITY);
                }
                super.visit(name, Math.min(p, MAX_PRIORITY));
                return;
            }
            super.visit(name, value);
        }

        @Override
        public void visitEnd() {
            // if the annotation didn't have these fields at all, inject them now
            if (!sawRemap)    super.visit("remap",    false);
            if (!sawPriority) super.visit("priority", MAX_PRIORITY);
            super.visitEnd();
        }
    }

    // used for all member-level annotations (fields and methods).
    // only needs to force remap=false — no priority logic needed here.
    private static class RemapFalseVisitor extends AnnotationVisitor {
        private boolean sawRemap = false;

        RemapFalseVisitor(AnnotationVisitor av) { super(Opcodes.ASM9, av); }

        @Override
        public void visit(String name, Object value) {
            if ("remap".equals(name)) {
                sawRemap = true;
                super.visit(name, false);
                return;
            }
            super.visit(name, value);
        }

        @Override
        public void visitEnd() {
            if (!sawRemap) super.visit("remap", false);
            super.visitEnd();
        }
    }
}