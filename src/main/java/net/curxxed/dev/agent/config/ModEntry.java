package net.curxxed.dev.agent.config;

import java.util.Objects;

public class ModEntry {

    private final String jar;
    private final String mixin;
    private final String property;

    public ModEntry(String jar, String mixin, String property) {
        this.jar = jar;
        this.mixin = mixin;
        this.property = property;
    }

    public String getJar() {
        return jar;
    }

    public String getMixin() {
        return mixin;
    }

    public String getProperty() {
        return property;
    }

    @Override
    public String toString() {
        return "ModEntry{" +
                "jar='" + jar + '\'' +
                ", mixin='" + mixin + '\'' +
                ", property='" + property + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModEntry)) return false;

        final ModEntry modEntry = (ModEntry) o;

        if (!Objects.equals(jar, modEntry.jar)) return false;
        if (!Objects.equals(mixin, modEntry.mixin)) return false;
        return Objects.equals(property, modEntry.property);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jar, mixin, property);
    }
}