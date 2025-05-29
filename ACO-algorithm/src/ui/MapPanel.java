package ui;

import core.*;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
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
    private static final int CELL_SIZE = 12;   // píxeles por celda (ajusta a tu gusto)
    final int depositoX = 12, depositoY = 8;
    private List<Bloqueo> bloqueos = Collections.emptyList();
    private int currentTime = 0;

    /** Permite al controlador cambiar qué camión destacar */
    public void setSelectedCamion(Camion c) {
        this.selectedCamion = c;
        repaint();
    }
    public void setBloqueos(List<Bloqueo> b) {
        this.bloqueos = b;
    }
    public void setCurrentTime(int t) {
        this.currentTime = t;
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

        // 3) Dibujar bloqueos
        Graphics2D g2 = (Graphics2D)g.create();
        g2.setColor(new Color(255,0,0,128));
        g2.setStroke(new BasicStroke(Math.min(cellW,cellH)/4f));
        for (Bloqueo b : bloqueos) {
            if (currentTime >= b.getStartMin() && currentTime < b.getEndMin()) {
                List<Point> pts = b.getNodes();
                for (int i = 0; i < pts.size()-1; i++) {
                    Point a = pts.get(i), c = pts.get(i+1);
                    int x1 = a.x*cellW + cellW/2,
                            y1 = (GRID_ROWS-1 - a.y)*cellH + cellH/2;
                    int x2 = c.x*cellW + cellW/2,
                            y2 = (GRID_ROWS-1 - c.y)*cellH + cellH/2;
                    g2.drawLine(x1, y1, x2, y2);
                }
            }
        }
        g2.dispose();

        // 4) Dibujar malla ligera
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i <= GRID_COLS; i++) {
            int x = i * cellW;
            g.drawLine(x, 0, x, getHeight());
        }
        for (int j = 0; j <= GRID_ROWS; j++) {
            int y = j * cellH;
            g.drawLine(0, y, getWidth(), y);
        }

        // 5) Dibujar pedidos pendientes en rojo
        g.setColor(Color.RED);
        for (Pedido p : planner.getPedidos()) {
            if (!p.isAtendido() && !p.isDescartado()) {
                int x = p.getX() * cellW;
                int y = getHeight() - (p.getY() * cellH + cellH);
                g.fillOval(x, y, cellW, cellH);
            }
        }

        // 6) Dibujar tanques intermedios en naranja
        g.setColor(Color.ORANGE);
        for (Tanque t : planner.getTanquesIntermedios()) {
            int x = t.getX() * cellW;
            int y = getHeight() - (t.getY() * cellH + cellH);
            g.drawRect(x, y, cellW, cellH);
        }

        // 7) Dibujar camiones en azul
        g.setColor(Color.BLUE);
        for (Camion c : planner.getFlota()) {
            int x = c.getX() * cellW;
            int y = getHeight() - (c.getY() * cellH + cellH);
            g.fillRect(x, y, cellW, cellH);
        }

        // 8) Ruta Manhattan segmentada (si está seleccionada)
        if (selectedCamion != null && selectedCamion.getRuta() != null) {
            drawRuta(g, selectedCamion.getRuta(), cellW, cellH);
        }

        // 9) Trayectoria histórica en magenta del seleccionado
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
