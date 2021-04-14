package net.sourcewriters.minecraft.vcompat.updater;

final class Informer {

    private Thread thread = new Thread(() -> send());

    int current = 0;
    int length = 0;

    boolean running = false;

    public void start() {
        running = true;
        thread.setDaemon(true);
        thread.setName("CompatWorker - 1");
        thread.start();
    }

    private void send() {
        while (running) {
            System.out.println("Updating vCompat... (" + current + "b / " + length + "b)");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }
    }

}
