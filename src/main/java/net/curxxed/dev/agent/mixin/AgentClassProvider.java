package net.curxxed.dev.agent.mixin;

import net.curxxed.dev.agent.AgentBootstrap;
import org.spongepowered.asm.service.IClassProvider;
import java.net.URL;

public class AgentClassProvider implements IClassProvider {

    @Override
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {

        return findClass(name, true);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {

        return Class.forName(name, initialize, ClassLoader.getSystemClassLoader());
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {

        return Class.forName(name, initialize, AgentBootstrap.class.getClassLoader());
    }
}
