package core;
import java.awt.Point;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Bloqueo {
    private final int startMin;      // minuto absoluto de inicio
    private final int endMin;        // minuto absoluto de fin (exclusivo)
    private final List<Point> nodes; // nodos extremos del bloqueo (poligonal abierta)

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)d(\\d+)h(\\d+)m");

    public Bloqueo(int startMin, int endMin, List<Point> nodes) {
        this.startMin = startMin;
        this.endMin   = endMin;
        this.nodes    = new ArrayList<>(nodes);
    }

    /** Construye un Bloqueo a partir de una línea de tu archivo:
     "01d06h00m-01d15h00m:31,21,34,21,..." */
    public static Bloqueo fromRecord(String record) {
        String[] parts = record.split(":");
        String[] times = parts[0].split("-");
        int s = parseTimeToMinutes(times[0]);
        int e = parseTimeToMinutes(times[1]);

        String[] coords = parts[1].split(",");
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            int x = Integer.parseInt(coords[i]);
            int y = Integer.parseInt(coords[i+1]);
            pts.add(new Point(x, y));
        }
        return new Bloqueo(s, e, pts);
    }

    /** ¿Está activo a t (minutos desde t=0)? */
    public boolean isActiveAt(int t) {
        return t >= startMin && t < endMin;
    }
    public boolean estaBloqueado(int timeMin, Point p) {
        if (timeMin < startMin || timeMin >= endMin) return false;
        // Recorro cada segmento de la poligonal
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i), b = nodes.get(i+1);
            // Compruebo si p está sobre el segmento a–b
            if (a.x == b.x && p.x == a.x && p.y >= Math.min(a.y,b.y) && p.y <= Math.max(a.y,b.y))
                return true;
            if (a.y == b.y && p.y == a.y && p.x >= Math.min(a.x,b.x) && p.x <= Math.max(a.x,b.x))
                return true;
        }
        return false;
    }
    /** ¿Bloquea el segmento p→q?
     Compara con cada par consecutivo en this.nodes */
    public boolean coversSegment(Point p, Point q) {
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i);
            Point b = nodes.get(i + 1);
            // coincide en cualquier orden
            if ((p.equals(a) && q.equals(b)) ||
                    (p.equals(b) && q.equals(a))) {
                return true;
            }
        }
        return false;
    }

    /** Convierte “#d#h#m” a minutos totales */
    private static int parseTimeToMinutes(String s) {
        Matcher m = TIME_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Formato de tiempo inválido: " + s);
        }
        int days    = Integer.parseInt(m.group(1));
        int hours   = Integer.parseInt(m.group(2));
        int minutes = Integer.parseInt(m.group(3));
        return days * 24 * 60 + hours * 60 + minutes;
    }

    // Getters (si los necesitas)
    public int getStartMin() { return startMin; }
    public int getEndMin()   { return endMin;   }
    public List<Point> getNodes() { return Collections.unmodifiableList(nodes); }
}
