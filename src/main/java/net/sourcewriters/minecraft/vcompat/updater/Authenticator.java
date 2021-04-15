package net.sourcewriters.minecraft.vcompat.updater;

import java.net.HttpURLConnection;

import com.syntaxphoenix.syntaxapi.net.http.Request;

public interface Authenticator {
    
    void authenticate(Request request);
    
    void authenticate(HttpURLConnection connection);

}
