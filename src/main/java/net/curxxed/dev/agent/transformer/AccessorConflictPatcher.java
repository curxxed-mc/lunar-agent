package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

// Renames @Accessor and @Invoker methods on mixin interfaces to avoid clashing with
// Lunar's own methods on the same target class.
//
// THE CORE PROBLEM THIS SOLVES:
// Lunar has its own AccessorConflictPatcher that also runs and prefixes ALL accessor
// methods from agent mods with "LunarAgentMod_". It correctly renames:
//   1. The method declaration in the accessor interface
//   2. All call sites in mod classes
// But it does NOT rename the Mixin-generated implementation on the target Minecraft
// class (e.g. EntityPlayerSP). This mismatch causes AbstractMethodError at runtime.
//
// Our fix: when we build the rename map we also record which Minecraft target classes
// have accessor implementations that need to be renamed. We then transform those
// target classes in addition to mod classes so the implementation name always matches
// the interface name, regardless of what Lunar's own patcher does on top.
//
// The rename map key is:  "ownerInternalName\nmethodName\ndescriptor"
// The rename map value is: newMethodName
//
// IMPORTANT: applyRenames() is also called as a static helper by AgentBootstrap during
// the mixin class pre-patching step (mixin classes are read by Mixin directly via
// getResourceAsStream and never go through ClassFileTransformer, so we must patch them
// ahead of time in the temp directory).
public class AccessorConflictPatcher implements ClassFileTransformer {

    private final Set<String> modClassNames;

    // "ownerInternalName\nmethodName\ndescriptor" → newMethodName
    private final Map<String, String> renames;

    public AccessorConflictPatcher(Set<String> modClassNames, Map<String, String> renames) {
        this.modClassNames = modClassNames;
        this.renames       = renames;
    }

    // ── ClassFileTransformer ──────────────────────────────────────────────────

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
        System.out.println("[Mod-Agent] AccessorConflictPatcher applied renames in: " + className);
        return result;
    }

    // ── Core rename logic ─────────────────────────────────────────────────────

    // Static so AgentBootstrap can call it during mixin class pre-patching.
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
                    System.out.println("[Mod-Agent] Renaming accessor declaration: "
                            + name + " → " + newName + " in " + currentOwner);
                    return super.visitMethod(access, newName, descriptor, signature, exceptions);
                }
                // Pass through unchanged but wrap to rewrite any call sites inside.
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String descriptor, boolean isInterface) {
                        String callKey    = owner + "\n" + mName + "\n" + descriptor;
                        String mappedName = renames.get(callKey);
                        if (mappedName != null) {
                            System.out.println("[Mod-Agent] Renaming accessor call site: "
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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