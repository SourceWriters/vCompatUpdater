package net.sourcewriters.minecraft.vcompat.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.lauriichan.laylib.json.IJson;
import me.lauriichan.laylib.json.JsonObject;
import me.lauriichan.laylib.json.io.JsonParser;

final class Http {

    public static final class Response<T> {

        public static final IResponseParser<IJson<?>> JSON = JsonParser::fromBytes;

        public static final IResponseParser<JsonObject> JSON_OBJECT = (data) -> {
            IJson<?> json = JsonParser.fromBytes(data);
            if (!json.isObject()) {
                throw new IllegalStateException("Expected json object but got: " + json.type().name());
            }
            return json.asJsonObject();
        };

        @FunctionalInterface
        public static interface IResponseParser<E> {
            E parse(byte[] data) throws Exception;
        }

        private final int code;
        private final T data;
        private final Map<String, List<String>> headers;

        public Response(int code, T data, Map<String, List<String>> headers) {
            this.code = code;
            this.data = data;
            this.headers = headers;
        }

        public int code() {
            return code;
        }

        public T data() {
            return data;
        }

        public boolean hasHeaders() {
            return !headers.isEmpty();
        }

        public boolean hasHeader(String key) {
            List<String> list = headers.get(key);
            return list != null && !list.isEmpty();
        }

        public List<String> getHeader(String key) {
            return headers.get(key);
        }

        public String getHeaderFirst(String key) {
            return getHeaderFirstOrDefault(key, null);
        }

        public String getHeaderFirstOrDefault(String key, String fallback) {
            List<String> list = headers.get(key);
            if (list == null || list.isEmpty()) {
                return fallback;
            }
            return list.get(0);
        }

    }

    public static final class Request {

        private final HashMap<String, String> headers = new HashMap<>();
        private final HashMap<String, String> params = new HashMap<>();

        private volatile Authenticator authenticator;

        private volatile String url;
        private volatile String method = "GET";

        private Request() {
            headers.put("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36");
            headers.put("Accept", "application/vnd.github.v3+json;application/json");
        }

        public Authenticator authenticator() {
            return authenticator;
        }

        public Request authenticator(Authenticator authenticator) {
            this.authenticator = authenticator;
            return this;
        }

        public String method() {
            return method;
        }

        public Request method(String method) {
            this.method = method;
            return this;
        }

        public String url() {
            return url;
        }

        public Request url(String url) {
            this.url = url;
            return this;
        }

        public String header(String key) {
            return headers.get(key);
        }

        public String headerOrDefault(String key, String fallback) {
            return headers.getOrDefault(key, fallback);
        }

        public Request header(String key, Object value) {
            if (value == null) {
                headers.remove(key);
                return this;
            }
            headers.put(key, value.toString());
            return this;
        }

        public Request contentType(String value) {
            return header("Content-Type", value);
        }

        public Request removeHeader(String key) {
            headers.remove(key);
            return this;
        }

        public Request clearHeaders() {
            headers.clear();
            return this;
        }

        public String param(String key) {
            return params.get(key);
        }

        public String paramOrDefault(String key, String fallback) {
            return params.getOrDefault(key, fallback);
        }

        public Request param(String key, Object value) {
            if (value == null) {
                params.remove(key);
                return this;
            }
            params.put(key, value.toString());
            return this;
        }

        public Request removeParam(String key) {
            params.remove(key);
            return this;
        }

        public Request clearParams() {
            params.clear();
            return this;
        }

        public <T> Response<T> call(Response.IResponseParser<T> parser) throws Exception {
            return call(parser, null);
        }

        public <T> Response<T> call(Response.IResponseParser<T> parser, byte[] content) throws Exception {
            String url = this.url;
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Invalid url: " + url);
            }

            if (!params.isEmpty()) {
                UrlEncoder encoder = UrlEncoder.UTF_8;
                StringBuilder builder = new StringBuilder();
                for (HashMap.Entry<String, String> entry : params.entrySet()) {
                    if (entry.getValue() == null || entry.getValue().isBlank()) {
                        continue;
                    }
                    builder.append(encoder.encode(entry.getKey())).append('=').append(encoder.encode(entry.getValue())).append('&');
                }
                if (builder.length() != 0) {
                    url = builder.insert(0, '?').insert(0, url).substring(0, builder.length() - 1);
                }
            }

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            for (HashMap.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (authenticator != null) {
                authenticator.authenticate(connection);
            }
            if (content != null && content.length > 0) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(content.length);
            }
            connection.connect();

            if (connection.getDoOutput()) {
                OutputStream streamOut = connection.getOutputStream();
                streamOut.write(content);
                streamOut.flush();
                streamOut.close();
            }

            InputStream streamIn;
            try {
                streamIn = connection.getInputStream();
                if (streamIn == null) {
                    streamIn = connection.getErrorStream();
                }
            } catch (IOException ignore) {
                streamIn = connection.getErrorStream();
            }
            byte[] data = null;
            if (streamIn != null && streamIn.available() != 0) {
                try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[1024];
                    int length;
                    while ((length = streamIn.read(buf, 0, buf.length)) != -1) {
                        buffer.write(buf, 0, length);
                    }
                    buffer.flush();
                    data = buffer.toByteArray();
                }
            }
            return new Response<>(connection.getResponseCode(), data != null && data.length != 0 ? parser.parse(data) : null,
                connection.getHeaderFields());
        }

    }

    public static Request create() {
        return new Request();
    }

}
