package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PermadeathCommand implements CommandExecutor, TabCompleter {
    private static final List<Integer> PHASES = List.of(10, 20, 25, 35, 40, 50, 60, 70, 80, 90, 100);
    private final PermadeathPlugin plugin;
    private final java.util.Map<String, PendingBank> pendingBank = new java.util.HashMap<>();

    public PermadeathCommand(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    /** Puerta debug: AMBOS permiso permadeath.debug (default false) Y config debug-commands:true. */
    private boolean isDebugAllowed(CommandSender sender) {
        boolean cfg;
        try {
            cfg = plugin.getConfig().getBoolean("debug-commands", false);
        } catch (RuntimeException exception) {
            cfg = false;
        }
        return cfg && sender.hasPermission("permadeath.debug");
    }

    /**
     * Helper puro para la matriz de fases visibles (sin Bukkit): filtra con el predicado dado.
     * La UI real pasa plugin::isPhaseActive (calendario comprimido + N&gt;=4), nunca día&gt;=fase legado.
     */
    static List<Integer> visibleActivePhases(java.util.function.IntPredicate isActive) {
        List<Integer> out = new ArrayList<>();
        for (int phase : PHASES) {
            if (isActive.test(phase)) {
                out.add(phase);
            }
        }
        return out;
    }

    private record PendingBank(String kind, String txId, long at) {
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("estado")) {
            showStatus(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("fases")) {
            showPhases(sender);
            return true;
        }
        if (sub.equals("temporada")) {
            showTemporada(sender);
            return true;
        }
        if (sub.equals("actos")) {
            showActos(sender);
            return true;
        }
        if (sub.equals("final")) {
            showFinal(sender);
            return true;
        }
        if (sub.equals("altar") || sub.equals("sacrificios") || sub.equals("sacrificio")) {
            handleAltar(sender, args);
            return true;
        }
        if (sub.equals("banco")) {
            handleBanco(sender, args);
            return true;
        }
        if (sub.equals("fosa")) {
            handleFosa(sender, args);
            return true;
        }
        if (sub.equals("revivir")) {
            revive(sender, args);
            return true;
        }
        if (sub.equals("revivirforzado")) {
            reviveForced(sender, args);
            return true;
        }
        if (sub.equals("reserva")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("salir")) {
                send(sender, plugin.reservaPodrida().exit(player));
            } else if (args.length == 1) {
                String result = plugin.reservaPodrida().enter(player);
                if (result != null) send(sender, result);
            } else {
                send(sender, "&cUso: /pd reserva [salir]");
            }
            return true;
        }
        if (!sender.hasPermission("permadeath.admin")) {
            send(sender, "&cNo tienes permiso para administrar la partida.");
            return true;
        }
        switch (sub) {
            case "dia" -> setDay(sender, args);
            case "auto" -> {
                plugin.days().useRealCalendar();
                send(sender, "Calendario real activo. Día actual: &e" + plugin.days().getCurrentDay());
            }
            case "pausa" -> {
                plugin.days().pause();
                send(sender, "Calendario pausado en el día &e" + plugin.days().getCurrentDay() + "&r.");
            }
            case "reanudar" -> {
                plugin.days().resume();
                send(sender, "Calendario reanudado. Día actual: &e" + plugin.days().getCurrentDay());
            }
            case "fase" -> setPhase(sender, args);
            case "muertos" -> showDeadPlayers(sender);
            case "ancla" -> giveAnchor(sender);
            case "nucleo" -> giveEclipseCore(sender);
            case "esquirla" -> giveEclipseShard(sender);
            case "sello" -> giveResurrectionSeal(sender);
            case "filtro" -> giveSporeFilter(sender, args);
            case "mascara" -> giveMask(sender);
            case "cartucho" -> giveCartridge(sender, args);
            case "evento" -> handleEvent(sender, args);
            case "caravana" -> handleCaravan(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                send(sender, "Configuración recargada.");
            }
            default -> send(sender, "&cUso: /pd <estado|temporada|actos|altar|sacrificios|banco|fases|dia|auto|pausa|reanudar|fase|muertos|revivir|revivirforzado|ancla|nucleo|esquirla|sello|filtro|mascara|cartucho|reserva|evento|caravana|reload>");
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        int day = plugin.days().getCurrentDay();
        send(sender, "Día real: &e" + day + "&r · Calendario: " + (plugin.days().isPaused() ? "&cPausado" : "&aActivo"));
        send(sender, "Fases activas: &f" + activePhases());
        send(sender, "Muerte permanente: " + (plugin.getConfig().getBoolean("permadeath.enabled", true) ? "&aActiva" : "&cDesactivada"));
        send(sender, "Jugadores eliminados: &e" + plugin.deaths().list().size());
    }

    private void showTemporada(CommandSender sender) {
        SeasonService seasons = null;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException ignored) {
        }
        if (seasons == null) {
            send(sender, "Temporada no disponible todavía.");
            return;
        }
        send(sender, seasons.statusLine());
        send(sender, "&7Usa &f/pd actos &7para el calendario completo.");
    }

    private void showFinal(CommandSender sender) {
        SeasonService seasons = null;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException ignored) {
        }
        if (seasons == null) {
            send(sender, "Temporada no disponible todavia.");
            return;
        }
        for (String line : seasons.finaleLines()) send(sender, line);
    }

    private void showActos(CommandSender sender) {
        SeasonService seasons = null;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException ignored) {
        }
        if (seasons == null) {
            send(sender, "Temporada no disponible todavía.");
            return;
        }
        for (String line : seasons.actsCalendar()) send(sender, line);
    }

    private void showPhases(CommandSender sender) {
        send(sender, "&6Calendario de dificultad:");
        for (int phase : PHASES) {
            boolean active = plugin.isPhaseActive(phase);
            boolean enabled = plugin.getConfig().getBoolean("phases." + phase + ".enabled", true);
            String state = !enabled ? "&cdesactivada" : active ? "&aactiva" : "&7pendiente";
            send(sender, "&eDía " + phase + "&7 — " + phaseTitle(phase) + " &8(" + state + "&8)");
        }
    }

    private void showDeadPlayers(CommandSender sender) {
        List<DeathRegistry.DeadPlayer> dead = plugin.deaths().list();
        if (dead.isEmpty()) {
            send(sender, "No hay jugadores eliminados.");
            return;
        }
        send(sender, "&6Jugadores eliminados (&e" + dead.size() + "&6):");
        for (DeathRegistry.DeadPlayer player : dead) {
            send(sender, "&c" + player.name() + " &7— " + player.cause() + " &8(" + player.timestamp() + ")");
        }
    }

    private void handleEvent(CommandSender sender, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("fortaleza")) {
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "estado" -> send(sender, plugin.fortressHuntEvent().status());
                case "iniciar", "prueba" -> {
                    if (!(sender instanceof Player player)) {
                        send(sender, "&cEste comando solo se puede usar dentro del Nether.");
                        return;
                    }
                    send(sender, plugin.fortressHuntEvent().startManual(player));
                }
                case "cancelar" -> send(sender, plugin.fortressHuntEvent().cancel());
                default -> send(sender, "&cUso: /pd evento fortaleza <estado|iniciar|cancelar>");
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("ceniza")) {
            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "estado" -> send(sender, plugin.netherAshstormEvent().status());
                case "iniciar", "prueba" -> {
                    if (!(sender instanceof Player player)) {
                        send(sender, "&cEste comando solo se puede usar dentro del Nether.");
                        return;
                    }
                    send(sender, plugin.netherAshstormEvent().startManual(player));
                }
                case "cancelar" -> send(sender, plugin.netherAshstormEvent().cancel());
                default -> send(sender, "&cUso: /pd evento ceniza <estado|iniciar|cancelar>");
            }
            return;
        }
        if (args.length != 2) {
            send(sender, "&cUso: /pd evento <estado|iniciar|cancelar|ceniza|fortaleza> [estado|iniciar|cancelar]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "estado" -> send(sender, plugin.nightHuntEvent().getStatus());
            case "iniciar", "prueba" -> send(sender, plugin.nightHuntEvent().startManualTest());
            case "cancelar" -> send(sender, plugin.nightHuntEvent().cancelManual());
            default -> send(sender, "&cUso: /pd evento <estado|iniciar|cancelar|ceniza|fortaleza> [estado|iniciar|cancelar]");
        }
    }

    private void handleCaravan(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "&cUso: /pd caravana <estado|iniciar>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "estado" -> send(sender, plugin.caravanEvent().status());
            case "iniciar", "prueba" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&cEste comando solo se puede usar dentro del juego.");
                    return;
                }
                send(sender, plugin.caravanEvent().startTest(player));
            }
            default -> send(sender, "&cUso: /pd caravana <estado|iniciar>");
        }
    }

    private void revive(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "&cUso: /pd revivir <jugador>");
            return;
        }
        DeathRegistry.DeadPlayer target = plugin.deaths().findDead(args[1]);
        if (target == null) {
            send(sender, "&cNo encontré a " + args[1] + " en el registro de muertes.");
            return;
        }
        boolean seasonActive = plugin.seasonService() != null
                  && plugin.seasonService().isSeasonActive();
          if (!seasonActive && plugin.deaths().hasBeenRevived(target.uuid())) {
              send(sender, "&c" + target.name() + " ya recibió su única resurrección.");
            return;
        }
        if (seasonActive) {
            reviveSeason(sender, target);
            return;
        }

        int anchorSlot = -1;
        if (!sender.hasPermission("permadeath.admin")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cSolo un jugador con un Ancla puede solicitar la resurrección.");
                return;
            }
            int effectiveAnchorDay = plugin.seasonService() != null ? plugin.seasonService().mapLegacyDay(RevivalAnchor.UNLOCK_DAY) : RevivalAnchor.UNLOCK_DAY;
            if (plugin.days().getCurrentDay() < effectiveAnchorDay) {
                send(sender, "&cEl Ancla todavía no puede usarse. Se desbloquea en el día " + effectiveAnchorDay + ".");
                return;
            }
            anchorSlot = plugin.revivalAnchor().findAnchorSlot(player);
            if (anchorSlot < 0) {
                send(sender, "&cNecesitas un Ancla del Último Aliento en tu inventario.");
                return;
            }
        }

        UUID targetId;
        try {
            targetId = UUID.fromString(target.uuid());
        } catch (IllegalArgumentException exception) {
            send(sender, "&cEl registro de " + target.name() + " no tiene un UUID válido.");
            return;
        }
        if (plugin.deaths().revive(targetId)) { // legado fuera de temporada: 1 sola vuelta
            if (anchorSlot >= 0 && sender instanceof Player player) {
                plugin.revivalAnchor().consumeFromStorage(player, anchorSlot);
            }
            send(sender, "&a" + target.name() + " fue restaurado a la partida.");
            Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                    + "&e" + target.name() + " volvió a la partida."));
        } else send(sender, "&cNo se pudo revivir a " + target.name() + ".");
    }

    /**
     * Bloque 3 — Vía normal en temporada (incluye /pd revivir admin normal y Ancla):
     * pasa por Fosa/policy sin bypass. Consume Chispas N + peaje + contador solo en
     * ÉXITO; el Ancla del invocador solo se consume si la vuelta quedó registrada.
     */
    private void reviveSeason(CommandSender sender, DeathRegistry.DeadPlayer target) {
        UUID targetId;
        try {
            targetId = UUID.fromString(target.uuid());
        } catch (IllegalArgumentException exception) {
            send(sender, "&cEl registro de " + target.name() + " no tiene un UUID válido.");
            return;
        }
        int anchorSlot = -1;
        if (!sender.hasPermission("permadeath.admin")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cSolo un jugador con un Ancla puede solicitar la resurrección.");
                return;
            }
            anchorSlot = plugin.revivalAnchor().findAnchorSlot(player);
            if (anchorSlot < 0) {
                send(sender, "&cNecesitas un Ancla del Último Aliento en tu inventario.");
                return;
            }
        }
        boolean hasSeal = sender.hasPermission("permadeath.admin");
        if (!hasSeal && sender instanceof Player player) {
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && plugin.resurrectionSealItem().isSeal(item)) {
                    hasSeal = true;
                    break;
                }
            }
        }
        RevivalPolicy.Decision decision = plugin.seasonService().trySeasonRevive(
                targetId, hasSeal, System.currentTimeMillis());
        if (!decision.allowed()) {
            send(sender, "&cLa Fosa no autoriza la vuelta de " + target.name() + ": "
                    + String.join(" ", decision.missing()));
            return;
        }
        if (anchorSlot >= 0 && sender instanceof Player player) {
            plugin.revivalAnchor().consumeFromStorage(player, anchorSlot);
        }
        send(sender, "&a" + target.name() + " volvió por La Fosa (vuelta "
                + plugin.seasonService().revivalsOf(targetId) + "/3, coste "
                + decision.cost() + " Chispas, vía " + decision.path() + ").");
        Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                + "&e" + target.name() + " volvió a la partida por La Fosa."));
    }

    /** Bloque 3 — La Fosa: empezar run y ver estado (privado, sin anuncios globales). */
    private void handleFosa(CommandSender sender, String[] args) {
        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("estado"))) {
            if (!(sender instanceof Player player) && args.length == 1) {
                send(sender, "&cUso: /pd fosa <empezar|estado|volver>");
                return;
            }
            UUID who = sender instanceof Player self ? self.getUniqueId() : null;
            if (who == null) {
                send(sender, "&cSolo jugadores.");
                return;
            }
            if (plugin.pitController() == null) {
                send(sender, "&cLa Fosa no está disponible.");
                return;
            }
            for (String line : plugin.pitController().statusLines(who)) {
                send(sender, line);
            }
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("volver")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            if (!plugin.deaths().isDead(player.getUniqueId())) {
                send(sender, "&cNo estás en el registro de eliminados.");
                return;
            }
            // Altar solitario de La Fosa (≤1 vivo o válvula 5 días): sin Sello.
            // Las vías de ritual exigen el Sello en el mundo y se deniegan aquí.
            RevivalPolicy.Decision decision = plugin.seasonService().trySeasonRevive(
                    player.getUniqueId(), false, System.currentTimeMillis());
            if (!decision.allowed()) {
                send(sender, "&cLa Fosa no autoriza tu vuelta: "
                        + String.join(" ", decision.missing()));
                return;
            }
            if (plugin.pitController() != null) {
                plugin.pitController().releaseRevived(player);
            }
            send(sender, "&aVolviste por el altar de La Fosa (vuelta "
                    + plugin.seasonService().revivalsOf(player.getUniqueId()) + "/3, coste "
                    + decision.cost() + " Chispas).");
            Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                    + "&e" + player.getName() + " volvió a la partida por La Fosa."));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("empezar")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            if (plugin.pitController() == null) {
                send(sender, "&cLa Fosa no está disponible.");
                return;
            }
            String error = plugin.pitController().startRun(player);
            if (error != null) {
                send(sender, error);
            }
            return;
        }
        send(sender, "&cUso: /pd fosa <empezar|estado|volver>");
    }

    private void reviveForced(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "&cUso: /pd revivirforzado <jugador>");
            return;
        }
        if (!sender.hasPermission("permadeath.admin.forcerevive")) {
            send(sender, "&cNo tienes permiso para la resurrección forzada.");
            return;
        }
        DeathRegistry.DeadPlayer target = plugin.deaths().findDead(args[1]);
        if (target == null) {
            send(sender, "&cNo encontré a " + args[1] + " en el registro de muertes.");
            return;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(target.uuid());
        } catch (IllegalArgumentException exception) {
            send(sender, "&cEl registro de " + target.name() + " no tiene un UUID válido.");
            return;
        }
        // Bloque 3: vía forzada solo admin, sin coste ni contador (revivals intacto,
        // historial revived conservado); queda en log persistente del servidor.
        // Si había un escrow abierto (crash a mitad de vuelta normal), se cancela
        // con refund ANTES de forzar: el forzado no cuenta vuelta ni retiene cobro,
        // y un reconcile posterior no debe cometer nada.
        try {
            if (plugin.seasonService() != null) {
                plugin.seasonService().cancelReviveTx(targetId);
            }
        } catch (RuntimeException ignored) {
        }
        if (plugin.deaths().reviveForced(targetId)) {
            plugin.getLogger().info("[Temporada] revivirforzado: " + target.name() + " (" + target.uuid()
                    + ") por " + sender.getName() + "; revivals intacto, historial conservado.");
            send(sender, "&a" + target.name() + " fue restaurado por vía forzada (el historial se conserva).");
            Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                    + "&e" + target.name() + " volvió a la partida por decisión administrativa."));
        } else send(sender, "&cNo se pudo revivir por la vía forzada a " + target.name() + ".");
    }

    private void giveAnchor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        player.getInventory().addItem(plugin.revivalAnchor().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&dAncla del Último Aliento entregada para probar su textura.");
    }

    private void giveMask(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        var recipe = plugin.getServer().getRecipe(new NamespacedKey(plugin, "spore_mask_recipe"));
        if (!(recipe instanceof org.bukkit.inventory.ShapedRecipe shaped)) {
            send(sender, "&cLa Máscara Antigás aún no está disponible (fábrica de la Reserva pendiente).");
            return;
        }
        ItemStack mask = shaped.getResult().clone();
        player.getInventory().addItem(mask)
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&2Máscara Antigás entregada para probar su textura.");
    }

    private void giveCartridge(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                send(sender, "&cUso: /pd cartucho [cantidad]");
                return;
            }
        }
        send(sender, plugin.reservaPodrida().giveFilter(player, amount));
    }

    private void giveSporeFilter(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                send(sender, "&cUso: /pd filtro [cantidad]");
                return;
            }
        }
        send(sender, plugin.reservaPodrida().giveFilter(player, amount));
    }

    private void giveEclipseCore(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        player.getInventory().addItem(plugin.eclipseCoreItem().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&5Núcleo del Eclipse entregado para probar su textura.");
    }

    private void giveEclipseShard(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        player.getInventory().addItem(plugin.eclipseShardItem().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&dEsquirla de Eclipse entregada para probar su textura.");
    }

    private void giveResurrectionSeal(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        if (!isDebugAllowed(sender)) {
            send(sender, "&cComandos debug desactivados (requieren permiso permadeath.debug y debug-commands:true).");
            return;
        }
        player.getInventory().addItem(plugin.resurrectionSealItem().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&5Sello de Resurrección entregado para probar su textura.");
    }

    private void setPhase(CommandSender sender, String[] args) {
        if (args.length != 3 || !List.of("on", "off", "activar", "desactivar").contains(args[2].toLowerCase(Locale.ROOT))) {
            send(sender, "&cUso: /pd fase <día> <on|off>");
            return;
        }
        try {
            int day = Integer.parseInt(args[1]);
            if (!PHASES.contains(day)) throw new NumberFormatException();
            boolean enabled = List.of("on", "activar").contains(args[2].toLowerCase(Locale.ROOT));
            plugin.setPhaseEnabled(day, enabled);
            send(sender, "Fase del día " + day + " " + (enabled ? "&aactivada" : "&cdesactivada") + "&r.");
        } catch (NumberFormatException ignored) {
            send(sender, "&cDía inválido. Usa: " + PHASES.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
    }

    private void setDay(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, "&cUso: /pd dia <número>");
            return;
        }
        try {
            int day = Integer.parseInt(args[1]);
            if (day < 1) throw new NumberFormatException();
            plugin.days().setManualDay(day);
            send(sender, "Día de prueba forzado a &e" + day + "&r.");
        } catch (NumberFormatException ignored) {
            send(sender, "&cUsa un número entero mayor o igual a 1.");
        }
    }

    private String activePhases() {
        List<Integer> active = visibleActivePhases(plugin::isPhaseActive);
        if (active.isEmpty()) return "Vanilla";
        return active.stream().map(phase -> "Día " + phase).collect(Collectors.joining(", "));
    }

    private String phaseTitle(int day) {
        return switch (day) {
            case 10 -> "Modo UHC";
            case 20 -> "Insomnio, asedios y Pale Warden";
            case 25 -> "Peligros subterráneos";
            case 35 -> "El End y el Dragón";
            case 40 -> "Invasión del Overworld";
            case 50 -> "Clima y agua hostiles";
            case 60 -> "Mobs y monturas peligrosas";
            case 70 -> "Supervivencia extrema";
            case 80 -> "Cazadores y emboscadas";
            case 90 -> "Hambruna global";
            case 100 -> "La última fase";
            default -> "Fase";
        };
    }

    private void handleAltar(CommandSender sender, String[] args) {
        String cmd = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "altar";
        boolean list = cmd.equals("sacrificios");
        if (!list) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("aqui")) {
                if (!sender.hasPermission("permadeath.admin")) {
                    send(sender, "&cNo tienes permiso para administrar la partida.");
                    return;
                }
                send(sender, plugin.seasonAltar().setHere(player));
                return;
            }
            plugin.seasonAltar().openMenu(player);
            return;
        }
        // /pd sacrificios: elecciones públicas de todos (solo lectura).
        SeasonService seasons = null;
        try {
            seasons = plugin.seasonService();
        } catch (RuntimeException ignored) {
        }
        if (seasons == null) {
            send(sender, "Temporada no disponible todavía.");
            return;
        }
        var all = seasons.allSacrifices();
        if (all.isEmpty()) {
            send(sender, "&7Nadie se ha sacrificado todavía. Usa &f/pd altar &7(desde el acto II).");
            return;
        }
        send(sender, "&5Sacrificios públicos (&e" + all.size() + "&5):");
        for (var e : all.entrySet()) {
            String name = e.getKey();
            try {
                Player online = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(e.getKey()));
                if (online != null) name = online.getName();
            } catch (IllegalArgumentException ignored) {
            }
            send(sender, "&f" + name + " &8— &d" + e.getValue());
        }
    }

    private void handleBanco(CommandSender sender, String[] args) {
        SeasonEconomyService economy = plugin.seasonEconomy();
        if (economy == null) {
            send(sender, "Banco no disponible todavía.");
            return;
        }
        if (args.length == 1) {
            for (String line : economy.statusLines()) send(sender, line);
            send(sender, "&7Usa &f/pd banco depositar&7 junto al altar, y &f/pd banco comprar <extension|ritual|sol> &7(permiso permadeath.bank, doble confirmación).");
            return;
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        if (op.equals("depositar")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            economy.depositFromInventory(player);
            return;
        }
        if (op.equals("comprar") && args.length >= 3) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            if (!economy.canSpend(player)) {
                send(sender, "&cSolo permadeath.bank (default op) puede gastar el fondo colectivo.");
                return;
            }
            String kind = args[2].toLowerCase(Locale.ROOT);
            if (!List.of("extension", "ritual", "sol").contains(kind)) {
                send(sender, "&cUso: /pd banco comprar <extension|ritual|sol>");
                return;
            }
            String key = player.getUniqueId().toString();
            PendingBank staged = pendingBank.get(key);
            long now = System.currentTimeMillis();
            if (staged == null || !staged.kind().equals(kind) || now - staged.at() > 60_000L) {
                String tx = SeasonEconomyService.newTxId(kind, player.getUniqueId());
                pendingBank.put(key, new PendingBank(kind, tx, now));
                send(sender, "&eVas a comprar &f" + kind + " &e(12N extensión única +3d tope 45 / 8N ritual / 30N sol en escrow). Repite &f/pd banco comprar " + kind + " &epara CONFIRMAR (id " + tx + "). Cerrar no cobra nada.");
                return;
            }
            pendingBank.remove(key);
            switch (staged.kind()) {
                case "extension" -> send(sender, economy.buyExtension(player, staged.txId()));
                case "ritual" -> send(sender, economy.buyRitual(player, staged.txId()));
                default -> send(sender, economy.reserveSun(player, staged.txId()));
            }
            return;
        }
        if (op.equals("sol") && args.length >= 4) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cEste comando solo se puede usar dentro del juego.");
                return;
            }
            String action = args[2].toLowerCase(Locale.ROOT);
            String tx = args[3];
            if (action.equals("confirmar")) send(sender, economy.confirmSun(player, tx));
            else if (action.equals("cancelar")) send(sender, economy.cancelSun(player, tx));
            else send(sender, "&cUso: /pd banco sol <confirmar|cancelar> <id>");
            return;
        }
        for (String line : economy.statusLines()) send(sender, line);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("estado", "temporada", "actos", "altar", "sacrificios", "banco", "fases", "revivir", "reserva"));
            if (sender.hasPermission("permadeath.admin")) {
                options.addAll(List.of("dia", "auto", "pausa", "reanudar", "fase", "muertos", "ancla",
                        "nucleo", "esquirla", "sello", "filtro", "mascara", "cartucho", "evento", "caravana", "reload"));
            }
            if (sender.hasPermission("permadeath.admin.forcerevive") || sender.hasPermission("permadeath.admin")) {
                options.add("revivirforzado");
            }
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reserva")) return List.of("salir");
        if (args.length == 2 && args[0].equalsIgnoreCase("altar")) return List.of("aqui");
        if (args.length == 2 && args[0].equalsIgnoreCase("banco")) return List.of("depositar", "comprar", "sol");
        if (args.length == 3 && args[0].equalsIgnoreCase("banco") && args[1].equalsIgnoreCase("comprar")) return List.of("extension", "ritual", "sol");
        if (args.length == 3 && args[0].equalsIgnoreCase("banco") && args[1].equalsIgnoreCase("sol")) return List.of("confirmar", "cancelar");
        if (args.length == 2 && args[0].equalsIgnoreCase("filtro")) return List.of("1", "5", "16", "64");
        if (args.length == 2 && args[0].equalsIgnoreCase("cartucho")) return List.of("1", "2", "5", "16", "64");
        if (args.length == 2 && args[0].equalsIgnoreCase("evento")) return List.of("estado", "iniciar", "cancelar", "ceniza", "fortaleza");
        if (args.length == 3 && args[0].equalsIgnoreCase("evento") && args[1].equalsIgnoreCase("ceniza")) return List.of("estado", "iniciar", "cancelar");
        if (args.length == 3 && args[0].equalsIgnoreCase("evento") && args[1].equalsIgnoreCase("fortaleza")) return List.of("estado", "iniciar", "cancelar");
        if (args.length == 2 && args[0].equalsIgnoreCase("caravana")) return List.of("estado", "iniciar");
        if (args.length == 2 && args[0].equalsIgnoreCase("fase")) return PHASES.stream().map(String::valueOf).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("revivir")) return plugin.deaths().list().stream().map(DeathRegistry.DeadPlayer::name).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("revivirforzado")) {
            if (!sender.hasPermission("permadeath.admin.forcerevive") && !sender.hasPermission("permadeath.admin")) return List.of();
            return plugin.deaths().list().stream().map(DeathRegistry.DeadPlayer::name).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("fase")) return List.of("on", "off");
        return List.of();
    }
}
