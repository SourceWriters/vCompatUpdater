package net.sourcewriters.minecraft.vcompat.updater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

public final class CompatUpdater {

    public static final CompatUpdater INSTANCE = new CompatUpdater();

    private static final String GITHUB_RELEASE = "https://api.github.com/repos/SourceWriters/vCompat/releases/tags/%s";
    private static final String GITHUB_TAGS = "https://api.github.com/repos/SourceWriters/vCompat/tags";

    private final HashMap<String, CompatApp> apps = new HashMap<>();
    private final Lock read, write;

    private final Path directory = Paths.get("plugins/vCompat");
    private final Path file = directory.resolve("vCompat.jar");

    private String githubVersion;
    private String exactVersion;

    private State state = State.NONE;
    private int version = 0;
    private int requested = 0;

    private Reason reason;
    private String message;

    private Authenticator authenticator;
    private ClassLoader classloader;

    private CompatUpdater() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        read = lock.readLock();
        write = lock.writeLock();
    }

    public ClassLoader getClassLoader() {
        return classloader == null ? ClassLoader.getSystemClassLoader() : classloader;
    }

    public void register(CompatApp app) {
        String id = app.getId();
        if (id == null || isRegistered(id)) {
            app.onFailed(Reason.ALREADY_REGISTERED, "Your App '" + id + "' is already registered!");
            return;
        }
        write.lock();
        try {
            apps.put(id, app);
            int tmp = app.getTargetVersion();
            if (requested < tmp) {
                requested = tmp;
            }
        } finally {
            write.unlock();
        }
        read.lock();
        try {
            State state = getState();
            if (state == State.SUCCESS) {
                if (app.getTargetVersion() == version) {
                    app.onReady();
                    return;
                }
                app.onFailed(Reason.INCOMPATIBLE, "The version of vCompat that is installed is incompatible with the app '" + id + "'!");
                return;
            }
            if (state == State.FAILED) {
                app.onFailed(reason, message);
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
            return;
        }
        write.lock();
        try {
            apps.remove(id);
        } finally {
            write.unlock();
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

    private void setFailed(Exception exp) {
        write.lock();
        try {
            state = State.FAILED;
            reason = Reason.UNKNOWN;
            message = exp.getMessage();
        } finally {
            write.unlock();
        }
    }

    public void run() {
        if (getState() != State.NONE || getAmount() == 0) {
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
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        try {
            if (!(loader instanceof URLClassLoader)) {
                loader = getClass().getClassLoader();
            }
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(loader, current.toUri().toURL());
        } catch (Exception exp) {
            setFailed(exp);
            return;
        }
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
                if (state == State.FAILED) {
                    app.onFailed(reason, message);
                    continue;
                }
                app.onReady();
            }
        } finally {
            read.unlock();
        }
    }

}
