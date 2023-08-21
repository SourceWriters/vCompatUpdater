package net.sourcewriters.minecraft.vcompat.updater;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MethodHandles.Lookup;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandler;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;

@SuppressWarnings({
    "rawtypes",
    "unchecked",
    "removal"
})
final class WrappedURLClassLoader {

    private static volatile boolean setup = false;
    private static final boolean ACC = isACC();

    private static MethodHandle URLClassLoader_get_ucp;

    private static MethodHandle URLClassPath_get_path;
    private static MethodHandle URLClassPath_get_unopenedUrls;
    private static MethodHandle URLClassPath_get_loaders;
    private static MethodHandle URLClassPath_get_lmap;
    private static MethodHandle URLClassPath_get_jarHandler;
    private static MethodHandle URLClassPath_get_acc;

    private static MethodHandle ParseUtil_fileToEncodedURL;

    private static MethodHandle URLUtil_urlNoFragString;

    private static MethodHandle JarLoader_init;

    private static boolean isACC() {
        try {
            Class.forName("java.security.AccessControlContext");
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static void setup(Lookup lookup) throws IllegalAccessException, ClassNotFoundException, NoSuchFieldException, NoSuchMethodException {
        if (setup) {
            return;
        }
        setup = true;
        // Find classes
        Class<?> urlUtilClass = lookup.findClass("sun.net.util.URLUtil");
        Class<?> parseUtilClass = lookup.findClass("sun.net.www.ParseUtil");
        Class<?> classPathClass = lookup.findClass("jdk.internal.loader.URLClassPath");
        // Setup ParseUtil
        Lookup parseUtilLookup = MethodHandles.privateLookupIn(parseUtilClass, lookup);
        ParseUtil_fileToEncodedURL = parseUtilLookup.findStatic(parseUtilClass, "fileToEncodedURL",
            MethodType.methodType(URL.class, File.class));
        // Setup URLUtil
        Lookup urlUtilLookup = MethodHandles.privateLookupIn(urlUtilClass, lookup);
        URLUtil_urlNoFragString = urlUtilLookup.findStatic(urlUtilClass, "urlNoFragString", MethodType.methodType(String.class, URL.class));
        // Setup URLClassLoader
        Lookup classLoaderLookup = MethodHandles.privateLookupIn(URLClassLoader.class, lookup);
        URLClassLoader_get_ucp = classLoaderLookup.findGetter(URLClassLoader.class, "ucp", classPathClass);
        // Setup URLClassPath
        Lookup classPathLookup = MethodHandles.privateLookupIn(classPathClass, lookup);
        URLClassPath_get_path = classPathLookup.findGetter(classPathClass, "path", ArrayList.class);
        URLClassPath_get_loaders = classPathLookup.findGetter(classPathClass, "loaders", ArrayList.class);
        URLClassPath_get_unopenedUrls = classPathLookup.findGetter(classPathClass, "unopenedUrls", ArrayDeque.class);
        URLClassPath_get_lmap = classPathLookup.findGetter(classPathClass, "lmap", HashMap.class);
        URLClassPath_get_jarHandler = classPathLookup.findGetter(classPathClass, "jarHandler", URLStreamHandler.class);
        URLClassPath_get_acc = ACC ? classPathLookup.findGetter(classPathClass, "acc", AccessControlContext.class) : null;
        Class<?> jarLoaderClass = classPathLookup.findClass("jdk.internal.loader.URLClassPath$JarLoader");
        // Setup URLClassPath.JarLoader
        Lookup jarLoaderLookup = MethodHandles.privateLookupIn(jarLoaderClass, lookup);
        JarLoader_init = jarLoaderLookup.findConstructor(jarLoaderClass,
            ACC ? MethodType.methodType(void.class, URL.class, URLStreamHandler.class, HashMap.class, AccessControlContext.class)
                : MethodType.methodType(void.class, URL.class, URLStreamHandler.class, HashMap.class));
    }

    private final URLClassLoader loader;
    // This is of type jdk.internal.loader.URLClassPath
    private final Object ucp;

    private ArrayList<URL> ucp_path;
    private ArrayDeque<URL> ucp_unopenedUrls;
    private ArrayList ucp_loaders;
    private HashMap ucp_lmap;
    private URLStreamHandler ucp_jarHandler;

    public WrappedURLClassLoader(URLClassLoader loader) {
        this.loader = loader;
        this.ucp = retrieveUCP();
    }

    private Object retrieveUCP() {
        try {
            return URLClassLoader_get_ucp.invoke(loader);
        } catch (Throwable e) {
            return null;
        }
    }

    public URLClassLoader getLoader() {
        return loader;
    }

    public boolean isValid() {
        return ucp != null;
    }

    public void setup() throws Throwable {
        if (ucp_unopenedUrls != null) {
            return;
        }
        ucp_path = (ArrayList<URL>) URLClassPath_get_path.invoke(ucp);
        ucp_loaders = (ArrayList<?>) URLClassPath_get_loaders.invoke(ucp);
        ucp_unopenedUrls = (ArrayDeque<URL>) URLClassPath_get_unopenedUrls.invoke(ucp);
        ucp_lmap = (HashMap<String, ?>) URLClassPath_get_lmap.invoke(ucp);
        ucp_jarHandler = (URLStreamHandler) URLClassPath_get_jarHandler.invoke(ucp);
    }

    public void addFile(String path) throws Throwable {
        addFile(new File(path));
    }

    public void addFile(File file) throws Throwable {
        addURL((URL) ParseUtil_fileToEncodedURL.invoke(file.getCanonicalFile()));
    }

    public void addURL(URL url) throws Throwable {
        if (url == null) {
            throw new NullPointerException("URL can not be null!");
        }
        setup();
        String string = (String) URLUtil_urlNoFragString.invoke(url);
        synchronized (ucp_unopenedUrls) {
            if (ucp_lmap.containsKey(string)) {
                return;
            }
            ucp_path.add(url);
        }
        Object loader = createLoader(url);
        ucp_loaders.add(loader);
        ucp_lmap.put(string, loader);
    }

    private Object createLoader(URL url) throws Throwable {
        if (ACC) {
            AccessControlContext ucp_acc = (AccessControlContext) URLClassPath_get_acc.invoke(ucp);
            return AccessController.doPrivileged(new PrivilegedExceptionAction<>() {
                @Override
                public Object run() throws Exception {
                    try {
                        return JarLoader_init.invoke(url, ucp_jarHandler, ucp_lmap, ucp_acc);
                    } catch (Throwable e) {
                        throw new ReflectiveOperationException(e.getMessage(), e);
                    }
                }
            }, ucp_acc);
        } else {
            return AccessController.doPrivileged(new PrivilegedExceptionAction<>() {
                @Override
                public Object run() throws Exception {
                    try {
                        return JarLoader_init.invoke(url, ucp_jarHandler, ucp_lmap);
                    } catch (Throwable e) {
                        throw new ReflectiveOperationException(e.getMessage(), e);
                    }
                }
            });
        }
    }

}
