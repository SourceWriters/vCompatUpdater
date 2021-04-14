package net.sourcewriters.minecraft.vcompat.updater;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.syntaxphoenix.syntaxapi.json.JsonArray;
import com.syntaxphoenix.syntaxapi.json.JsonObject;
import com.syntaxphoenix.syntaxapi.json.JsonValue;
import com.syntaxphoenix.syntaxapi.json.ValueType;
import com.syntaxphoenix.syntaxapi.json.value.JsonInteger;
import com.syntaxphoenix.syntaxapi.net.http.Request;
import com.syntaxphoenix.syntaxapi.net.http.RequestType;
import com.syntaxphoenix.syntaxapi.net.http.Response;
import com.syntaxphoenix.syntaxapi.net.http.StandardContentType;
import com.syntaxphoenix.syntaxapi.reflection.AbstractReflect;
import com.syntaxphoenix.syntaxapi.reflection.Reflect;
import com.syntaxphoenix.syntaxapi.utils.java.Files;

public final class CompatUpdater {

    public static final CompatUpdater INSTANCE = new CompatUpdater();

    private static final String GITHUB_RELEASE = "https://api.github.com/repos/SourceWriters/vCompat/releases/tags/%s";
    private static final String GITHUB_TAGS = "https://api.github.com/repos/SourceWriters/vCompat/tags";

    private final HashMap<String, CompatApp> apps = new HashMap<>();
    private final Lock read, write;

    private final File file = new File("plugins/vCompat", "vCompat.jar");

    private String githubVersion;
    private String exactVersion;

    private State state = State.NONE;
    private int version = 0;
    private int requested = 0;

    private Reason reason;
    private String message;

    private CompatUpdater() {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        read = lock.readLock();
        write = lock.writeLock();
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
        String version;
        File jarFile;
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
                updateAll();
                return;
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.addRequestProperty("Accept", "application/octet-stream");
            connection.connect();
            InputStream stream = connection.getInputStream();
            int length = connection.getContentLength();
            int current = 0;
            FileOutputStream output = new FileOutputStream(jarFile);
            if (jarFile.exists()) {
                jarFile.delete();
            }
            Files.createFile(jarFile);
            while (current != length) {
                int value = length - current;
                if (value > 64) {
                    value = 64;
                }
                current += value;
                byte[] array = new byte[value];
                stream.read(array);
                output.write(array);
            }
            output.flush();
            output.close();
            stream.close();
            connection.disconnect();
            setState(State.SUCCESS);
            write.lock();
            try {
                exactVersion = version;
            } finally {
                write.unlock();
            }
        } catch (IOException exp) {
            setFailed(exp);
        }
        loadCompatLib();
        updateAll();
    }

    private String getAssetUrl(String version) throws IOException {
        Response response = new Request(RequestType.GET).header("Accept", "application/vnd.github.v3+json")
            .execute(String.format(GITHUB_RELEASE, 'v' + version), StandardContentType.JSON);
        if (response.getCode() != 200) {
            return null;
        }
        JsonObject object = (JsonObject) response.getResponseAsJson();
        if (!object.has("assets", ValueType.ARRAY)) {
            return null;
        }
        JsonArray array = (JsonArray) object.get("assets");
        if (array.size() == 0) {
            return null;
        }
        for (JsonValue<?> value : array) {
            if (value.getType() != ValueType.OBJECT) {
                continue;
            }
            JsonObject asset = (JsonObject) value;
            if (!asset.has("name", ValueType.STRING)) {
                continue;
            }
            String name = (String) asset.get("name").getValue();
            if (name.startsWith("vcompat") && name.endsWith(".jar") && asset.has("url", ValueType.STRING)) {
                return (String) asset.get("url").getValue();
            }
        }
        return null;
    }

    private void loadCompatLib() {
        ClassLoader loader = findClassLoader();
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        try {
            File current;
            read.lock();
            try {
                current = file;
            } finally {
                read.unlock();
            }
            AbstractReflect reflect = new Reflect(URLClassLoader.class).searchMethod("add", "addUrl", URL.class);
            if (!reflect.getOwner().isInstance(loader)) {
                throw new IllegalArgumentException("Can't load vCompat with '" + loader.getClass().getName() + "' Classloader!");
            }
            reflect.execute(loader, "add", current.toURI().toURL());
        } catch (Exception exp) {
            setFailed(exp);
            return;
        }
    }

    private ClassLoader findClassLoader() {
        ClassLoader loader = null;
        if (getAmount() == 0) {
            return loader;
        }
        read.lock();
        try {
            for (CompatApp app : apps.values()) {
                ClassLoader tmp = app.getClass().getClassLoader().getParent();
                if (tmp != loader) {
                    loader = tmp;
                }
            }
        } finally {
            read.unlock();
        }
        return loader;
    }

    private int readCurrentVersion() {
        if (!file.exists()) {
            return 0;
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("META-INF/maven/net.sourcewriters.minecraft/vcompat/pom.properties");
            if (entry == null) {
                return 0;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)));
            String line;
            int version = 0;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("=")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts[0].equals("version")) {
                    version = Integer.valueOf(parts[1].split("\\.", 2)[0]);
                    break;
                }
            }
            reader.close();
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
        int requested = getRequested(false);
        int previous = 0;
        int page = 0;
        boolean found = false;
        try {
            while (requested != previous || !found) {
                Response response = request.clearParameters().parameter("per_page", "20").parameter("page", new JsonInteger(page++))
                    .execute(GITHUB_TAGS, StandardContentType.JSON);
                if (response.getCode() == 404) {
                    previous = requested;
                    requested = getRequested(true);
                    continue;
                }
                JsonArray array = (JsonArray) response.getResponseAsJson();
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
