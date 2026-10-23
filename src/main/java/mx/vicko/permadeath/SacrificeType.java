package mx.vicko.permadeath;

import java.util.Locale;

/**
 * Bloque 2 — Los 7 sacrificios del Altar (puros, sin Bukkit).
 *
 * <p>Una elección por UUID por temporada, irreversible aun al morir/revivir/reiniciar.
 * Ids estables para persistencia en {@code season-state.yml} (nunca {@code config.yml} como BD).
 */
public enum SacrificeType {
    ABRIGO("ABRIGO", "Abrigo", "Inmune a tormenta/frío; los mobs te detectan a 32 bloques."),
    CAMA("CAMA", "Cama", "Dormir funciona (evita la explosión UHC); −2 corazones máximos (−4 HP)."),
    OJO("OJO", "Ojo", "Ves mobs tras paredes a 16 bloques; los mobs te apuntan primero."),
    PULMON("PULMON", "Pulmón", "Respiras bajo el agua; velocidad en tierra −15%."),
    ESTOMAGO("ESTOMAGO", "Estómago", "Comida sana y sin agotamiento; sin pociones (beber/salpicadura/nube)."),
    CORAZON("CORAZON", "Corazón", "+4 corazones máximos (+8 HP); sin armadura superior a hierro."),
    ANCLA("ANCLA", "Ancla", "Reapareces en tu punto de muerte una vez; el grupo paga 1 Núcleo. No concede vuelta gratis ni salta La Fosa.");

    private final String id;
    private final String displayName;
    private final String description;

    SacrificeType(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Tolerante: null/vacío/desconocido = null (el llamador decide, nunca lanza). */
    public static SacrificeType fromId(String id) {
        if (id == null) {
            return null;
        }
        String t = id.trim().toUpperCase(Locale.ROOT);
        for (SacrificeType type : values()) {
            if (type.id.equals(t)) {
                return type;
            }
        }
        return null;
    }
}
