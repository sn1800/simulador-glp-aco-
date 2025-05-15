package core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Bloqueo {
    int inicio;
    int fin;
    Set<String> aristasBloqueadas = new HashSet<>();

    public Bloqueo(int inicio, int fin, List<int[]> coords) {
        this.inicio = inicio;
        this.fin = fin;
        for (int i = 0; i < coords.size() - 1; i++) {
            int[] a = coords.get(i);
            int[] b = coords.get(i + 1);
            String arista = aristaId(a[0], a[1], b[0], b[1]);
            aristasBloqueadas.add(arista);
        }
    }

    boolean afecta(int t, int x1, int y1, int x2, int y2) {
        return t >= inicio && t <= fin && aristasBloqueadas.contains(aristaId(x1, y1, x2, y2));
    }

    static String aristaId(int x1, int y1, int x2, int y2) {
        return Math.min(x1, x2) + "," + Math.min(y1, y2) + "-" + Math.max(x1, x2) + "," + Math.max(y1, y2);
    }
}
