package mx.vicko.permadeath;

import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

public final class DayManager {
    private final PermadeathPlugin plugin;

    public DayManager(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        FileConfiguration config = plugin.getConfig();
        if (config.getLong("start-epoch-millis", 0L) <= 0L) {
            config.set("start-epoch-millis", System.currentTimeMillis());
            plugin.saveConfig();
        }
    }

    public int getCurrentDay() {
        int manualDay = plugin.getConfig().getInt("manual-day-override", 0);
        if (manualDay > 0) return manualDay;

        long started = plugin.getConfig().getLong("start-epoch-millis");
        long end = plugin.getConfig().getBoolean("calendar.paused", false)
                ? plugin.getConfig().getLong("calendar.paused-at-epoch-millis", System.currentTimeMillis())
                : System.currentTimeMillis();
        long elapsed = Math.max(0L, end - started);
        return (int) Duration.ofMillis(elapsed).toDays() + 1;
    }

    public void setManualDay(int day) {
        plugin.getConfig().set("manual-day-override", day);
        plugin.saveConfig();
    }

    public void useRealCalendar() {
        plugin.getConfig().set("manual-day-override", 0);
        plugin.saveConfig();
    }

    public boolean isPaused() {
        return plugin.getConfig().getBoolean("calendar.paused", false);
    }

    public void pause() {
        if (isPaused()) return;
        plugin.getConfig().set("calendar.paused", true);
        plugin.getConfig().set("calendar.paused-at-epoch-millis", System.currentTimeMillis());
        plugin.saveConfig();
    }

    public void resume() {
        if (!isPaused()) return;
        long pausedAt = plugin.getConfig().getLong("calendar.paused-at-epoch-millis", System.currentTimeMillis());
        long elapsedPause = Math.max(0L, System.currentTimeMillis() - pausedAt);
        plugin.getConfig().set("start-epoch-millis", plugin.getConfig().getLong("start-epoch-millis") + elapsedPause);
        plugin.getConfig().set("calendar.paused", false);
        plugin.getConfig().set("calendar.paused-at-epoch-millis", 0L);
        plugin.saveConfig();
    }
}
