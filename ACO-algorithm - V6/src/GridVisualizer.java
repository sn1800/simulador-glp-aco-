import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class GridVisualizer extends JPanel {
    static final int GRID_WIDTH = 70;
    static final int GRID_HEIGHT = 50;
    static final int CELL_SIZE = 15;

    public static class Punto {
        int x, y;
        public Punto(int x, int y) { this.x = x; this.y = y; }
    }

    static class Ruta {
        Punto origen;
        Punto destino;
        Color color;
        Ruta(Punto origen, Punto destino, Color color) {
            this.origen = origen;
            this.destino = destino;
            this.color = color;
        }
    }

    List<Ruta> rutas = new ArrayList<>();
    List<Punto> camiones = new ArrayList<>();
    List<Punto> clientes = new ArrayList<>();
    List<Punto> bloqueos = new ArrayList<>();
    boolean[][] bloqueado;

    Color[] colores = { Color.BLUE, Color.MAGENTA, Color.ORANGE, Color.CYAN, Color.PINK };

    public GridVisualizer(List<Punto> nodos, List<List<Integer>> rutasPorCamion, boolean[][] bloqueado) {
        this.bloqueado = bloqueado;

        for (int i = 0; i < rutasPorCamion.size(); i++) {
            List<Integer> ruta = rutasPorCamion.get(i);
            Color color = colores[i % colores.length];

            for (int j = 1; j < ruta.size(); j++) {
                Punto origen = nodos.get(ruta.get(j - 1));
                Punto destino = nodos.get(ruta.get(j));
                List<Punto> camino = obtenerCaminoReal(origen, destino);
                for (int k = 0; k < camino.size() - 1; k++) {
                    rutas.add(new Ruta(camino.get(k), camino.get(k + 1), color));
                }
            }

            for (int idx : ruta) {
                Punto p = nodos.get(idx);
                if (idx == 0) camiones.add(p);
                else clientes.add(p);
            }
        }

        for (int i = 0; i < bloqueado.length; i++) {
            for (int j = 0; j < bloqueado[0].length; j++) {
                if (bloqueado[i][j]) bloqueos.add(new Punto(i, j));
            }
        }
    }

    private List<Punto> obtenerCaminoReal(Punto a, Punto b) {
        Queue<Punto> q = new LinkedList<>();
        Map<String, Punto> parent = new HashMap<>();
        boolean[][] visited = new boolean[GRID_WIDTH][GRID_HEIGHT];

        q.add(a);
        visited[a.x][a.y] = true;

        while (!q.isEmpty()) {
            Punto curr = q.poll();
            if (curr.x == b.x && curr.y == b.y) break;

            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = curr.x + d[0], ny = curr.y + d[1];
                if (nx >= 0 && nx < GRID_WIDTH && ny >= 0 && ny < GRID_HEIGHT && !visited[nx][ny] && !bloqueado[nx][ny]) {
                    visited[nx][ny] = true;
                    Punto next = new Punto(nx, ny);
                    q.add(next);
                    parent.put(nx + "," + ny, curr);
                }
            }
        }

        List<Punto> path = new ArrayList<>();
        Punto step = b;
        while (!(step.x == a.x && step.y == a.y)) {
            path.add(step);
            step = parent.get(step.x + "," + step.y);
            if (step == null) return new ArrayList<>();
        }
        path.add(a);
        Collections.reverse(path);
        return path;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Leyenda de colores por camión
        g.setFont(new Font("Arial", Font.PLAIN, 12));
        for (int i = 0; i < colores.length; i++) {
            g.setColor(colores[i]);
            g.fillRect(10, 10 + i * 20, 15, 15);
            g.setColor(Color.BLACK);
            g.drawString("Camión " + (i + 1), 30, 22 + i * 20);
        }
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i <= GRID_WIDTH; i++)
            g.drawLine(i * CELL_SIZE, 0, i * CELL_SIZE, GRID_HEIGHT * CELL_SIZE);
        for (int j = 0; j <= GRID_HEIGHT; j++)
            g.drawLine(0, j * CELL_SIZE, GRID_WIDTH * CELL_SIZE, j * CELL_SIZE);

        for (Punto b : bloqueos) {
            g.setColor(Color.BLACK);
            g.fillRect(b.x * CELL_SIZE + 2, b.y * CELL_SIZE + 2, CELL_SIZE - 4, CELL_SIZE - 4);
        }

        for (Ruta ruta : rutas) {
            g.setColor(ruta.color);
            int x1 = ruta.origen.x * CELL_SIZE + CELL_SIZE / 2;
            int y1 = ruta.origen.y * CELL_SIZE + CELL_SIZE / 2;
            int x2 = ruta.destino.x * CELL_SIZE + CELL_SIZE / 2;
            int y2 = ruta.destino.y * CELL_SIZE + CELL_SIZE / 2;
            g.drawLine(x1, y1, x2, y2);
        }

        g.setFont(new Font("Arial", Font.BOLD, 10));
        for (int i = 0; i < clientes.size(); i++) {
            Punto p = clientes.get(i);
            g.setColor(Color.RED);
            g.fillOval(p.x * CELL_SIZE + 4, p.y * CELL_SIZE + 4, CELL_SIZE - 8, CELL_SIZE - 8);
            g.setColor(Color.BLACK);
            g.drawString(String.valueOf(i + 1), p.x * CELL_SIZE + 6, p.y * CELL_SIZE + 10);
        }

        for (Punto p : camiones) {
            g.setColor(Color.GREEN);
            g.fillRect(p.x * CELL_SIZE + 2, p.y * CELL_SIZE + 2, CELL_SIZE - 4, CELL_SIZE - 4);
        }
    }
}
