package net.curxxed.dev.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// lightweight alternative to @Mod for mods that don't need Forge at all.
// works on vanilla Lunar, works on Forge Lunar via the agent.
// mods using this don't need FML, don't need the event bus, don't need anything
// except whatever Minecraft classes they target via mixins.
//
// usage:
//   @AgentMod(id = "my-mod", name = "My Mod", version = "1.0.0")
//   public class MyMod {
//       public void init() { ... } // optional, called by the agent on first tick
//   }
//
// add the agent JAR as compileOnly in your mod's build.gradle to get this on the classpath.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AgentMod {
    String id();
    String name() default "";
    String version() default "1.0.0";
}