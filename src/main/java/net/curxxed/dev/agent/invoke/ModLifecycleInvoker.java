package net.curxxed.dev.agent.invoke;

import sun.misc.Unsafe;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lifecycle order:
 *   FMLPreInitializationEvent  (0)
 *   FMLInitializationEvent     (1)
 *   FMLPostInitializationEvent (2)
 *   FMLLoadCompleteEvent       (3)
 */
public class ModLifecycleInvoker {

    private static final Logger LOG = Logger.getLogger("ModLifecycleInvoker");

    private static final String EVENT_HANDLER_CLASS =
            "net.minecraftforge.fml.common.Mod$EventHandler";

    private static final String[] LIFECYCLE_ORDER = {
            "net.minecraftforge.fml.common.event.FMLPreInitializationEvent",
            "net.minecraftforge.fml.common.event.FMLInitializationEvent",
            "net.minecraftforge.fml.common.event.FMLPostInitializationEvent",
            "net.minecraftforge.fml.common.event.FMLLoadCompleteEvent",
    };

    private static final Unsafe UNSAFE;

    static {
        Unsafe u = null;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[ModLifecycleInvoker] Could not obtain Unsafe.  Lifecycle invocation will not work!", e);
        }
        UNSAFE = u;
    }

    public static void invokeLifecycle(Object modInstance, File configDir, ClassLoader loader) {
        if (UNSAFE == null) {
            LOG.severe("[ModLifecycleInvoker] Unsafe unavailable,  aborting lifecycle.");
            return;
        }

        Class<?> modClass = modInstance.getClass();
        Class<? extends Annotation> eventHandlerAnnotation = resolveAnnotation(loader);

        if (eventHandlerAnnotation == null) {
            LOG.warning("[ModLifecycleInvoker] Could not resolve @EventHandler; falling back to name-based matching.");
        }

        List<Method> lifecycleMethods = collectLifecycleMethods(modClass, eventHandlerAnnotation);

        if (lifecycleMethods.isEmpty()) {
            LOG.info("[ModLifecycleInvoker] No lifecycle methods found on " + modClass.getName());
            return;
        }

        for (Method method : lifecycleMethods) {
            Class<?> eventType = method.getParameterTypes()[0];
            Object event = buildEvent(eventType, configDir);

            if (event == null) {
                LOG.warning("[ModLifecycleInvoker] Skipping " + method.getName()
                        + " — could not construct event " + eventType.getName());
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(modInstance, event);
                LOG.info("[ModLifecycleInvoker] Invoked " + method.getName()
                        + "(" + eventType.getSimpleName() + ") on " + modClass.getSimpleName());
            } catch (Exception e) {
                Throwable cause = (e.getCause() != null) ? e.getCause() : e;
                LOG.log(Level.SEVERE,
                        "[ModLifecycleInvoker] Exception in " + method.getName()
                                + "(" + eventType.getSimpleName() + "): " + cause.getMessage(),
                        cause);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> resolveAnnotation(ClassLoader loader) {
        try {
            return (Class<? extends Annotation>) Class.forName(EVENT_HANDLER_CLASS, true, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static List<Method> collectLifecycleMethods(
            Class<?> modClass, Class<? extends Annotation> eventHandlerAnnotation) {

        List<Method> found = new ArrayList<>();

        // Walk the class hierarchy
        for (Class<?> c = modClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;

                boolean annotated = (eventHandlerAnnotation != null)
                        ? m.isAnnotationPresent(eventHandlerAnnotation)
                        : isEventHandlerByName(m);

                if (!annotated) continue;

                // Avoid duplicates
                if (!found.contains(m)) {
                    found.add(m);
                }
            }
        }

        found.sort(Comparator.comparingInt(m -> lifecycleIndex(m.getParameterTypes()[0].getName())));
        return found;
    }

    /// Fallback
    private static boolean isEventHandlerByName(Method m) {
        for (Annotation a : m.getAnnotations()) {
            String name = a.annotationType().getSimpleName();
            if ("EventHandler".equals(name)) return true;
        }
        return false;
    }

    private static int lifecycleIndex(String className) {
        for (int i = 0; i < LIFECYCLE_ORDER.length; i++) {
            if (LIFECYCLE_ORDER[i].equals(className)) return i;
        }
        // Unknown lifecycle events fire last but still fire, just in case the mod is doing something weird that we don't know about.
        return LIFECYCLE_ORDER.length;
    }

    private static Object buildEvent(Class<?> eventClass, File configDir) {
        try {
            Object event = UNSAFE.allocateInstance(eventClass);
            injectConfigDir(event, eventClass, configDir);
            return event;
        } catch (InstantiationException e) {
            LOG.log(Level.WARNING,
                    "[ModLifecycleInvoker] allocateInstance failed for " + eventClass.getName(), e);
            return null;
        }
    }

    private static void injectConfigDir(Object event, Class<?> eventClass, File configDir) {
        for (Class<?> c = eventClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String name = f.getName().toLowerCase();
                boolean isFileType = f.getType() == File.class;
                boolean looksLikeConfigDir =
                        name.contains("config") || name.contains("cfgdir") || name.contains("moddir");

                if (isFileType && looksLikeConfigDir) {
                    try {
                        f.setAccessible(true);
                        f.set(event, configDir);
                        LOG.fine("[ModLifecycleInvoker] Injected configDir into "
                                + eventClass.getSimpleName() + "." + f.getName());
                    } catch (IllegalAccessException ex) {
                        LOG.log(Level.WARNING,
                                "[ModLifecycleInvoker] Could not set field " + f.getName(), ex);
                    }
                    // Only set the first matching field
                    return;
                }
            }
        }
    }
}