package net.sourcewriters.minecraft.vcompat.updater;

public abstract class CompatApp {
    
    private final String id;
    private final int version;
    
    public CompatApp(String id, int version) {
        this.id = id;
        this.version = version;
        CompatUpdater.INSTANCE.register(this);
    }
    
    public final String getId() {
        return id;
    }
    
    public final int getTargetVersion() {
        return version;
    }
    
    public final void start() {
        CompatUpdater.INSTANCE.run();
    }
    
    public final void stop() {
        CompatUpdater.INSTANCE.unregister(this);
    }
    
    protected void onFailed(Reason reason, String message) {}
    
    protected void onReady() {}
    
    protected void onShutdown() {}
    
}
