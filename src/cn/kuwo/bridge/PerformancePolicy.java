package cn.kuwo.bridge;

/** Clock-driven policies shared by Android hooks and deterministic host regressions. */
final class PerformancePolicy {
    static final long SUGGEST_DELAY_MS = 300;
    static final long HOME_FRESH_MS = 30000;
    static final class Generation {
        private long value;
        long advance() { return ++value; }
        long current() { return value; }
        boolean accepts(long token) { return token == value; }
    }
    static final class RefreshWindow {
        private long successAt = -1;
        private boolean running;
        private boolean forcePending;
        boolean start(long now, boolean force) {
            if (running) { forcePending |= force; return false; }
            if (!force && successAt >= 0 && now-successAt < HOME_FRESH_MS) return false;
            running = true;
            return true;
        }
        boolean complete(long now, boolean success) {
            running = false;
            if (success) successAt = now;
            boolean again = forcePending;
            forcePending = false;
            return again;
        }
    }
    static long optionalDelay(long now, long requested, long firstFrame, long lastInteraction) {
        long frameReady = firstFrame < 0 ? requested+15000 : firstFrame+2000;
        long ready = Math.min(requested+45000, Math.max(frameReady, lastInteraction+1200));
        return Math.max(0, ready-now);
    }
    static int[] artworkSize(int width, int height) {
        if (width <= 0 || height <= 0) return new int[] {640,640};
        double scale = Math.min(1.0, 1024.0/Math.max(width,height));
        return new int[] {Math.max(1,(int)(width*scale)), Math.max(1,(int)(height*scale))};
    }
    private PerformancePolicy() {}
}
