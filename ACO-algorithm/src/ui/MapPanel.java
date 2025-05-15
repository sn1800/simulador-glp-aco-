package ui;

import core.ACOPlanner;
import core.Camion;
import core.Pedido;
import core.Tanque;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Panel que dibuja el estado de la simulación:
 *   - la cuadrícula (70×50)
 *   - los pedidos pendientes (en rojo)
 *   - los camiones (en azul)
 *   - los tanques intermedios (en naranja)
 */
public class MapPanel extends JPanel {
    private Camion selectedCamion;
    private final ACOPlanner planner;
    private static final int GRID_COLS = 70;
    private static final int GRID_ROWS = 50;
    final int depositoX = 12, depositoY = 8;
    /** Permite al controlador cambiar qué camión destacar */
    public void setSelectedCamion(Camion c) {
        this.selectedCamion = c;
        repaint();
    }
    public MapPanel(ACOPlanner planner) {
        this.planner = planner;
        // Para evitar flicker
        setDoubleBuffered(true);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // 1) Fondo blanco
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, getWidth(), getHeight());

        // 2) Calcular tamaño de celda
        int cellW = getWidth()  / GRID_COLS;
        int cellH = getHeight() / GRID_ROWS;

        // 3) Dibujar malla ligera
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i <= GRID_COLS; i++) {
            int x = i * cellW;
            g.drawLine(x, 0, x, getHeight());
        }
        for (int j = 0; j <= GRID_ROWS; j++) {
            int y = j * cellH;
            g.drawLine(0, y, getWidth(), y);
        }

        // 4) Dibujar pedidos pendientes en rojo
        List<Pedido> pedidos = planner.getPedidos();
        g.setColor(Color.RED);
        for (Pedido p : pedidos) {
            if (!p.isAtendido() && !p.isDescartado()) {
                int x = p.getX() * cellW;
                int y = getHeight() - (p.getY() * cellH + cellH);
                g.fillOval(x, y, cellW, cellH);
            }
        }

        // 5) Dibujar tanques intermedios en naranja (sólo su ubicación)
        List<Tanque> tanques = planner.getTanquesIntermedios();
        g.setColor(Color.ORANGE);
        for (Tanque t : tanques) {
            int x = t.getX() * cellW;
            int y = getHeight() - (t.getY() * cellH + cellH);
            g.drawRect(x, y, cellW, cellH);
        }

        // 6) Dibujar camiones en azul
        List<Camion> flota = planner.getFlota();
        g.setColor(Color.BLUE);
        for (Camion c : flota) {
            int x = c.getX() * cellW;
            int y = getHeight() - (c.getY() * cellH + cellH);
            g.fillRect(x, y, cellW, cellH);
        }
        // ***🔹 7b) Dibuja aquí la ruta segmentada (Manhattan) ***
        if (selectedCamion != null && selectedCamion.getRuta() != null) {
            drawRuta(g, selectedCamion.getRuta(), cellW, cellH);
        }
        // 7) Dibujar trayectoria HISTÓRICA del camión seleccionado (gris fina)
        // 7) Dibujar toda la trayectoria histórica del camión seleccionado en magenta
        if (selectedCamion != null) {
            drawFullTrajectory(g, selectedCamion.getHistory(), cellW, cellH);
        }

    }
    /**
     * Dibuja el recorrido Manhattan en magenta,
     * marcando el origen (primer nodo) en verde
     * y el destino (último nodo) en rojo.
     */
    /** Dibuja toda la trayectoria histórica en gris fino */
    private void drawHistory(Graphics2D g, List<Point> history, int cellW, int cellH) {
        if (history == null || history.size() < 2) return;
        g.setColor(Color.LIGHT_GRAY);
        g.setStroke(new BasicStroke(1));
        for (int i = 0; i < history.size() - 1; i++) {
            Point p1 = history.get(i), p2 = history.get(i+1);
            int x1 = p1.x * cellW + cellW/2;
            int y1 = getHeight() - (p1.y * cellH + cellH/2);
            int x2 = p2.x * cellW + cellW/2;
            int y2 = getHeight() - (p2.y * cellH + cellH/2);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    /** Dibuja el último tramo de ruta en magenta, marcando origen y destino */
    private void drawRuta(Graphics g0, List<Point> ruta, int cellW, int cellH) {
        if (ruta == null || ruta.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g0;
        // Origen y destino
        Point start = ruta.get(0), end = ruta.get(ruta.size()-1);
        int sx = start.x * cellW + cellW/2, sy = getHeight() - (start.y * cellH + cellH/2);
        int ex = end.x   * cellW + cellW/2, ey = getHeight() - (end.y   * cellH + cellH/2);
        g2.setColor(Color.GREEN); g2.fillOval(sx-5, sy-5, 10, 10);
        g2.setColor(Color.RED);   g2.fillOval(ex-5, ey-5, 10, 10);
        // Línea magenta
        g2.setColor(Color.MAGENTA);
        g2.setStroke(new BasicStroke(2));
        for (int i = 0; i < ruta.size() - 1; i++) {
            Point p1 = ruta.get(i), p2 = ruta.get(i+1);
            int x1 = p1.x * cellW + cellW/2;
            int y1 = getHeight() - (p1.y * cellH + cellH/2);
            int x2 = p2.x * cellW + cellW/2;
            int y2 = getHeight() - (p2.y * cellH + cellH/2);
            g2.drawLine(x1, y1, x2, y2);
        }
    }
    private void drawFullTrajectory(Graphics g0, List<Point> history, int cellW, int cellH) {
        if (history == null || history.size() < 2) return;
        Graphics2D g = (Graphics2D) g0;

        // 1) Línea magenta gruesa
        g.setColor(Color.MAGENTA);
        g.setStroke(new BasicStroke(2));
        for (int i = 0; i < history.size() - 1; i++) {
            Point p1 = history.get(i), p2 = history.get(i + 1);
            int x1 = p1.x * cellW + cellW/2;
            int y1 = getHeight() - (p1.y * cellH + cellH/2);  // ← AQUÍ!
            int x2 = p2.x * cellW + cellW/2;
            int y2 = getHeight() - (p2.y * cellH + cellH/2);  // ← Y AQUÍ!
            g.drawLine(x1, y1, x2, y2);
        }

        // 2) Punto inicial (primer nodo) en verde
        Point start = history.get(0);
        int sx = start.x * cellW + cellW/2;
        int sy = getHeight() - (start.y * cellH + cellH / 2);  // ← AQUÍ
        g.setColor(Color.GREEN);
        g.fillOval(sx - 5, sy - 5, 10, 10);

        // 3) Punto final (último nodo) en rojo
        Point end = history.get(history.size() - 1);
        int ex = end.x * cellW + cellW/2;
        int ey = getHeight() - (end.y * cellH + cellH / 2);  // ← AQUÍ
        g.setColor(Color.RED);
        g.fillOval(ex - 5, ey - 5, 10, 10);
    }
}
