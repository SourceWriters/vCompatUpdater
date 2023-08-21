package net.sourcewriters.minecraft.vcompat.updater;

final class AccessHelper {
    
    private AccessHelper() {
        throw new UnsupportedOperationException();
    }
    
    public static Class<?> getClass(String classPath, ClassLoader loader) {
        try {
            return Class.forName(classPath, false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
    
    public static Class<?> loadClass(String classPath, ClassLoader loader) {
        try {
            return Class.forName(classPath, true, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

}
