package net.sourcewriters.minecraft.vcompat.updater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.syntaxphoenix.syntaxapi.json.JsonArray;
import com.syntaxphoenix.syntaxapi.json.JsonObject;
import com.syntaxphoenix.syntaxapi.json.JsonValue;
import com.syntaxphoenix.syntaxapi.json.ValueType;
import com.syntaxphoenix.syntaxapi.json.value.JsonInteger;
import com.syntaxphoenix.syntaxapi.net.http.Request;
import com.syntaxphoenix.syntaxapi.net.http.RequestType;
import com.syntaxphoenix.syntaxapi.net.http.Response;
import com.syntaxphoenix.syntaxapi.net.http.StandardContentType;

import sun.misc.Unsafe;

public final class CompatUpdater {

    public static final CompatUpdater INSTANCE = new CompatUpdater();

    private static final String GITHUB_RELEASE = "https://api.github.com/repos/SourceWriters/vCompat/releases/tags/%s";
    private static final String GITHUB_TAGS = "https://api.github.com/repos/SourceWriters/vCompat/tags";

    private final HashMap<String, CompatApp> apps = new HashMap<>();
    private final Lock read, write;

    private final Path directory = Paths.get("plugins/vCompat");
    private final Path file = directory.resolve("vCompat.jar");

    private final ClassLoader[] classLoaders = new ClassLoader[] {
        Thread.currentThread().getContextClassLoader(),
        ClassLoader.getSystemClassLoader(),
        ClassLoader.getPlatformClassLoader(),
        getClass().getClassLoader()
    };

    private Unsafe unsafe;

    private URLClassLoader urlClassLoader;

    private String githubVersion;
    private String exactVersion;

    private State state = State.NONE;
    private int version = 0;
    private int requested = 0;

    private Reason reason;
    private String message;
    private Throwable exception;

    private Authenticator authenticator;

