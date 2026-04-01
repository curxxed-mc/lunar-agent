package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;

/* Forge's event bus needs a no-args constructor to instantiate event classes via reflection.
 * mod authors don't always add one because under normal FML usage events are instantiated
 * manually with their required args. but when the agent injects them the bus tries to
 * reflectively construct them and crashes. so we add one synthetically at load time.
 * yes, we are generating bytecode to fix someone else's implicit API contract.
 */
public class EventConstructorPatcher implements ClassFileTransformer {

    // if it doesn't extend this (directly or indirectly) we don't touch it
    private static final String FORGE_EVENT = "net/minecraftforge/fml/common/eventhandler/Event";

    private final Set<String> modClassNames;

    public EventConstructorPatcher(Set<String> modClassNames) {
        this.modClassNames = modClassNames;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);

        if (!extendsForgeEvent(cr)) return null;
        if (hasNoArgsConstructor(cr)) return null;

        // COMPUTE_FRAMES here because we're generating new bytecode from scratch.
        // unlike RuntimeRemapper which only renames things, we're actually adding a method,
        // so we need frames computed properly. the getCommonSuperClass fallback handles
        // classes that aren't loaded yet at transform time.
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // most Minecraft/Forge classes aren't loaded yet when this runs.
                // Class.forName throws, we catch it and return Object.
                // this is safe because the constructor we're injecting is so trivial
                // that the precise type relationship between any two classes doesn't matter.
                try {
                    return super.getCommonSuperClass(type1, type2);
                } catch (Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        cr.accept(new NoArgsConstructorInjector(cw, cr.getSuperName()), 0);

        byte[] patched = cw.toByteArray();
        System.out.println("[Mod-Agent] Injected no-args constructor into event: " + className);
        return patched;
    }

    private boolean extendsForgeEvent(ClassReader cr) {
        String superName = cr.getSuperName();
        if (superName == null) return false;

        // direct subclass
        if (superName.equals(FORGE_EVENT)) return true;

        // one level of indirection catches PlayerEvent, EntityEvent, etc.
        // forge event hierarchies are shallow in practice so this covers basically everything.
        // if you have a 3-level-deep custom event hierarchy that's your problem, not mine.
        return superName.startsWith("net/minecraftforge/");
    }

    private boolean hasNoArgsConstructor(ClassReader cr) {
        boolean[] found = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("<init>".equals(name) && "()V".equals(descriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static class NoArgsConstructorInjector extends ClassVisitor {
        private final String superName;
        private String className;

        NoArgsConstructorInjector(ClassVisitor cv, String superName) {
            super(Opcodes.ASM9, cv);
            this.superName = superName;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superN, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superN, interfaces);
        }

        @Override
        public void visitEnd() {
            // ACC_SYNTHETIC so decompilers know we injected this and don't get confused.
            // ACC_PUBLIC because the event bus needs to call it from outside the class.
            MethodVisitor mv = super.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                    "<init>", "()V", null, null
            );
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            // call super(). if super doesn't have a no-args constructor either,
            // you've hit a case this doesn't handle. it's rare. if it happens, good luck.
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1); // COMPUTE_FRAMES recalculates this anyway, ASM just wants a value
            mv.visitEnd();
            super.visitEnd();
        }
    }
}