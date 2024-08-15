package net.sourcewriters.minecraft.vcompat.updater;

import java.net.HttpURLConnection;

public interface Authenticator {
    
    void authenticate(HttpURLConnection connection);

}
