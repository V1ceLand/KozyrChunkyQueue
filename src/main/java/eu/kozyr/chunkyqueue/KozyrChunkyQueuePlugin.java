package eu.kozyr.chunkyqueue;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.GenerationTask;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class KozyrChunkyQueuePlugin extends JavaPlugin implements Listener {

    private record QueueEntry(String world, Integer radius, Double centerX, Double centerZ) {}

    private final List<QueueEntry> queue = new ArrayList<>();
    private File stateFile;

    private String onPlayersOnlineMode = "pause";
    private int throttleWorkerThreads = 2;
    private boolean autoStart = true;

    private int queueIndex = 0;
    private boolean stopped = false;

    // Состояние на время жизни процесса, не персистится - переопределяется живой сверкой с Chunky.
    private boolean pausedByUs = false;
    private boolean taskConfirmedRunning = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.txt");
        loadState();
        loadConfig();

        Chunky chunky = ChunkyProvider.get();
        chunky.getEventBus().subscribe(GenerationCompleteEvent.class, this::onGenerationComplete);

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("chunkyqueue").setExecutor(this::onCommand);

        applyWorkerThreadsThrottle();

        Bukkit.getScheduler().runTaskLater(this, this::tick, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 200L, 200L);

        getLogger().info("KozyrChunkyQueue enabled. В очереди " + queue.size() + " мир(ов), индекс " + queueIndex + ".");
    }

    // ================================================================
    // Конфиг и состояние
    // ================================================================

    private void loadConfig() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        onPlayersOnlineMode = cfg.getString("on-players-online", "pause");
        throttleWorkerThreads = cfg.getInt("throttle-worker-threads", 2);
        autoStart = cfg.getBoolean("auto-start", true);

        queue.clear();
        boolean simpleEnabled = cfg.getBoolean("simple-generation.enabled", false);
        if (simpleEnabled) {
            for (String world : splitCsv(cfg.getString("simple-generation.order", ""))) {
                String path = "simple-generation.worlds." + world;
                int radius = cfg.getInt(path + ".radius", 15000);
                double[] center = parseCenter(cfg.getString(path + ".center", "0 0"));
                queue.add(new QueueEntry(world, radius, center[0], center[1]));
            }
        } else {
            for (String world : splitCsv(cfg.getString("queue-order", ""))) {
                queue.add(new QueueEntry(world, null, null, null));
            }
        }

        if (queueIndex > queue.size()) {
            queueIndex = queue.size();
            saveState();
        }
        taskConfirmedRunning = false;
    }

    private static List<String> splitCsv(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static double[] parseCenter(String raw) {
        String[] parts = raw.trim().split("\\s+");
        try {
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception e) {
            return new double[]{0, 0};
        }
    }

    private void loadState() {
        if (!stateFile.exists()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(stateFile.toPath(), StandardCharsets.UTF_8);
            queueIndex = lines.isEmpty() ? 0 : Integer.parseInt(lines.get(0).trim());
            stopped = lines.size() > 1 && Boolean.parseBoolean(lines.get(1).trim());
        } catch (Exception e) {
            queueIndex = 0;
            stopped = false;
        }
    }

    private void saveState() {
        try {
            Files.writeString(stateFile.toPath(), queueIndex + "\n" + stopped, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить state.txt: " + e.getMessage());
        }
    }

    private QueueEntry currentEntry() {
        return queueIndex < queue.size() ? queue.get(queueIndex) : null;
    }

    // ================================================================
    // Троттлинг потоков (paper-global.yml, требует рестарта сервера)
    // ================================================================

    private void applyWorkerThreadsThrottle() {
        if (!"throttle".equalsIgnoreCase(onPlayersOnlineMode)) {
            return;
        }
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        File paperGlobal = new File(serverRoot, "config/paper-global.yml");
        if (!paperGlobal.exists()) {
            getLogger().warning("config/paper-global.yml не найден - похоже, сервер не на Paper-совместимом ядре. Режим throttle недоступен.");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(paperGlobal.toPath(), StandardCharsets.UTF_8);
            boolean inChunkSystem = false;
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.equals("chunk-system:")) {
                    inChunkSystem = true;
                    continue;
                }
                if (!inChunkSystem) {
                    continue;
                }
                if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                    inChunkSystem = false;
                    continue;
                }
                if (trimmed.startsWith("worker-threads:")) {
                    String currentValue = trimmed.substring("worker-threads:".length()).trim();
                    if (!currentValue.equals(String.valueOf(throttleWorkerThreads))) {
                        String indent = line.substring(0, line.indexOf("worker-threads:"));
                        lines.set(i, indent + "worker-threads: " + throttleWorkerThreads);
                        changed = true;
                    }
                    break;
                }
            }
            if (changed) {
                Files.write(paperGlobal.toPath(), lines, StandardCharsets.UTF_8);
                getLogger().warning("worker-threads в paper-global.yml изменён на " + throttleWorkerThreads
                        + ". Требуется рестарт сервера, чтобы это применилось.");
            }
        } catch (IOException e) {
            getLogger().warning("Не удалось поправить paper-global.yml: " + e.getMessage());
        }
    }

    // ================================================================
    // Пауза/продолжение по игрокам онлайн
    // ================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, this::updatePlayerGate);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTask(this, this::updatePlayerGate);
    }

    private void updatePlayerGate() {
        if (stopped || !"pause".equalsIgnoreCase(onPlayersOnlineMode)) {
            return;
        }
        QueueEntry entry = currentEntry();
        if (entry == null) {
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        if (online > 0 && !pausedByUs) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky pause");
            pausedByUs = true;
            getLogger().info("Игрок(и) на сервере - прогрузка \"" + entry.world() + "\" поставлена на паузу.");
        } else if (online == 0 && pausedByUs) {
            pausedByUs = false;
            getLogger().info("Сервер опустел - прогрузка \"" + entry.world() + "\" возобновляется.");
            startOrResume(entry);
        }
    }

    // ================================================================
    // Цепочка миров
    // ================================================================

    private void onGenerationComplete(GenerationCompleteEvent event) {
        QueueEntry expected = currentEntry();
        if (expected == null || !expected.world().equals(event.world())) {
            return;
        }
        // Chunky шлёт это событие при ЛЮБОМ завершении цикла генерации - в том числе при
        // паузе/остановке/рестарте сервера, а не только при реальных 100%. Различаем по
        // флагу "cancelled" в сохранённом Chunky/tasks/<world>.properties: Chunky сам
        // пишет туда true при настоящем завершении (или /chunky cancel) и false при
        // паузе/стопе (когда генерацию можно продолжить через "chunky continue").
        // Полагаться на проценты из GenerationProgressEvent нельзя - на быстрых/маленьких
        // генерациях последний прогресс успевает прийти уже ПОСЛЕ этого события (гонка
        // потоков в самом Chunky), из-за чего свежезавершённый мир ошибочно считался бы
        // недогруженным.
        if (isResumable(event.world())) {
            taskConfirmedRunning = false;
            return;
        }
        getLogger().info("Прогрузка \"" + event.world() + "\" завершена.");
        queueIndex++;
        taskConfirmedRunning = false;
        saveState();
        QueueEntry next = currentEntry();
        if (next != null) {
            if (!stopped && autoStart) {
                startOrResume(next);
            }
        } else {
            getLogger().info("Очередь автоматической прогрузки миров полностью пройдена.");
        }
    }

    private boolean isResumable(String world) {
        File props = taskPropertiesFile(world);
        if (!props.exists()) {
            return false;
        }
        try (InputStream in = new FileInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            return !Boolean.parseBoolean(p.getProperty("cancelled", "true"));
        } catch (IOException e) {
            return false;
        }
    }

    private void startOrResume(QueueEntry entry) {
        Bukkit.getScheduler().runTask(this, () -> {
            if (isResumable(entry.world())) {
                getLogger().info("Продолжаю ранее начатую прогрузку \"" + entry.world() + "\".");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky continue " + entry.world());
            } else {
                getLogger().info("Начинаю новую прогрузку \"" + entry.world() + "\""
                        + (entry.radius() != null ? " (радиус " + entry.radius() + ")" : " (настройки Chunky по умолчанию для мира)") + ".");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky world " + entry.world());
                if (entry.radius() != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky center " + fmt(entry.centerX()) + " " + fmt(entry.centerZ()));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky radius " + entry.radius());
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky start");
            }
        });
    }

    private File taskPropertiesFile(String world) {
        return new File(getDataFolder().getParentFile(), "Chunky/tasks/" + world + ".properties");
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ================================================================
    // Периодическая сверка с реальным состоянием Chunky
    // ================================================================

    private void tick() {
        updatePlayerGate();
        watchLiveState();
    }

    private void watchLiveState() {
        if (stopped) {
            return;
        }
        QueueEntry entry = currentEntry();
        if (entry == null) {
            return;
        }
        Chunky chunky = ChunkyProvider.get();
        GenerationTask task = chunky.getGenerationTasks().get(entry.world());

        if (task != null) {
            taskConfirmedRunning = true;
            if (entry.radius() != null) {
                Selection sel = task.getSelection();
                if (Math.round(sel.radiusX()) != entry.radius()
                        || Math.round(sel.centerX()) != Math.round(entry.centerX())
                        || Math.round(sel.centerZ()) != Math.round(entry.centerZ())) {
                    getLogger().warning("Параметры активной генерации \"" + entry.world()
                            + "\" отличаются от config.yml (кто-то изменил их напрямую через /chunky). "
                            + "Аддон это не переопределяет.");
                }
            }
            return;
        }

        if (pausedByUs) {
            return;
        }

        if (taskConfirmedRunning) {
            taskConfirmedRunning = false;
            stopped = true;
            saveState();
            String message = "Генерация \"" + entry.world() + "\" остановлена не через аддон "
                    + "(например /chunky cancel или /chunky stop). Очередь поставлена на паузу - "
                    + "используй /chunkyqueue resume, чтобы продолжить.";
            getLogger().warning(message);
            notifyOps("§c[ChunkyQueue] " + message);
            return;
        }

        if (autoStart) {
            startOrResume(entry);
        }
    }

    private void notifyOps(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage(message);
            }
        }
    }

    // ================================================================
    // Команда /chunkyqueue
    // ================================================================

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        boolean isAdminSub = !sub.equals("status");
        String required = isAdminSub ? "kozyrchunkyqueue.admin" : "kozyrchunkyqueue.status";
        if (!sender.hasPermission(required)) {
            sender.sendMessage("§cУ тебя нет прав (" + required + ").");
            return true;
        }
        switch (sub) {
            case "stop" -> {
                stopped = true;
                pausedByUs = false;
                saveState();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "chunky pause");
                sender.sendMessage("§7[ChunkyQueue] §fОчередь остановлена. §7/chunkyqueue resume §fчтобы продолжить.");
            }
            case "resume" -> {
                stopped = false;
                saveState();
                sender.sendMessage("§7[ChunkyQueue] §fОчередь возобновлена.");
                tick();
            }
            case "reload" -> {
                loadConfig();
                applyWorkerThreadsThrottle();
                sender.sendMessage("§7[ChunkyQueue] §fКонфиг перечитан. §f" + queue.size()
                        + "§7 мир(ов) в очереди, текущий индекс §f" + queueIndex + "§7.");
                tick();
            }
            default -> {
                QueueEntry entry = currentEntry();
                sender.sendMessage("§7Очередь прогрузки: §f" + queueIndex + "§7/§f" + queue.size()
                        + (entry != null ? " §7(ожидаем: §f" + entry.world() + "§7)" : " §7(пройдена)"));
                sender.sendMessage("§7Статус: §f" + (stopped ? "остановлена вручную" : "активна")
                        + " §7| on-players-online: §f" + onPlayersOnlineMode
                        + (pausedByUs ? " §7(сейчас на паузе из-за игроков онлайн)" : ""));
            }
        }
        return true;
    }
}
