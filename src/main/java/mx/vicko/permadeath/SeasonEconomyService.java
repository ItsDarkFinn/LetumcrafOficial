package mx.vicko.permadeath;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Bloque 2 — Fachada del Banco Eclipse (Bukkit).
 *
 * <p>Política (documentada también en README y {@code /pd banco}):
 * los fondos son colectivos; solo {@code permadeath.bank} (default op) puede consumirlos,
 * siempre con doble confirmación; ningún jugador aleatorio puede destruir fondos sin
 * confirmar. Depósitos solo con Núcleos PDC auténticos junto al altar; las Esquirlas no
 * se convierten (sin tasa aprobada) y las Chispas nunca entran al banco (personales).
 *
 * <p>Expone para B3: {@link #balance}, {@link #isRitualOpen}, {@link #isSunReserved},
 * {@link #escrowLines} y {@link #txLines} (gastos/estado del ritual/expiración).
 */
public final class SeasonEconomyService {

    private final PermadeathPlugin plugin;

    public SeasonEconomyService(PermadeathPlugin plugin) {
        this.plugin = plugin;
    }

    private SeasonService seasons() {
        return plugin.seasonService();
    }

    // ------------------------------------------------------------------ consulta (API para B3)

    public int balance() {
        return seasons().bankBalance();
    }

    public int totalDeposited() {
        return seasons().bankTotalDeposited();
    }

    public int totalSpent() {
        return seasons().bankTotalSpent();
    }

    public boolean isRitualOpen() {
        return seasons().isBankRitualOpen();
    }

    public boolean isSunReserved() {
        return seasons().isBankSunReserved();
    }

    public boolean isExtensionUsed() {
        return seasons().isBankExtensionUsed();
    }

    public List<String> escrowLines() {
        List<String> lines = new ArrayList<>();
        for (var e : seasons().bankEscrow().entrySet()) {
            lines.add(e.getKey() + " → " + e.getValue() + " Núcleos en escrow");
        }
        return lines;
    }

    public List<String> txLines() {
        return new ArrayList<>(seasons().bankTxIds());
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GOLD + "[Banco Eclipse] " + ChatColor.WHITE + "fondo: "
                + balance() + " Núcleos (aportado " + totalDeposited() + ", gastado " + totalSpent() + ")");
        int day = 1;
        int base = 40;
        int effective = 40;
        try {
            day = seasons().currentDay();
            base = seasons().baseSeasonLengthDays();
            effective = seasons().effectiveSeasonLengthDays();
        } catch (RuntimeException ignored) {
        }
        lines.add(ChatColor.GRAY + "Temporada: día " + day + "/" + effective
                + " (final real día " + effective + ", base " + base + ")");
        lines.add(ChatColor.GRAY + "Extensión 12N (única, +3d, tope 45): "
                + (isExtensionUsed() ? "usada (fin día " + effective + ")" : "disponible (fin día " + effective + ")"));
        lines.add(ChatColor.GRAY + "Ritual global 8N: " + (isRitualOpen() ? "ABIERTO" : "cerrado"));
        lines.add(ChatColor.GRAY + "Sol último día 30N: " + (isSunReserved() ? "RESERVADO" : "sin reservar"));
        for (String line : escrowLines()) {
            lines.add(ChatColor.GRAY + "Escrow: " + line);
        }
        return lines;
    }

    // ------------------------------------------------------------------ depósitos

    /**
     * Deposita los Núcleos PDC auténticos del inventario (cuenta real, sin argumentos
     * negativos ni duplicación: quita los ítems y luego acredita). Con ENDED se
     * rechaza antes de tocar el inventario; si el abono acredita 0, se devuelve
     * íntegro. Exige cercanía al altar si está fijado (cofre del altar).
     * Las Esquirlas se rechazan sin convertir.
     *
     * @return cuántos Núcleos entraron.
     */
    public int depositFromInventory(Player player) {
        // B4 — con la temporada sellada (ENDED) se rechaza ANTES de tocar el
        // inventario: quitar primero y acreditar después destruiría los Núcleos
        // (depositBankCores devuelve 0 con ENDED) con mensaje de éxito falso.
        if (seasons().isSeasonEnded()) {
            player.sendMessage(ChatColor.RED + "La temporada ya terminó: el banco está cerrado, conserva tus Núcleos.");
            return 0;
        }
        int cores = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (plugin.eclipseCoreItem() != null && plugin.eclipseCoreItem().isCore(item)) {
                cores += item.getAmount();
            }
        }
        if (cores <= 0) {
            boolean hasShards = false;
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (plugin.eclipseShardItem() != null && plugin.eclipseShardItem().isShard(item)) {
                    hasShards = true;
                    break;
                }
            }
            if (hasShards) {
                player.sendMessage(ChatColor.RED + "Solo Núcleos entran al fondo; "
                        + "las Esquirlas no se convierten (sin tasa aprobada).");
            } else {
                player.sendMessage(ChatColor.RED + "No llevas Núcleos del Eclipse auténticos.");
            }
            return 0;
        }
        if (!nearAltar(player)) {
            player.sendMessage(ChatColor.RED + "Acércate al altar (12 bloques) para depositar.");
            return 0;
        }
        List<ItemStack> taken = new ArrayList<>();
        int removed = 0;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            ItemStack item = player.getInventory().getStorageContents()[slot];
            if (plugin.eclipseCoreItem() != null && plugin.eclipseCoreItem().isCore(item)) {
                taken.add(item.clone());
                removed += item.getAmount();
                player.getInventory().setItem(slot, null);
            }
        }
        if (removed <= 0) {
            return 0;
        }
        int credited = seasons().depositBankCores(removed, player.getUniqueId());
        if (credited <= 0) {
            // No se acreditó nada (cierre concurrente entre el guard y el abono):
            // devolver íntegro al inventario, sin cobrar nada y sin éxito falso.
            for (ItemStack back : taken) {
                player.getInventory().addItem(back);
            }
            player.sendMessage(ChatColor.RED + "No se pudo acreditar el depósito (la temporada está cerrada); se devolvieron tus Núcleos.");
            return 0;
        }
        if (credited < removed) {
            // Acreditar solo lo realmente depositado y devolver el resto.
            int missing = removed - credited;
            for (ItemStack back : taken) {
                if (missing <= 0) {
                    break;
                }
                int give = Math.min(missing, back.getAmount());
                ItemStack part = back.clone();
                part.setAmount(give);
                player.getInventory().addItem(part);
                missing -= give;
            }
        }
        player.sendMessage(ChatColor.GREEN + "Depositaste " + credited + " Núcleos. Fondo: "
                + seasons().bankBalance() + ".");
        return credited;
    }

    private boolean nearAltar(Player player) {
        Location altar = null;
        try {
            altar = plugin.seasonAltar() == null ? null : plugin.seasonAltar().altarLocation();
        } catch (RuntimeException ignored) {
        }
        if (altar == null || altar.getWorld() == null) {
            return true;
        }
        if (!player.getWorld().equals(altar.getWorld())) {
            return false;
        }
        return player.getLocation().distanceSquared(altar) <= 12 * 12;
    }

    // ------------------------------------------------------------------ compras (permiso + confirmación en el comando)

    public boolean canSpend(Player player) {
        return player.hasPermission("permadeath.bank");
    }

    public String buyExtension(Player player, String txId) {
        if (!canSpend(player)) {
            return ChatColor.RED + "Solo permadeath.bank (default op) puede gastar el fondo colectivo.";
        }
        if (seasons().purchaseBankExtension(txId)) {
            return ChatColor.GREEN + "Extensión aplicada: +3 días (temporada "
                    + seasons().seasonLengthDays() + " días).";
        }
        return ChatColor.RED + "No se pudo comprar la extensión (¿fondos? ¿tope 45? ¿ya usada?).";
    }

    public String buyRitual(Player player, String txId) {
        if (!canSpend(player)) {
            return ChatColor.RED + "Solo permadeath.bank (default op) puede gastar el fondo colectivo.";
        }
        if (seasons().purchaseBankRitual(txId)) {
            return ChatColor.GREEN + "Ritual de resurrección ABIERTO para el grupo (persistente).";
        }
        return ChatColor.RED + "No se pudo abrir el ritual (¿fondos? ¿ya abierto?).";
    }

    public String reserveSun(Player player, String txId) {
        if (!canSpend(player)) {
            return ChatColor.RED + "Solo permadeath.bank (default op) puede gastar el fondo colectivo.";
        }
        if (seasons().reserveBankSun(txId)) {
            return ChatColor.GREEN + "Sol reservado en escrow (30N). Confirma con /pd banco sol confirmar " + txId + ".";
        }
        return ChatColor.RED + "No se pudo reservar el Sol (¿fondos? ¿ya reservado?).";
    }

    public String confirmSun(Player player, String txId) {
        if (!canSpend(player)) {
            return ChatColor.RED + "Solo permadeath.bank (default op) puede gastar el fondo colectivo.";
        }
        if (seasons().confirmBankSun(txId)) {
            return ChatColor.GREEN + "Sol del último día PAGADO (contrato B4: se enciende el día final).";
        }
        return ChatColor.RED + "No hay escrow con ese id.";
    }

    public String cancelSun(Player player, String txId) {
        if (!canSpend(player)) {
            return ChatColor.RED + "Solo permadeath.bank (default op) puede gastar el fondo colectivo.";
        }
        if (seasons().cancelBankSun(txId)) {
            return ChatColor.GREEN + "Reserva cancelada: escrow devuelto al fondo.";
        }
        return ChatColor.RED + "No hay escrow con ese id.";
    }

    public static String newTxId(String kind, UUID actor) {
        String who = actor == null ? "consola" : actor.toString().substring(0, 8);
        return kind + "-" + who + "-" + System.currentTimeMillis();
    }
}
