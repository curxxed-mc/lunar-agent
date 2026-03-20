package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

// Renames @Accessor and @Invoker methods on mixin interfaces to avoid clashing with
// Lunar's own methods on the same target class.
//
// Example: a mod declares `@Accessor("timer") Timer getTimer()` on a Minecraft accessor
// mixin, but Lunar already generated its own getTimer() on Minecraft. Mixin sees two
// methods with the same name and crashes. We rename the mod's version to
// `mod_timer` everywhere: in the interface declaration, and in every call site
// across the rest of the mod's classes.
//
// The rename map is built at premain time by AgentBootstrap.buildAccessorRenameMap()
// by scanning the mod JARs before any classes are loaded. The map key is:
//   "ownerInternalName\nmethodName\ndescriptor"
// The value is the new method name.
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

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;
        if (renames.isEmpty()) return null;

        // Quick check: does the rename map contain any entry for this owner?
        // Avoids a full ASM parse for the vast majority of mod classes that aren't
        // accessor interfaces or call sites.
        boolean relevant = false;
        for (String key : renames.keySet()) {
            if (key.startsWith(className + "\n")) { relevant = true; break; }
        }
        if (!relevant) {
            // Check if this class *calls* any of the renamed methods (as a call site).
            // We have to scan the raw bytes for the old method names.
            for (String key : renames.keySet()) {
                String methodName = key.split("\n")[1];
                if (containsUtf8(classfileBuffer, methodName)) { relevant = true; break; }
            }
        }
        if (!relevant) return null;

        byte[] result = applyRenames(classfileBuffer, renames);
        if (result != classfileBuffer) {
            System.out.println("[Mod-Agent] AccessorConflictPatcher applied renames in: " + className);
        }
        return result;
    }

    // Static so AgentBootstrap can call it during mixin class pre-patching.
    // Applies the rename map to a class's byte array:
    //   - In the class that *declares* the accessor methods: renames the method declarations.
    //   - In any class that *calls* those methods: renames the INVOKEINTERFACE/INVOKEVIRTUAL sites.
    // Both cases are handled by the same ClassRemapper-like visitor: we intercept
    // visitMethod for declarations and visitMethodInsn for call sites.
    public static byte[] applyRenames(byte[] bytes, Map<String, String> renames) {
        ClassReader cr = new ClassReader(bytes);
        // No COMPUTE_MAXS needed — we're only renaming method names, not changing
        // control flow or adding instructions.
        ClassWriter cw = new ClassWriter(cr, 0);

        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String currentOwner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                this.currentOwner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            // Method DECLARATIONS: rename if this class is the owning accessor interface
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
                // Pass through unchanged, but still wrap so we can rewrite call sites below
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {

                    // Method CALL SITES: rename INVOKEINTERFACE / INVOKEVIRTUAL targeting
                    // one of the accessor methods we've renamed
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String descriptor, boolean isInterface) {
                        String callKey  = owner + "\n" + mName + "\n" + descriptor;
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

    // Checks whether the raw class bytes contain a given UTF-8 string.
    // Used as a fast pre-filter before committing to a full ASM parse.
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