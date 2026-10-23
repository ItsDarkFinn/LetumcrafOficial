package mx.vicko.permadeath;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class PermadeathCommand implements CommandExecutor, TabCompleter {
    private static final List<Integer> PHASES = List.of(10, 20, 25, 35, 40, 50, 60, 70, 80, 90, 100);
    private final PermadeathPlugin plugin;

    public PermadeathCommand(PermadeathPlugin plugin) {
        this.plugin = plugin;
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
        if (sub.equals("revivir")) {
            revive(sender, args);
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
            case "evento" -> handleEvent(sender, args);
            case "caravana" -> handleCaravan(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                send(sender, "Configuración recargada.");
            }
            default -> send(sender, "&cUso: /pd <estado|fases|dia|auto|pausa|reanudar|fase|muertos|revivir|ancla|nucleo|esquirla|sello|reserva|reload>");
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        int day = plugin.days().getCurrentDay();
        send(sender, "Día real: &e" + day + "&r · Calendario: " + (plugin.days().isPaused() ? "&cPausado" : "&aActivo"));
        send(sender, "Fases activas: &f" + activePhases(day));
        send(sender, "Muerte permanente: " + (plugin.getConfig().getBoolean("permadeath.enabled", true) ? "&aActiva" : "&cDesactivada"));
        send(sender, "Jugadores eliminados: &e" + plugin.deaths().list().size());
    }

    private void showPhases(CommandSender sender) {
        send(sender, "&6Calendario de dificultad:");
        for (int phase : PHASES) {
            boolean active = plugin.days().getCurrentDay() >= phase && plugin.isPhaseActive(phase);
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
        if (plugin.deaths().hasBeenRevived(target.uuid())) {
            send(sender, "&c" + target.name() + " ya recibió su única resurrección.");
            return;
        }

        int anchorSlot = -1;
        if (!sender.hasPermission("permadeath.admin")) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cSolo un jugador con un Ancla puede solicitar la resurrección.");
                return;
            }
            if (plugin.days().getCurrentDay() < RevivalAnchor.UNLOCK_DAY) {
                send(sender, "&cEl Ancla todavía no puede usarse. Se desbloquea en el día " + RevivalAnchor.UNLOCK_DAY + ".");
                return;
            }
            anchorSlot = plugin.revivalAnchor().findAnchorSlot(player);
            if (anchorSlot < 0) {
                send(sender, "&cNecesitas un Ancla del Último Aliento en tu inventario.");
                return;
            }
        }

        if (plugin.deaths().revive(target.name())) {
            if (anchorSlot >= 0 && sender instanceof Player player) {
                plugin.revivalAnchor().consumeFromStorage(player, anchorSlot);
            }
            send(sender, "&a" + target.name() + " fue restaurado a la partida.");
            Bukkit.broadcastMessage(color(plugin.getConfig().getString("messages.prefix", "")
                    + "&e" + target.name() + " volvió a la partida."));
        } else send(sender, "&cNo se pudo revivir a " + target.name() + ".");
    }

    private void giveAnchor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
            return;
        }
        player.getInventory().addItem(plugin.revivalAnchor().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&dAncla del Último Aliento entregada para probar su textura.");
    }

    private void giveSporeFilter(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
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
        player.getInventory().addItem(plugin.eclipseCoreItem().createItem())
                .values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        send(sender, "&5Núcleo del Eclipse entregado para probar su textura.");
    }

    private void giveEclipseShard(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cEste comando solo se puede usar dentro del juego.");
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

    private String activePhases(int day) {
        if (day < 10) return "Vanilla";
        return PHASES.stream().filter(phase -> day >= phase && plugin.isPhaseActive(phase))
                .map(phase -> "Día " + phase).collect(Collectors.joining(", "));
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

    private void send(CommandSender sender, String message) {
        sender.sendMessage(color(plugin.getConfig().getString("messages.prefix", "") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("estado", "fases", "dia", "auto", "pausa", "reanudar", "fase", "muertos", "revivir", "ancla", "nucleo", "esquirla", "sello", "filtro", "reserva", "evento", "caravana", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("reserva")) return List.of("salir");
        if (args.length == 2 && args[0].equalsIgnoreCase("filtro")) return List.of("1", "5", "16", "64");
        if (args.length == 2 && args[0].equalsIgnoreCase("evento")) return List.of("estado", "iniciar", "cancelar", "ceniza", "fortaleza");
        if (args.length == 3 && args[0].equalsIgnoreCase("evento") && args[1].equalsIgnoreCase("ceniza")) return List.of("estado", "iniciar", "cancelar");
        if (args.length == 3 && args[0].equalsIgnoreCase("evento") && args[1].equalsIgnoreCase("fortaleza")) return List.of("estado", "iniciar", "cancelar");
        if (args.length == 2 && args[0].equalsIgnoreCase("caravana")) return List.of("estado", "iniciar");
        if (args.length == 2 && args[0].equalsIgnoreCase("fase")) return PHASES.stream().map(String::valueOf).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("revivir")) return plugin.deaths().list().stream().map(DeathRegistry.DeadPlayer::name).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("fase")) return List.of("on", "off");
        return List.of();
    }
}
