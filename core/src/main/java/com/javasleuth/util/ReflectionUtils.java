package com.javasleuth.util;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;

public final class ReflectionUtils {
    private static final Method CAN_ACCESS_METHOD;

    static {
        Method m = null;
        try {
            m = AccessibleObject.class.getMethod("canAccess", Object.class);
        } catch (Exception ignored) {
            // Java 8 doesn't have canAccess(Object)
            SleuthLogger.trace("AccessibleObject.canAccess(Object) not available; falling back to isAccessible()");
        }
        CAN_ACCESS_METHOD = m;
    }

    private ReflectionUtils() {}

    public static boolean canAccess(AccessibleObject obj, Object target) {
        if (obj == null) {
            return false;
        }
        if (CAN_ACCESS_METHOD != null) {
            try {
                Object v = CAN_ACCESS_METHOD.invoke(obj, target);
                if (v instanceof Boolean) {
                    return (Boolean) v;
                }
            } catch (Exception ignored) {
                // fall back
                SleuthLogger.trace("ReflectionUtils.canAccess invoke failed; falling back to isAccessible(): " + ignored.getMessage());
            }
        }
        return obj.isAccessible();
    }

    public static boolean trySetAccessible(AccessibleObject obj) {
        if (obj == null) {
            return false;
        }
        try {
            obj.setAccessible(true);
            return true;
        } catch (RuntimeException e) {
            SleuthLogger.trace("ReflectionUtils.trySetAccessible failed: " + e.getMessage());
            return false;
        }
    }
}
