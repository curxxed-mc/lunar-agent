package net.curxxed.dev.agent.transformer;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// translates SRG names (func_XXXXX_x, field_XXXXX_x) to MCP names at class load time.
// this exists because normal Forge reobfuscates mods to SRG for distribution,
// but Lunar's Ichor applies MCP mappings at runtime and expects MCP names everywhere.
// so a jar that works fine on vanilla Forge will silently call the wrong methods on Lunar.
// great design. love it. this class fixes it.
public class RuntimeRemapper implements ClassFileTransformer {

    // SRG method names are globally unique across the entire 1.8.9 codebase,
    // so we can key on just the name without needing owner or descriptor.
    // if this assumption breaks on some obscure edge case, you'll know because
    // something will silently call the wrong method and nothing will work.
    private final Map<String, String> methodMap = new HashMap<>();

    // same story for fields. field_XXXXX_x is always globally unique.
    private final Map<String, String> fieldMap = new HashMap<>();

    private final Set<String> modClassNames;

    public RuntimeRemapper(Set<String> modClassNames) {
        this.modClassNames = modClassNames;
        loadMappings();
    }

    private void loadMappings() {
        // methods.csv: searge,name,side,desc
        // e.g. func_71407_l,runTick,2,""
        parseCsv("/mappings/methods.csv", methodMap);

        // fields.csv: searge,name,side,desc
        // e.g. field_71428_a,thePlayer,2,""
        parseCsv("/mappings/fields.csv", fieldMap);

        System.out.println("[Mod-Agent] Loaded " + methodMap.size() + " method mappings, "
                + fieldMap.size() + " field mappings.");
    }

    private void parseCsv(String resource, Map<String, String> target) {
        try (InputStream is = RuntimeRemapper.class.getResourceAsStream(resource)) {
            if (is == null) {
                // you forgot to put the CSVs in src/main/resources/mappings/.
                // get MCP stable 9.10 for 1.8.9. you want methods.csv and fields.csv.
                // without these the remapper does nothing and your mod is broken on Lunar.
                System.out.println("[Mod-Agent] Mappings resource not found: " + resource
                        + " -- SRG->MCP remapping will not work!");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            reader.readLine(); // skip header row
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    target.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (Exception e) {
            System.out.println("[Mod-Agent] Failed to load mappings: " + resource + " -- " + e);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null || !modClassNames.contains(className)) return null;

        // scan raw bytes for func_/field_ before committing to a full ASM pass.
        // most mod classes won't have SRG names if they were built with dev mappings,
        // so this check saves a lot of unnecessary work.
        if (!containsSrgNames(classfileBuffer)) return null;

        ClassReader cr = new ClassReader(classfileBuffer);

        // COMPUTE_MAXS not COMPUTE_FRAMES here, my mistake! SRG->MCP only renames methods and fields,
        // class names and descriptors don't change, so the existing stack frames are still valid.
        // we tried COMPUTE_FRAMES first. it calls getCommonSuperClass, fails on unloaded
        // Minecraft classes, falls back to Object, and silently corrupts type assignments.
        // COMPUTE_MAXS just recalculates max stack and locals and leaves the frames alone.
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

        cr.accept(new ClassRemapper(cw, new SrgRemapper()), 0);

        byte[] remapped = cw.toByteArray();
        System.out.println("[Mod-Agent] Remapped SRG->MCP: " + className);
        return remapped;
    }

    // scans raw bytes for the func_ and field_ prefixes.
    // crude, but fast. no point doing a full class parse just to find out there's nothing to remap.
    private boolean containsSrgNames(byte[] bytes) {
        for (int i = 0; i < bytes.length - 6; i++) {
            if (bytes[i] == 'f') {
                if (bytes[i+1] == 'u' && bytes[i+2] == 'n' && bytes[i+3] == 'c' && bytes[i+4] == '_')
                    return true;
                if (bytes[i+1] == 'i' && bytes[i+2] == 'e' && bytes[i+3] == 'l' && bytes[i+4] == 'd' && bytes[i+5] == '_')
                    return true;
            }
        }
        return false;
    }

    private class SrgRemapper extends Remapper {

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String mapped = methodMap.get(name);
            return mapped != null ? mapped : name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = fieldMap.get(name);
            return mapped != null ? mapped : name;
        }

        // class names are identical between SRG and MCP mappings.
        // only method and field names differ. so we leave mapType, mapDesc, etc. alone.
    }
}