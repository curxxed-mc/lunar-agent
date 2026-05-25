package net.curxxed.dev.agent.mappings;

import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MappingRegistry {

    public enum Namespace {
        OBF, SRG, MCP
    }

    private final EnumMap<Namespace, Map<String, ClassMapping>> classesByName =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Map<String, FieldMapping>> fieldsByKey =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Map<String, MethodMapping>> methodsByKey =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Map<String, FieldMapping>> uniqueFieldsByName =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Map<String, MethodMapping>> uniqueMethodsByNameDesc =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Map<String, MethodMapping>> uniqueMethodsByOwnerName =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Set<String>> ambiguousFieldsByName =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Set<String>> ambiguousMethodsByNameDesc =
            new EnumMap<>(Namespace.class);

    private final EnumMap<Namespace, Set<String>> ambiguousMethodsByOwnerName =
            new EnumMap<>(Namespace.class);

    private final Map<String, String> superClasses = new HashMap<>();

    // SRG func_/field_ names are globally unique identifiers — the same name always
    // resolves to the same target regardless of which class it's called on.
    // This gives us an owner-agnostic last-resort lookup when the owner-based chain
    // walk fails (e.g. because the runtime class loader doesn't expose class bytes for
    // hierarchy traversal, or because the declaring class differs from the call-site owner).
    private final Map<String, MethodMapping> methodsBySrgName = new HashMap<>();
    private final Map<String, FieldMapping>  fieldsBySrgName  = new HashMap<>();

    private int classCount;
    private int fieldCount;
    private int methodCount;

    public MappingRegistry() {
        for (Namespace namespace : Namespace.values()) {
            classesByName.put(namespace, new HashMap<>());
            fieldsByKey.put(namespace, new HashMap<>());
            methodsByKey.put(namespace, new HashMap<>());
            uniqueFieldsByName.put(namespace, new HashMap<>());
            uniqueMethodsByNameDesc.put(namespace, new HashMap<>());
            uniqueMethodsByOwnerName.put(namespace, new HashMap<>());
            ambiguousFieldsByName.put(namespace, new HashSet<>());
            ambiguousMethodsByNameDesc.put(namespace, new HashSet<>());
            ambiguousMethodsByOwnerName.put(namespace, new HashSet<>());
        }
    }

    public static class ClassMapping {

        public final String obfName;
        public final String srgName;
        public final String mcpName;

        ClassMapping(String obfName, String srgName, String mcpName) {
            this.obfName = obfName;
            this.srgName = srgName;
            this.mcpName = mcpName;
        }

        public String name(Namespace namespace) {
            return switch (namespace) {
                case OBF -> obfName;
                case SRG -> srgName;
                case MCP -> mcpName;
            };
        }
    }

    public static class FieldMapping {

        public final ClassMapping owner;
        public final String obfName;
        public final String srgName;
        public final String mcpName;

        FieldMapping(ClassMapping owner,
                     String obfName,
                     String srgName,
                     String mcpName) {
            this.owner = owner;
            this.obfName = obfName;
            this.srgName = srgName;
            this.mcpName = mcpName;
        }

        public String ownerName(Namespace namespace) {
            return owner.name(namespace);
        }

        public String name(Namespace namespace) {
            return switch (namespace) {
                case OBF -> obfName;
                case SRG -> srgName;
                case MCP -> mcpName;
            };
        }
    }

    public static class MethodMapping {

        public final ClassMapping owner;

        public final String obfName;
        public final String obfDesc;

        public final String srgName;
        public final String srgDesc;

        public final String mcpName;
        public final String mcpDesc;

        MethodMapping(ClassMapping owner,
                      String obfName,
                      String obfDesc,
                      String srgName,
                      String srgDesc,
                      String mcpName,
                      String mcpDesc) {

            this.owner = owner;

            this.obfName = obfName;
            this.obfDesc = obfDesc;

            this.srgName = srgName;
            this.srgDesc = srgDesc;

            this.mcpName = mcpName;
            this.mcpDesc = mcpDesc;
        }

        public String ownerName(Namespace namespace) {
            return owner.name(namespace);
        }

        public String name(Namespace namespace) {
            return switch (namespace) {
                case OBF -> obfName;
                case SRG -> srgName;
                case MCP -> mcpName;
            };
        }

        public String desc(Namespace namespace) {
            return switch (namespace) {
                case OBF -> obfDesc;
                case SRG -> srgDesc;
                case MCP -> mcpDesc;
            };
        }
    }

    public static MappingRegistry load(InputStream is) throws Exception {
        MappingRegistry registry = new MappingRegistry();

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8));

        List<List<String>> rows = new ArrayList<>();

        String line;
        boolean first = true;

        while ((line = reader.readLine()) != null) {
            if (first) {
                first = false;
                continue;
            }

            List<String> parts = parseCsvLine(line);

            if (parts.size() >= 5) {
                rows.add(parts);
            }
        }

        for (List<String> row : rows) {

            if (!"class".equals(col(row, 0))) {
                continue;
            }

            String obfName = normalizeInternalName(col(row, 2));
            String srgName = normalizeInternalName(col(row, 3));
            String mcpName = normalizeInternalName(
                    emptyTo(col(row, 4), srgName)
            );

            if (obfName.isEmpty() || srgName.isEmpty()) {
                continue;
            }

            registry.addClass(
                    new ClassMapping(obfName, srgName, mcpName)
            );
        }

        for (List<String> row : rows) {

            String type = col(row, 0);

            if (!"field".equals(type) && !"method".equals(type)) {
                continue;
            }

            String obfOwner = normalizeInternalName(col(row, 1));
            String obfNameDesc = col(row, 2);

            String srgName = col(row, 3);
            String mcpName = emptyTo(col(row, 4), srgName);

            if (obfOwner.isEmpty()
                    || obfNameDesc.isEmpty()
                    || srgName.isEmpty()) {
                continue;
            }

            ClassMapping owner =
                    registry.classByName(Namespace.OBF, obfOwner);

            if (owner == null) {
                owner = new ClassMapping(
                        obfOwner,
                        obfOwner,
                        obfOwner
                );

                registry.addClass(owner);
            }

            if ("field".equals(type)) {

                FieldMapping field =
                        new FieldMapping(
                                owner,
                                obfNameDesc,
                                srgName,
                                mcpName
                        );

                registry.addField(field);

            } else {

                int spaceIndex = obfNameDesc.indexOf(' ');

                String obfName =
                        spaceIndex >= 0
                                ? obfNameDesc.substring(0, spaceIndex)
                                : obfNameDesc;

                String obfDesc =
                        spaceIndex >= 0
                                ? obfNameDesc.substring(spaceIndex + 1)
                                : "";

                String srgDesc =
                        registry.remapDescriptor(
                                obfDesc,
                                Namespace.SRG
                        );

                String mcpDesc =
                        registry.remapDescriptor(
                                obfDesc,
                                Namespace.MCP
                        );

                MethodMapping method =
                        new MethodMapping(
                                owner,
                                obfName,
                                obfDesc,
                                srgName,
                                srgDesc,
                                mcpName,
                                mcpDesc
                        );

                registry.addMethod(method);
            }
        }

        return registry;
    }

    public void registerSuperClass(String child, String parent) {

        if (child == null || parent == null) {
            return;
        }

        child = normalizeInternalName(child);
        parent = normalizeInternalName(parent);

        superClasses.put(child, parent);

        ClassMapping childMapping = findClass(child);
        ClassMapping parentMapping = findClass(parent);

        if (childMapping != null && parentMapping != null) {

            for (Namespace ns : Namespace.values()) {

                superClasses.put(
                        childMapping.name(ns),
                        parentMapping.name(ns)
                );
            }
        }
    }

    public int classCount() {
        return classCount;
    }

    public int fieldCount() {
        return fieldCount;
    }

    public int methodCount() {
        return methodCount;
    }

    public String mapClass(String internalName,
                           Namespace targetNamespace) {

        ClassMapping mapping = findClass(internalName);

        return mapping != null
                ? mapping.name(targetNamespace)
                : internalName;
    }

    public String mapMethodName(String owner,
                                String name,
                                String descriptor,
                                Namespace targetNamespace) {

        if (name == null
                || "<init>".equals(name)
                || "<clinit>".equals(name)) {
            return name;
        }

        MethodMapping mapping =
                findMethod(owner, name, descriptor);

        if (mapping == null) {
            return name;
        }

        String mapped = mapping.name(targetNamespace);

        return mapped == null || mapped.isEmpty()
                ? name
                : mapped;
    }

    public String mapFieldName(String owner,
                               String name,
                               String descriptor,
                               Namespace targetNamespace) {

        if (name == null) {
            return null;
        }

        FieldMapping mapping = findField(owner, name);

        if (mapping == null) {
            return name;
        }

        String mapped = mapping.name(targetNamespace);

        return mapped == null || mapped.isEmpty()
                ? name
                : mapped;
    }

    public String remapDescriptor(String desc,
                                  Namespace targetNamespace) {

        if (desc == null || desc.isEmpty()) {
            return desc;
        }

        try {

            if (desc.charAt(0) == '(') {

                Type methodType = Type.getMethodType(desc);

                Type[] args = methodType.getArgumentTypes();

                StringBuilder out = new StringBuilder("(");

                for (Type arg : args) {
                    out.append(remapType(arg, targetNamespace));
                }

                out.append(")")
                        .append(
                                remapType(
                                        methodType.getReturnType(),
                                        targetNamespace
                                )
                        );

                return out.toString();
            }

            return remapType(
                    Type.getType(desc),
                    targetNamespace
            ).getDescriptor();

        } catch (RuntimeException ignored) {
            return desc;
        }
    }

    public String remapMethodSelector(String owner,
                                      String selector,
                                      Namespace targetNamespace) {

        if (selector == null || selector.isEmpty()) {
            return selector;
        }

        int descStart = selector.indexOf('(');

        if (descStart <= 0) {
            return mapMethodName(
                    owner,
                    selector,
                    null,
                    targetNamespace
            );
        }

        String name = selector.substring(0, descStart);
        String desc = selector.substring(descStart);

        String mappedName =
                mapMethodName(
                        owner,
                        name,
                        desc,
                        targetNamespace
                );

        String mappedDesc =
                remapDescriptor(desc, targetNamespace);

        return mappedName + mappedDesc;
    }

    public String remapMemberReference(String reference,
                                       Namespace targetNamespace) {

        if (reference == null || reference.isEmpty()) {
            return reference;
        }

        int ownerStart = reference.indexOf('L');
        int ownerEnd = reference.indexOf(';', ownerStart + 1);

        if (ownerStart < 0 || ownerEnd < 0) {
            return reference;
        }

        String owner =
                reference.substring(ownerStart + 1, ownerEnd);

        String suffix = reference.substring(ownerEnd + 1);

        String mappedOwner =
                mapClass(owner, targetNamespace);

        int descStart = suffix.indexOf('(');

        if (descStart >= 0) {

            String methodName =
                    suffix.substring(0, descStart);

            String desc =
                    suffix.substring(descStart);

            String mappedName =
                    mapMethodName(
                            owner,
                            methodName,
                            desc,
                            targetNamespace
                    );

            return "L"
                    + mappedOwner
                    + ";"
                    + mappedName
                    + remapDescriptor(desc, targetNamespace);
        }

        int colon = suffix.indexOf(':');

        if (colon >= 0) {

            String fieldName =
                    suffix.substring(0, colon);

            String desc =
                    suffix.substring(colon + 1);

            String mappedName =
                    mapFieldName(
                            owner,
                            fieldName,
                            desc,
                            targetNamespace
                    );

            return "L"
                    + mappedOwner
                    + ";"
                    + mappedName
                    + ":"
                    + remapDescriptor(desc, targetNamespace);
        }

        String mappedName =
                mapFieldName(
                        owner,
                        suffix,
                        null,
                        targetNamespace
                );

        return "L" + mappedOwner + ";" + mappedName;
    }

    private Type remapType(Type type,
                           Namespace targetNamespace) {

        return switch (type.getSort()) {

            case Type.ARRAY ->
                    Type.getType(
                            "[".repeat(type.getDimensions())
                                    + remapType(
                                    type.getElementType(),
                                    targetNamespace
                            ).getDescriptor()
                    );

            case Type.OBJECT ->
                    Type.getObjectType(
                            mapClass(
                                    type.getInternalName(),
                                    targetNamespace
                            )
                    );

            default -> type;
        };
    }

    private void addClass(ClassMapping mapping) {

        classCount++;

        for (Namespace namespace : Namespace.values()) {

            classesByName.get(namespace)
                    .put(mapping.name(namespace), mapping);
        }
    }

    private void addField(FieldMapping mapping) {

        fieldCount++;

        for (Namespace namespace : Namespace.values()) {

            String key =
                    fieldKey(
                            mapping.ownerName(namespace),
                            mapping.name(namespace)
                    );

            fieldsByKey.get(namespace)
                    .put(key, mapping);

            putUnique(
                    uniqueFieldsByName.get(namespace),
                    ambiguousFieldsByName.get(namespace),
                    mapping.name(namespace),
                    mapping
            );
        }

        // SRG field_ names are globally unique — index for owner-agnostic fallback lookup.
        if (mapping.srgName != null && !mapping.srgName.isEmpty()
                && !mapping.srgName.equals(mapping.obfName)) {
            fieldsBySrgName.putIfAbsent(mapping.srgName, mapping);
        }
    }

    private void addMethod(MethodMapping mapping) {

        methodCount++;

        for (Namespace namespace : Namespace.values()) {

            String key =
                    methodKey(
                            mapping.ownerName(namespace),
                            mapping.name(namespace),
                            mapping.desc(namespace)
                    );

            methodsByKey.get(namespace)
                    .put(key, mapping);

            putUnique(
                    uniqueMethodsByOwnerName.get(namespace),
                    ambiguousMethodsByOwnerName.get(namespace),
                    fieldKey(
                            mapping.ownerName(namespace),
                            mapping.name(namespace)
                    ),
                    mapping
            );

            if (!mapping.desc(namespace).isEmpty()) {

                putUnique(
                        uniqueMethodsByNameDesc.get(namespace),
                        ambiguousMethodsByNameDesc.get(namespace),
                        mapping.name(namespace)
                                + mapping.desc(namespace),
                        mapping
                );
            }
        }

        // SRG func_ names are globally unique — index for owner-agnostic fallback lookup.
        // When the owner-based chain walk fails (e.g. the call site is a subclass that
        // inherits the method, and we can't traverse the hierarchy at runtime), this lets
        // us resolve func_NNNNN_X directly without needing to know the declaring class.
        // putIfAbsent: first writer wins, which is fine since all entries for the same
        // SRG name map to the same target name by the SRG uniqueness guarantee.
        if (mapping.srgName != null && !mapping.srgName.isEmpty()
                && !mapping.srgName.equals(mapping.obfName)) {
            methodsBySrgName.putIfAbsent(mapping.srgName, mapping);
        }
    }

    private ClassMapping findClass(String internalName) {

        for (Namespace namespace : Namespace.values()) {

            ClassMapping mapping =
                    classByName(namespace, internalName);

            if (mapping != null) {
                return mapping;
            }
        }

        return null;
    }

    private ClassMapping classByName(Namespace namespace,
                                     String internalName) {

        return classesByName.get(namespace)
                .get(internalName);
    }

    private MethodMapping findMethod(String owner,
                                     String name,
                                     String descriptor) {

        if (owner != null) {

            ClassMapping ownerMapping = findClass(owner);

            for (Namespace namespace : Namespace.values()) {

                String currentOwner =
                        ownerMapping != null
                                ? ownerMapping.name(namespace)
                                : owner;

                while (currentOwner != null) {

                    if (descriptor != null) {

                        String namespaceDesc =
                                remapDescriptor(
                                        descriptor,
                                        namespace
                                );

                        MethodMapping mapping =
                                methodsByKey.get(namespace).get(
                                        methodKey(
                                                currentOwner,
                                                name,
                                                namespaceDesc
                                        )
                                );

                        if (mapping != null) {
                            return mapping;
                        }
                    }

                    MethodMapping mapping =
                            uniqueMethodsByOwnerName.get(namespace)
                                    .get(
                                            fieldKey(
                                                    currentOwner,
                                                    name
                                            )
                                    );

                    if (mapping != null) {
                        return mapping;
                    }

                    currentOwner =
                            superClasses.get(currentOwner);
                }
            }
        }

        if (descriptor != null) {

            for (Namespace namespace : Namespace.values()) {

                MethodMapping mapping =
                        uniqueMethodsByNameDesc.get(namespace)
                                .get(name + descriptor);

                if (mapping != null) {
                    return mapping;
                }
            }
        }

        // Last resort: SRG func_ names are globally unique, so if the name is one we
        // recognise we can return the mapping directly regardless of owner or descriptor.
        // This fires when the call-site owner is a subclass that inherits the method
        // and we cannot traverse the hierarchy at runtime (e.g. the class loader doesn't
        // expose Minecraft .class resources via getResourceAsStream).
        MethodMapping byFuncName = methodsBySrgName.get(name);
        if (byFuncName != null) {
            return byFuncName;
        }

        return null;
    }

    private FieldMapping findField(String owner,
                                   String name) {

        if (owner != null) {

            ClassMapping ownerMapping = findClass(owner);

            for (Namespace namespace : Namespace.values()) {

                String namespaceOwner =
                        ownerMapping != null
                                ? ownerMapping.name(namespace)
                                : owner;

                FieldMapping mapping =
                        fieldsByKey.get(namespace)
                                .get(
                                        fieldKey(
                                                namespaceOwner,
                                                name
                                        )
                                );

                if (mapping != null) {
                    return mapping;
                }
            }
        }

        for (Namespace namespace : Namespace.values()) {

            FieldMapping mapping =
                    uniqueFieldsByName.get(namespace)
                            .get(name);

            if (mapping != null) {
                return mapping;
            }
        }

        // Last resort: SRG field_ names are globally unique — same logic as findMethod.
        FieldMapping byFieldName = fieldsBySrgName.get(name);
        if (byFieldName != null) {
            return byFieldName;
        }

        return null;
    }

    private static <T> void putUnique(Map<String, T> map,
                                      Set<String> ambiguous,
                                      String key,
                                      T value) {

        if (key == null
                || key.isEmpty()
                || ambiguous.contains(key)) {
            return;
        }

        T previous = map.get(key);

        if (previous == null || previous == value) {

            map.put(key, value);

        } else {

            map.remove(key);
            ambiguous.add(key);
        }
    }

    private static String fieldKey(String owner,
                                   String name) {

        return owner + "." + name;
    }

    private static String methodKey(String owner,
                                    String name,
                                    String descriptor) {

        return owner + "." + name + descriptor;
    }

    private static String col(List<String> row,
                              int index) {

        return index < row.size()
                ? row.get(index).trim()
                : "";
    }

    private static String emptyTo(String value,
                                  String fallback) {

        return value == null || value.isEmpty()
                ? fallback
                : value;
    }

    private static String normalizeInternalName(String value) {

        return value == null
                ? ""
                : value.trim().replace('.', '/');
    }

    private static List<String> parseCsvLine(String line) {

        List<String> values = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {

            char c = line.charAt(i);

            if (c == '"') {

                if (quoted
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    current.append('"');
                    i++;

                } else {

                    quoted = !quoted;
                }

            } else if (c == ',' && !quoted) {

                values.add(current.toString());
                current.setLength(0);

            } else {

                current.append(c);
            }
        }

        values.add(current.toString());

        return values;
    }
}