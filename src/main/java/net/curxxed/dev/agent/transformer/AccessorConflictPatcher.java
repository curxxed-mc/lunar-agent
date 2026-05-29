package net.curxxed.dev.agent.transformer;

import net.curxxed.dev.agent.AgentLog;
import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

// Renames @Accessor and @Invoker methods on mixin interfaces to avoid clashing with
// Lunar's own methods on the same target class.
//
// NOTE ON NAMING CONVENTION:
// We suffix the mod prefix (e.g. getCurBlockDamageMP_modid) rather than prefixing it.
// This is important because AgentBootstrap separately
// injects an explicit value= into the @Accessor annotation before this rename runs,
// which tells Mixin the exact field to target regardless of the method name. The suffix
// strategy is kept consistent with that approach and avoids any future regression if the
// value= injection were ever skipped.
public class AccessorConflictPatcher implements ClassFileTransformer {

    private final Set<String> modClassNames;

    // "ownerInternalName\nmethodName\ndescriptor" → newMethodName
    private final Map<String, String> renames;

    public AccessorConflictPatcher(Set<String> modClassNames, Map<String, String> renames) {
        this.modClassNames = modClassNames;
        this.renames       = renames;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null) return null;
        if (renames.isEmpty()) return null;
        if (!modClassNames.contains(className)) return null;

        boolean relevant = false;
        for (String key : renames.keySet()) {
            if (key.startsWith(className + "\n")) { relevant = true; break; }
        }
        if (!relevant) {
            for (String key : renames.keySet()) {
                String methodName = key.split("\n")[1];
                if (containsUtf8(classfileBuffer, methodName)) { relevant = true; break; }
            }
        }
        if (!relevant) return null;

        byte[] result = applyRenames(classfileBuffer, renames);
        AgentLog.log("AccessorConflictPatcher applied renames in: " + className);
        return result;
    }

    public static byte[] applyRenames(byte[] bytes, Map<String, String> renames) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String currentOwner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.currentOwner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            // Method DECLARATIONS: rename if this class owns the accessor method.
            // This covers both the accessor interface declaration AND the
            // Mixin-generated implementation on the Minecraft target class.
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String key     = currentOwner + "\n" + name + "\n" + descriptor;
                String newName = renames.get(key);
                if (newName != null) {
                    AgentLog.log("Renaming accessor declaration: "
                            + name + " → " + newName + " in " + currentOwner);
                    return super.visitMethod(access, newName, descriptor, signature, exceptions);
                }
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String descriptor, boolean isInterface) {
                        String callKey    = owner + "\n" + mName + "\n" + descriptor;
                        String mappedName = renames.get(callKey);
                        if (mappedName != null) {
                            AgentLog.log("Renaming accessor call site: "
                                    + mName + " → " + mappedName + " (owner: " + owner + ")");
                            super.visitMethodInsn(opcode, owner, mappedName, descriptor, isInterface);
                        } else {
                            super.visitMethodInsn(opcode, owner, mName, descriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);

        return cw.toByteArray();
    }


    private static boolean containsUtf8(byte[] bytes, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (bytes[i + j] != n[j]) continue outer;
            }
            return true;
        }
        return false;
    }

}