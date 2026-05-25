package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.mappings.MappingRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftBootstrapTransformer implements ClassFileTransformer {

    private static final String MCP_MINECRAFT = "net/minecraft/client/Minecraft";

    private final MappingRegistry mappings;
    private final AtomicReference<Environment> runtimeEnvironment;

    public MinecraftBootstrapTransformer(MappingRegistry mappings,
                                         AtomicReference<Environment> runtimeEnvironment) {
        this.mappings = mappings;
        this.runtimeEnvironment = runtimeEnvironment;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null) return null;
        if (!"ave".equals(className) && !MCP_MINECRAFT.equals(className)) return null;

        Environment environment = Environment.detectFromMinecraftClass(className, classfileBuffer);
        runtimeEnvironment.set(environment);

        String targetClass = mappings.mapClass(MCP_MINECRAFT, environment.namespace());
        String targetMethod = mappings.mapMethodName(
                MCP_MINECRAFT, "startGame", "()V", environment.namespace());

        if (!className.equals(targetClass)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        boolean[] injected = {false};

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targetMethod.equals(name) || !"()V".equals(descriptor)) return mv;

                injected[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            visitBootstrapCall();
                        }
                        super.visitInsn(opcode);
                    }

                    private void visitBootstrapCall() {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader",
                                "getSystemClassLoader", "()Ljava/lang/ClassLoader;", false);
                        super.visitLdcInsn("net.curxxed.dev.agent.AgentBootstrap");
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                                "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                        super.visitLdcInsn("bootstrapLoadedMods");
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
                        super.visitInsn(Opcodes.DUP);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitLdcInsn(Type.getType("Ljava/lang/ClassLoader;"));
                        super.visitInsn(Opcodes.AASTORE);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                                "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                        super.visitInsn(Opcodes.DUP);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
                                "getClass", "()Ljava/lang/Class;", false);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                                "getClassLoader", "()Ljava/lang/ClassLoader;", false);
                        super.visitInsn(Opcodes.AASTORE);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                        super.visitInsn(Opcodes.POP);
                    }
                };
            }
        }, 0);

        if (!injected[0]) {
            System.out.println("[Mod-Agent] Could not find Minecraft.startGame for " + environment
                    + " runtime (" + className + "." + targetMethod + ").");
            return null;
        }

        System.out.println("[Mod-Agent] Injected direct mod bootstrap into "
                + className + "." + targetMethod + " for " + environment + ".");
        return cw.toByteArray();
    }
}
