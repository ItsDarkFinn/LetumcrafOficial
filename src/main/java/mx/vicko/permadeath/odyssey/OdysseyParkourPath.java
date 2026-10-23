package mx.vicko.permadeath.odyssey;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometria pura de una ruta de parkour entre dos islas.
 *
 * <p>Las plataformas son de 1x1. La separacion horizontal varia entre 2 y 4
 * bloques y la altura cambia como maximo un bloque por salto. La ruta se sube
 * o baja de forma gradual para que sea exigente pero alcanzable sin pociones
 * ni bloques de apoyo.</p>
 */
final class OdysseyParkourPath {
    static final int MIN_GAP = 2;
    static final int MAX_GAP = 4;
    private static final int[] STEP_PATTERN = {3, 3, 2, 3, 4, 3, 2, 3};
    private static final int MAX_PLATFORMS = 512;

    private OdysseyParkourPath() {
    }

    /**
     * @param position coordenada sobre el eje de avance (X para este, Z para sur)
     * @param y        altura del bloque de la plataforma
     * @param sway     desvio lateral en el eje perpendicular
     */
    record Platform(int position, int y, int sway) {
    }

    static List<Platform> plan(int start, int end, int startY, int endY) {
        List<Platform> platforms = new ArrayList<>();
        if (end < start) {
            return platforms;
        }

        int position = start;
        int previousY = startY;
        int index = 0;
        while (true) {
            int y = limitStep(previousY, interpolateY(start, end, startY, endY, position));
            platforms.add(new Platform(position, y, sway(index)));
            previousY = y;

            if (position >= end || index >= MAX_PLATFORMS) {
                break;
            }

            int baseStep = STEP_PATTERN[index % STEP_PATTERN.length];
            int previewPosition = Math.min(end, position + baseStep);
            int previewY = interpolateY(start, end, startY, endY, previewPosition);
            int step = previewY > y && baseStep > 3 ? 3 : baseStep;
            if (sway(index) != 0 && step > 3) {
                step = 3;
            }
            int next = Math.min(end, position + step);
            if (next <= position) {
                next = position + 1;
            } else if (next == end - 1) {
                // Evita un ultimo salto de un solo bloque: la ruta debe seguir
                // teniendo un hueco real hasta la isla.
                next = step <= 2 ? end : end - 2;
            }
            position = next;
            index++;
        }
        return platforms;
    }

    static int interpolateY(int start, int end, int startY, int endY, int position) {
        if (end <= start) {
            return endY;
        }
        double progress = (double) (position - start) / (double) (end - start);
        return startY + (int) Math.round((endY - startY) * progress);
    }

    static int limitStep(int previousY, int desiredY) {
        return Math.max(previousY - 1, Math.min(previousY + 1, desiredY));
    }

    static int sway(int index) {
        if (Math.floorMod(index, 7) == 0) {
            return 1;
        }
        if (Math.floorMod(index, 11) == 5) {
            return -1;
        }
        return 0;
    }
}
