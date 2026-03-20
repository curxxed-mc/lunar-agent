package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

// Translates SRG names (func_XXXXX_x, field_XXXXX_x) to MCP names at class load time.
// This fires via ClassFileTransformer for normal mod classes (everything except mixin classes,
// which Mixin reads via getResourceAsStream and which are pre-patched by AgentBootstrap instead).
public class RuntimeRemapper implements ClassFileTransformer {

    private final Map<String, String> methodMap;
    private final Map<String, String> fieldMap;
    private final Set<String> modClassNames;

    // Maps are pre-loaded by AgentBootstrap so the CSV files are only parsed once.
    public RuntimeRemapper(Set<String> modClassNames,
                           Map<String, String> methodMap,
                           Map<String, String> fieldMap) {
        this.modClassNames = modClassNames;
        this.methodMap     = methodMap;
        this.fieldMap      = fieldMap;
        System.out.println("[Mod-Agent] RuntimeRemapper ready: "
                + methodMap.size() + " method mappings, " + fieldMap.size() + " field mappings.");
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        // Scan raw bytes for func_/field_ before doing a full ASM pass.
        // Most mod classes won't have SRG names if they were built with dev mappings.
        if (!containsSrgNames(classfileBuffer)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);

        // COMPUTE_MAXS not COMPUTE_FRAMES: SRG→MCP only renames methods and fields.
        // Class names and descriptors don't change, so existing stack frames are still valid.
        // COMPUTE_FRAMES triggers getCommonSuperClass, which fails on unloaded Minecraft classes.
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassRemapper(cw, new SrgRemapper()), 0);

        byte[] remapped = cw.toByteArray();
        System.out.println("[Mod-Agent] Remapped SRG->MCP: " + className);
        return remapped;
    }

    private boolean containsSrgNames(byte[] bytes) {
        for (int i = 0; i < bytes.length - 6; i++) {
            if (bytes[i] == 'f') {
                if (bytes[i+1]=='u' && bytes[i+2]=='n' && bytes[i+3]=='c' && bytes[i+4]=='_') return true;
                if (bytes[i+1]=='i' && bytes[i+2]=='e' && bytes[i+3]=='l' && bytes[i+4]=='d' && bytes[i+5]=='_') return true;
            }
        }
        return false;
    }

    private class SrgRemapper extends Remapper {
        @Override public String mapMethodName(String owner, String name, String descriptor) {
            String m = methodMap.get(name); return m != null ? m : name;
        }
        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String f = fieldMap.get(name); return f != null ? f : name;
        }
    }
}