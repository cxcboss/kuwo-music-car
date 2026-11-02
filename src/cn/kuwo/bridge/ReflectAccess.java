package cn.kuwo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Cache only members selected by internal bridge code; never cache player objects. */
final class ReflectAccess {
    private static final ConcurrentHashMap<Class<?>, Members> CLASSES = new ConcurrentHashMap<Class<?>, Members>();
    private ReflectAccess() {}

    private static Members members(Class<?> type) {
        Members value = CLASSES.get(type);
        if (value == null) {
            Members created = new Members();
            Members previous = CLASSES.putIfAbsent(type, created);
            value = previous == null ? created : previous;
        }
        return value;
    }
    static Method method(Class<?> type, String name, Class<?>... arguments) throws NoSuchMethodException {
        Members members = members(type);
        Signature signature = new Signature(name, arguments);
        Method result = members.methods.get(signature);
        if (result == null) {
            Method found = type.getMethod(name, arguments);
            found.setAccessible(true);
            Method previous = members.methods.putIfAbsent(signature, found);
            result = previous == null ? found : previous;
        }
        return result;
    }
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Members members = members(type);
        Field result = members.fields.get(name);
        if (result == null) {
            Field found = type.getField(name);
            Field previous = members.fields.putIfAbsent(name, found);
            result = previous == null ? found : previous;
        }
        return result;
    }
    static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Members members = members(type);
        String key = "declared:"+name;
        Field result = members.fields.get(key);
        if (result == null) {
            Class<?> parent = type;
            while (parent != null) {
                try {
                    Field found = parent.getDeclaredField(name);
                    found.setAccessible(true);
                    Field previous = members.fields.putIfAbsent(key, found);
                    return previous == null ? found : previous;
                } catch (NoSuchFieldException missing) { parent = parent.getSuperclass(); }
            }
            throw new NoSuchFieldException(type.getName()+"."+name);
        }
        return result;
    }
    private static final class Members {
        final ConcurrentHashMap<Signature, Method> methods = new ConcurrentHashMap<Signature, Method>();
        final ConcurrentHashMap<String, Field> fields = new ConcurrentHashMap<String, Field>();
    }
    private static final class Signature {
        final String name;
        final Class<?>[] arguments;
        final int hash;
        Signature(String name, Class<?>[] arguments) {
            this.name = name;
            this.arguments = arguments.clone();
            this.hash = 31 * name.hashCode() + Arrays.hashCode(arguments);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Signature)) return false;
            Signature value = (Signature) other;
            return name.equals(value.name) && Arrays.equals(arguments, value.arguments);
        }
    }
}
