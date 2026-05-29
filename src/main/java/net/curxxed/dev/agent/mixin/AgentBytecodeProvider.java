package net.curxxed.dev.agent.mixin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import java.io.IOException;
import java.io.InputStream;

public class AgentBytecodeProvider implements IClassBytecodeProvider {

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {

        return getClassNode(name, true);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {

        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags) throws ClassNotFoundException, IOException {

        final String resource = name.replace('.', '/') + ".class";
        final InputStream i = ClassLoader.getSystemClassLoader().getResourceAsStream(resource);

        if (i == null) {
            throw new ClassNotFoundException(name);
        }

        try {
            ClassReader reader = new ClassReader(i);

            ClassNode node = new ClassNode();

            reader.accept(node, readerFlags);

            return node;
        } finally {
            i.close();
        }
    }
}
