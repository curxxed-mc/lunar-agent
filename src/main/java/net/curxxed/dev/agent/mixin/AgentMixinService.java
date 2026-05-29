package net.curxxed.dev.agent.mixin;

import net.curxxed.dev.agent.AgentBootstrap;
import net.curxxed.dev.agent.AgentLog;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AgentMixinService extends MixinServiceAbstract {

    private final IClassProvider classProvider = new AgentClassProvider();
    private final IClassBytecodeProvider bytecodeProvider = new AgentBytecodeProvider();
    private final ReEntranceLock lock = new ReEntranceLock(1);
    private static final List<File> RESOURCE_ROOTS = new CopyOnWriteArrayList<>();

    public static void addResourceRoot(File file) {
        RESOURCE_ROOTS.add(file);
    }

    @Override
    public String getName() {
        return "Agent";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public IClassProvider getClassProvider() {
        return classProvider;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return bytecodeProvider;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {

        return new ITransformerProvider() {

            private final Set<String> exclusions = new HashSet<>();

            @Override
            public Collection<ITransformer> getTransformers() {
                return Collections.emptyList();
            }

            @Override
            public Collection<ITransformer> getDelegatedTransformers() {
                return Collections.emptyList();
            }

            @Override
            public void addTransformerExclusion(String name) {

                exclusions.add(name);

                AgentLog.log("Mixin transformer exclusion: " + name);
            }
        };
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {

        try {
            URI uri = AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return new ContainerHandleURI(uri);
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve agent container", e);
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {

        InputStream stream = AgentBootstrap.class.getClassLoader().getResourceAsStream(name);

        if (stream != null) {
            return stream;
        }

        for (File root : RESOURCE_ROOTS) {
            try {
                if (root.isDirectory()) {
                    File f = new File(root, name);

                    if (f.exists()) {
                        return Files.newInputStream(f.toPath());
                    }
                } else {
                    try (JarFile jf = new JarFile(root)) {
                        JarEntry entry = jf.getJarEntry(name);
                        if (entry != null) {
                            try (InputStream i = jf.getInputStream(entry)) {
                                return new ByteArrayInputStream(toByteArray(i));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return lock;
    }

    private static byte[] toByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;

        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }

        return out.toByteArray();
    }
}