    private CompatUpdater() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        read = lock.readLock();
        write = lock.writeLock();
    }

    public void register(CompatApp app) {
        String id = app.getId();
        if (id == null || isRegistered(id)) {
            app.onFailed(Reason.ALREADY_REGISTERED, "Your App '" + id + "' is already registered!", null);
            app.state = AppState.FAILED;
            return;
        }
        if (app.state == AppState.FAILED) {
            return;
        }
        write.lock();
        try {
            apps.put(id, app);
            if (app.state != AppState.STARTED) {
                app.state = AppState.KNOWN;
            }
            int tmp = app.getTargetVersion();
            if (requested < tmp) {
                requested = tmp;
            }
        } finally {
            write.unlock();
        }
        if (app.state != AppState.STARTED) {
            return;
        }
        read.lock();
        try {
            State state = getState();
            if (state == State.SUCCESS) {
                if (app.getTargetVersion() == version) {
                    app.onReady();
                    app.state = AppState.RUNNING;
                    return;
                }
                app.onFailed(Reason.INCOMPATIBLE, "The version of vCompat that is installed is incompatible with the app '" + id + "'!",
                    null);
                app.state = AppState.FAILED;
                return;
            }
            if (state == State.FAILED) {
                app.onFailed(reason, message, exception);
                app.state = AppState.FAILED;
                return;
            }
        } finally {
            read.unlock();
        }
    }

    public void unregister(CompatApp app) {
        String id = app.getId();
        if (id == null || !isRegistered(id)) {
            app.onShutdown();
            app.state = AppState.NONE;
            return;
        }
        write.lock();
        try {
            apps.remove(id);
        } finally {
            write.unlock();
        }
        read.lock();
        try {
            if (apps.isEmpty()) {
                shutdown();
            }
        } finally {
            read.unlock();
        }
    }

    private void shutdown() {
        try {
            Class<?> control = getClass("net.sourcewriters.minecraft.vcompat.reflection.VersionControl");
            if (control != null) {
                control.getMethod("shutdown").invoke(control.getMethod("get").invoke(null));
                return;
            }
            Class<?> provider = getClass("net.sourcewriters.minecraft.vcompat.VersionCompatProvider");
            control = getClass("net.sourcewriters.minecraft.vcompat.provider.VersionControl");
            if (provider != null && control != null) {
                Object providerObj = provider.getMethod("get").invoke(null);
                control.getMethod("shutdown").invoke(provider.getMethod("getControl").invoke(providerObj));
            }
        } catch (Exception e) {
            // Ignore
        }
        state = State.NONE;
    }

    private Class<?> getClass(String name) {
        try {
            return Class.forName(name, false, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public boolean isRegistered(String id) {
        read.lock();
        try {
            return apps.containsKey(id);
        } finally {
            read.unlock();
        }
    }

    public int getAmount() {
        read.lock();
        try {
            return apps.size();
        } finally {
            read.unlock();
        }
    }

    public int getVersion() {
        read.lock();
        try {
            return version;
        } finally {
            read.unlock();
        }
    }

    public State getState() {
        read.lock();
        try {
            return state;
        } finally {
            read.unlock();
        }
    }

    public void setAuthenticator(Authenticator authenticator) {
        write.lock();
        try {
            this.authenticator = authenticator;
        } finally {
            write.unlock();
        }
    }

    private void setVersion(int version) {
        write.lock();
        try {
            this.version = version;
        } finally {
            write.unlock();
        }
    }

    private void setState(State state) {
        write.lock();
        try {
            this.state = state;
        } finally {
            write.unlock();
        }
    }

    private void setNoConnection() {
        write.lock();
        try {
            state = State.FAILED;
            reason = Reason.NO_CONNECTION;
            message = "Unable to connect to Github!";
        } finally {
            write.unlock();
        }
    }

    private void setFailed(Throwable exp) {
        write.lock();
        try {
            state = State.FAILED;
            reason = Reason.UNKNOWN;
            message = exp.getMessage();
            exception = exp;
        } finally {
            write.unlock();
        }
    }

    public void run(CompatApp app) {
        if (app.state == AppState.KNOWN) {
            app.state = AppState.STARTED;
        }
        if (getState() != State.NONE || getAmount() == 0) {
            if (getState() != State.UPDATING) {
                updateAll();
            }
            return;
        }
        setState(State.UPDATING);
        int tmpVersion = readCurrentVersion();
        if (tmpVersion == -1) {
            updateAll();
            return;
        }
        setVersion(tmpVersion);
        if (!readGithubVersion() && tmpVersion == 0) {
            updateAll();
            return;
        }
        if (!isUpToDate()) {
            downloadNewVersion();
            return;
        }
        setState(State.SUCCESS);
        loadCompatLib();
        updateAll();
    }

    private void downloadNewVersion() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            String version;
            Path jarFile;
            read.lock();
            try {
                version = githubVersion;
                jarFile = file;
            } finally {
                read.unlock();
            }
            try {
                String url = getAssetUrl(version);
                if (url == null) {
                    read.lock();
                    try {
                        version = exactVersion;
                    } finally {
                        read.unlock();
                    }
                    if (version == null) {
                        setFailed(new NullPointerException("Couldn't obtain release jar"));
                        updateAll();
                        return;
                    }
                    loadCompatLib();
                    setState(State.SUCCESS);
                    updateAll();
                    return;
                }
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                if (authenticator != null) {
                    authenticator.authenticate(connection);
                }
                connection.connect();
                try (InputStream input = connection.getInputStream()) {
                    Files.deleteIfExists(jarFile);
                    if (!Files.exists(directory)) {
                        Files.createDirectories(directory);
                    }
                    int length = connection.getContentLength();
                    int prev = -1;
                    int perc = 0;
                    int current = 0;
                    try (OutputStream output = new FileOutputStream(jarFile.toFile())) {
                        while (current != length) {
                            output.write(input.read());
                            current += 1;
                            perc = (int) ((current * 10D) / length);
                            if (prev != perc) {
                                prev = perc;
                                System.out.println("Updating vCompat... (" + current + " / " + length + ")");
                            }
                        }
                    }
                }
                connection.disconnect();
                setState(State.SUCCESS);
                System.out.println("Updated vCompat successfully!");
                write.lock();
                try {
                    exactVersion = version;
                } finally {
                    write.unlock();
                }
            } catch (IOException exp) {
                setFailed(exp);
                updateAll();
                return;
            }
            loadCompatLib();
            updateAll();
        });
        executor.submit(() -> executor.shutdown());
    }

    private String getAssetUrl(String version) {
        try {
            Request request = new Request(RequestType.GET).header("Accept", "application/vnd.github.v3+json");
            if (authenticator != null) {
                authenticator.authenticate(request);
            }
            Response response = request.execute(String.format(GITHUB_RELEASE, 'v' + version), StandardContentType.JSON);
            if (response.getCode() != 200) {
                return null;
            }
            JsonObject object = (JsonObject) response.getResponseAsJson();
            JsonArray array = (JsonArray) object.get("assets");
            for (JsonValue<?> value : array) {
                JsonObject asset = (JsonObject) value;
                String name = (String) asset.get("name").getValue();
                if (name.startsWith("vcompat") && name.endsWith(".jar")) {
                    return (String) asset.get("browser_download_url").getValue();
                }
            }
            return null;
        } catch (IOException exp) {
            setFailed(exp);
            updateAll();
            return null;
        }
    }

    private void loadCompatLib() {
        Path current;
        read.lock();
        try {
            current = file;
        } finally {
            read.unlock();
        }
        URL url = null;
        try {
            url = current.toUri().toURL();
        } catch (Exception exp) {
            setFailed(exp);
            return;
        }
        URLClassLoader loader = getUrlClassLoader();
        try {
            invoke(loader, findMethod(URLClassLoader.class, "addURL", URL.class), url);
        } catch (Throwable ignore) {
            setFailed(ignore);
            return;
        }
    }

    private void invoke(Object target, Method method, Object... arguments) throws Throwable {
        Lookup lookup = (Lookup) getStaticValue(Lookup.class, "IMPL_LOOKUP");
        Object[] args = new Object[arguments.length + 1];
        args[0] = target;
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        lookup.unreflect(method).invokeWithArguments(args);
    }

    private Object getStaticValue(Class<?> clazz, String field) throws Throwable {
        Field valueField = findField(clazz, field);
        Unsafe unsafe = getUnsafe();
        Object base = unsafe.staticFieldBase(valueField);
        long offset = unsafe.staticFieldOffset(valueField);
        return unsafe.getObjectVolatile(base, offset);
    }

    private Unsafe getUnsafe() throws NoSuchFieldException, IllegalAccessException {
        if (unsafe != null) {
            return unsafe;
        }
        Field unsafeField = findField(Unsafe.class, "theUnsafe");
        unsafeField.setAccessible(true);
        return unsafe = (Unsafe) unsafeField.get(null);
    }

    private Field findField(Class<?> target, String field) throws NoSuchFieldException {
        try {
            return target.getField(field);
        } catch (NoSuchFieldException i) {
            return target.getDeclaredField(field);
        }
    }

    private Method findMethod(Class<?> target, String method, Class<?>... classes) throws NoSuchMethodException {
        try {
            return target.getMethod(method, classes);
        } catch (NoSuchMethodException i) {
            return target.getDeclaredMethod(method, classes);
        }
    }

    private URLClassLoader getUrlClassLoader() {
        if (urlClassLoader != null) {
            return urlClassLoader;
        }
        ArrayList<ClassLoader> list = new ArrayList<ClassLoader>();
        for (ClassLoader classLoader : classLoaders) {
            // Try parents first
            collectParents(list, classLoader);
            for (ClassLoader parent : list) {
                if (!(parent instanceof URLClassLoader)) {
                    continue;
                }
                return urlClassLoader = (URLClassLoader) parent;
            }
            if (classLoader instanceof URLClassLoader) {
                return urlClassLoader = (URLClassLoader) classLoader;
            }
            list.clear();
        }
        throw new IllegalStateException("Could not find URLClassLoader");
    }

    private void collectParents(Collection<ClassLoader> parents, ClassLoader loader) {
        if (loader.getParent() == null) {
            return;
        }
        collectParents(parents, loader.getParent());
        parents.add(loader.getParent());
    }

    private int readCurrentVersion() {
        File jarFile;
        read.lock();
        try {
            jarFile = file.toFile();
        } finally {
            read.unlock();
        }
        if (!jarFile.exists()) {
            return 0;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("META-INF/maven/net.sourcewriters.minecraft/vcompat/pom.properties");
            if (entry == null) {
                return 0;
            }
            int version = 0;
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(jar.getInputStream(entry)))) {
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("=")) {
                        continue;
                    }
                    String[] parts = line.split("=", 2);
                    if (parts[0].equals("version")) {
                        line = parts[1];
                        version = Integer.valueOf(parts[1].split("\\.", 2)[0]);
                        break;
                    }
                }
            }
            write.lock();
            try {
                exactVersion = line;
            } finally {
                write.unlock();
            }
            return version;
        } catch (IOException exp) {
            setFailed(exp);
            return -1;
        }
    }

    private boolean readGithubVersion() {
        Request request = new Request(RequestType.GET).modifyUrl(true).header("Accept", "application/vnd.github.v3+json");
        if (authenticator != null) {
            authenticator.authenticate(request);
        }
        int requested = getRequested(false);
        int previous = 0;
        int page = 0;
        boolean found = false;
        try {
            while (requested != previous && !found) {
                Response response = request.clearParameters().parameter("per_page", "40").parameter("page", new JsonInteger(page++))
                    .modifyUrl(true).execute(GITHUB_TAGS, StandardContentType.URL_ENCODED);
                if (response.getCode() == 404) {
                    previous = requested;
                    requested = getRequested(true);
                    continue;
                }
                JsonValue<?> rawValue = response.getResponseAsJson();
                if (response.getCode() == 403) {
                    setFailed(new IllegalStateException((String) ((JsonObject) rawValue).get("message").getValue()));
                    return false;
                }
                JsonArray array = (JsonArray) rawValue;
                if (array.isEmpty()) {
                    previous = requested;
                    requested = getRequested(true);
                    continue;
                }
                for (JsonValue<?> value : array) {
                    if (value.getType() != ValueType.OBJECT) {
                        continue;
                    }
                    JsonObject object = (JsonObject) value;
                    if (!object.has("name", ValueType.STRING)) {
                        continue;
                    }
                    String version = ((String) object.get("name").getValue()).substring(1);
                    if (requested == Integer.valueOf(version.split("\\.", 2)[0])) {
                        write.lock();
                        try {
                            githubVersion = version;
                        } finally {
                            write.unlock();
                        }
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                if (getVersion() == 0) {
                    setFailed(new IOException("Failed to find compatible version on Github!"));
                }
                return false;
            }
            setVersion(requested);
        } catch (SocketTimeoutException timeout) {
            setNoConnection();
            return false;
        } catch (IOException exp) {
            setFailed(exp);
            return false;
        }
        return true;
    }

    private int getRequested(boolean next) {
        int tmp;
        read.lock();
        try {
            tmp = requested;
            if (!next) {
                return tmp;
            }
            int nextLowest = tmp;
            for (CompatApp app : apps.values()) {
                int version = app.getTargetVersion();
                if (version > nextLowest && version < tmp) {
                    nextLowest = version;
                }
            }
            tmp = nextLowest;
        } finally {
            read.unlock();
        }
        write.lock();
        try {
            requested = tmp;
        } finally {
            write.unlock();
        }
        return tmp;
    }

    private boolean isUpToDate() {
        read.lock();
        try {
            if (githubVersion == null) {
                return true;
            }
            return githubVersion.equals(exactVersion);
        } finally {
            read.unlock();
        }
    }

    private void updateAll() {
        read.lock();
        try {
            for (CompatApp app : apps.values()) {
                if (app.state != AppState.STARTED) {
                    continue;
                }
                if (state == State.FAILED) {
                    app.onFailed(reason, message, exception);
                    app.state = AppState.FAILED;
                    continue;
                }
                app.onReady();
                app.state = AppState.RUNNING;
            }
        } finally {
            read.unlock();
        }
    }

}
