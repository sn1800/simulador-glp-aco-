import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

public class ACOPlanner {
    public static void main(String[] args) {
        System.out.println("📦 Inicializando estado global...");

        List<Pedido> pedidos = cargarPedidos("pedidos.txt");
        List<Bloqueo> bloqueos = cargarBloqueos("bloqueos.txt");

        if (pedidos.isEmpty()) {
            System.out.println("❌ No se cargaron pedidos válidos. Terminando ejecución.");
            return;
        }

        System.out.println("Pedidos cargados: " + pedidos.size());
        System.out.println("Bloqueos cargados: " + bloqueos.size());
        System.out.println("Depósitos creados: 3");
        System.out.println("Flota creada: 20 camiones.");
        System.out.println("Inicialización completa.");
        System.out.println("--- Iniciando Simulación por 57600 minutos ---");

        AntColonyOptimizer aco = new AntColonyOptimizer(pedidos, bloqueos);
        aco.simularTiempoReal();
    }

    static class Pedido {
        int tiempo;
        int x, y;
        double volumen;
        int tiempoEntrega;
        boolean asignado = false;

        public Pedido(int tiempo, int x, int y, double volumen, int tiempoEntrega) {
            this.tiempo = tiempo;
            this.x = x;
            this.y = y;
            this.volumen = volumen;
            this.tiempoEntrega = tiempoEntrega;
        }
    }

    static class Bloqueo {
        int inicio;
        int fin;
        List<int[]> coords;

        public Bloqueo(int inicio, int fin, List<int[]> coords) {
            this.inicio = inicio;
            this.fin = fin;
            this.coords = coords;
        }

        public boolean afecta(int tiempo, int x1, int y1, int x2, int y2) {
            if (tiempo < inicio || tiempo > fin) return false;
            for (int[] c : coords) {
                if ((c[0] == x1 && c[1] == y1) || (c[0] == x2 && c[1] == y2)) return true;
            }
            return false;
        }
    }

    static class Camion {
        String id;
        double capacidad;
        double capacidadDisponible;

        public Camion(String id, double capacidad) {
            this.id = id;
            this.capacidad = capacidad;
            this.capacidadDisponible = capacidad;
        }
    }
    static class GridVisualizerGUI extends JFrame {
        int ancho, alto;
        char[][] grid;
        JPanel panel;

        public GridVisualizerGUI(int ancho, int alto) {
            this.ancho = ancho;
            this.alto = alto;
            setTitle("Simulación GLP");
            setSize(ancho * 10 + 50, alto * 10 + 50);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLocationRelativeTo(null);
            grid = new char[alto][ancho];
            panel = new JPanel() {
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    for (int y = 0; y < alto; y++) {
                        for (int x = 0; x < ancho; x++) {
                            switch (grid[y][x]) {
                                case 'P': g.setColor(Color.RED); break;
                                case 'A': g.setColor(Color.GREEN); break;
                                case 'C': g.setColor(Color.BLUE); break;
                                case 'B': g.setColor(Color.YELLOW); break;
                                default: g.setColor(Color.LIGHT_GRAY); break;
                            }
                            g.fillRect(x * 10, y * 10, 10, 10);
                            g.setColor(Color.BLACK);
                            g.drawRect(x * 10, y * 10, 10, 10);
                        }
                    }
                }
            };
            add(panel);
            setVisible(true);
        }

        public void render(int t, List<Pedido> pedidos, List<Camion> camiones, List<Bloqueo> bloqueos, int depositoX, int depositoY)
        {
            for (char[] row : grid) Arrays.fill(row, '.');
            for (Bloqueo b : bloqueos) {
                if (t >= b.inicio && t <= b.fin) {
                    for (int[] c : b.coords) {
                        int bx = c[0], by = c[1];
                        if (bx >= 0 && bx < ancho && by >= 0 && by < alto) {
                            grid[by][bx] = 'B';
                        }
                    }
                }
            }
            // Pintar punto del depósito primero
            if (depositoX >= 0 && depositoX < ancho && depositoY >= 0 && depositoY < alto) {
                grid[depositoY][depositoX] = 'C';
            }

            // Luego pintar todos los pedidos activos (asignados o no)
            for (Pedido p : pedidos) {
                if (p != null && p.x >= 0 && p.x < ancho && p.y >= 0 && p.y < alto) {
                    grid[p.y][p.x] = p.asignado ? 'A' : 'P';
                }
            }

            System.out.println("🧾 MATRIZ FINAL DEL GRID:");
            for (int y = 0; y < alto; y++) {
                System.out.printf("Fila %02d: ", y);
                for (int x = 0; x < ancho; x++) {
                    System.out.print(grid[y][x] + " ");
                }
                System.out.println();
            }
            System.out.println("====================================");

            System.out.println("📍 Coordenadas de los pedidos en el grid:");
            for (Pedido p : pedidos) {
                if (p != null && p.x >= 0 && p.x < ancho && p.y >= 0 && p.y < alto) {
                    System.out.printf("Pedido en (%d, %d) -> asignado: %b%n", p.x, p.y, p.asignado);
                }
            }
            System.out.println("====================================");

            // Repaint GUI
            panel.repaint();
        }
    }
    static class AntColonyOptimizer {
        List<Pedido> pedidos;
        List<Bloqueo> bloqueos;
        List<Pedido> pedidosActivos = new ArrayList<>();
        int tMax = 57600;
        List<Camion> flota;
        int depositoX = 12, depositoY = 8;

        double[][] feromonas;
        double alpha = 1.0, beta = 2.0, rho = 0.5;

        GridVisualizerGUI visualizer;

        public AntColonyOptimizer(List<Pedido> pedidos, List<Bloqueo> bloqueos) {
            this.pedidos = pedidos;
            this.bloqueos = bloqueos;
            this.pedidos.sort(Comparator.comparingInt(p -> p.tiempo));
            visualizer = new GridVisualizerGUI(70, 50);
            inicializarFlota();
        }

        public void simularTiempoReal() {
            int indicePedido = 0;

            for (int t = 0; t <= tMax; t++) {
                if (t % 60 == 0)
                    System.out.printf("--- Tiempo: %dd %02dh %02dm ---%n", t / 1440, (t / 60) % 24, t % 60);

                while (indicePedido < pedidos.size() && pedidos.get(indicePedido).tiempo == t) {
                    Pedido p = pedidos.get(indicePedido);
                    pedidosActivos.add(p);
                    System.out.printf("⏰ t=%d -> Nueva Parte Pedido ID:%d en CustPart[%d,(%d,%d), Dem:%.1f, Lim:%d] recibida.%n",
                            t, indicePedido, indicePedido, p.x, p.y, p.volumen, p.tiempoEntrega);
                    indicePedido++;
                }

                if (!pedidosActivos.isEmpty()) {
                    ejecutarACO(t);
                }
                if (t % 60 == 0) visualizer.render(t, pedidosActivos, flota, bloqueos, depositoX, depositoY);
            }
        }

        private void ejecutarACO(int tiempoActual) {
           // System.out.printf("=== REPLANIFICANDO RUTAS en t=%d para %d partes ===%n", tiempoActual, pedidosActivos.size());

            List<Pedido> noAsignados = new ArrayList<>();
            for (Pedido p : pedidosActivos) {
                if (!p.asignado) noAsignados.add(p);
            }

            int n = noAsignados.size();
            if (n == 0) return;
            feromonas = new double[n][n];
            for (int i = 0; i < n; i++) Arrays.fill(feromonas[i], 1.0);

            for (Camion c : flota) {
                double capacidadRestante = c.capacidadDisponible;
                List<Pedido> asignados = new ArrayList<>();
                int actualX = depositoX, actualY = depositoY;
                boolean[] visitado = new boolean[n];

                while (true) {
                    int siguiente = -1;
                    double suma = 0;
                    double[] probs = new double[n];

                    for (int i = 0; i < n; i++) {
                        Pedido p = noAsignados.get(i);
                        if (!visitado[i] && !p.asignado && capacidadRestante >= p.volumen &&
                                !hayBloqueo(tiempoActual, actualX, actualY, p.x, p.y)) {
                            double desirability = Math.pow(feromonas[i][i], alpha) *
                                    Math.pow(1.0 / (1 + distancia(actualX, actualY, p.x, p.y)), beta);
                            probs[i] = desirability;
                            suma += desirability;
                        } else {
                            probs[i] = 0;
                        }
                    }

                    if (suma == 0) break;

                    double r = Math.random() * suma;
                    double acc = 0;
                    for (int i = 0; i < n; i++) {
                        acc += probs[i];
                        if (r <= acc) {
                            siguiente = i;
                            break;
                        }
                    }

                    if (siguiente == -1) break;

                    Pedido p = noAsignados.get(siguiente);
                    p.asignado = true;
                    asignados.add(p);
                    capacidadRestante -= p.volumen;
                    actualX = p.x;
                    actualY = p.y;
                    visitado[siguiente] = true;
                }

                if (!asignados.isEmpty()) {
                    c.capacidadDisponible = capacidadRestante;
                    double total = asignados.stream().mapToDouble(p -> p.volumen).sum();
                    String pedidosStr = asignados.stream()
                            .map(p -> pedidosActivos.indexOf(p) + "(" + p.volumen + "," + p.tiempo + ")")
                            .reduce((a, b) -> a + ", " + b).orElse("");
                    System.out.printf("🚚 %s atenderá %d pedidos | Carga total: %.1f m³ -> %s%n",
                            c.id, asignados.size(), total, pedidosStr);
                }
            }

            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    feromonas[i][j] *= (1 - rho);
                }
            }
            for (Pedido p : pedidosActivos) {
                if (p.asignado) {
                    int idx = noAsignados.indexOf(p);
                    if (idx != -1) feromonas[idx][idx] += 1.0;
                }
            }
        }

        boolean hayBloqueo(int tiempo, int x1, int y1, int x2, int y2) {
            for (Bloqueo b : bloqueos) {
                if (b.afecta(tiempo, x1, y1, x2, y2)) {
                    System.out.printf("⛔ Bloqueo activo en tiempo %d afecta (%d,%d) → (%d,%d)%n", tiempo, x1, y1, x2, y2);
                    return true;
                }
            }
            return false;
        }

        void inicializarFlota() {
            flota = new ArrayList<>();
            for (int i = 1; i <= 2; i++) flota.add(new Camion("TA0" + i, 25));
            for (int i = 1; i <= 4; i++) flota.add(new Camion("TB0" + i, 15));
            for (int i = 1; i <= 4; i++) flota.add(new Camion("TC0" + i, 10));
            for (int i = 1; i <= 10; i++) flota.add(new Camion("TD" + String.format("%02d", i), 5));
        }
    }

    public static List<Pedido> cargarPedidos(String archivo) {
        List<Pedido> lista = new ArrayList<>();
        System.out.println("📄 Cargando archivos de pedidos y bloqueos...");

        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                try {
                    String[] partes = linea.split(":", 2);
                    String tiempoStr = partes[0].trim();
                    String[] datos = partes[1].split(",");

                    int tiempo = convertirATiempoMinutos(tiempoStr);
                    int x = Integer.parseInt(datos[0].trim());
                    int y = Integer.parseInt(datos[1].trim());
                    double volumen = Double.parseDouble(datos[3].replace("m3", "").trim());
                    int tiempoLimite = tiempo + convertirATiempoMinutos(datos[4].trim());

                    lista.add(new Pedido(tiempo, x, y, volumen, tiempoLimite));
                } catch (Exception e) {
                    System.out.println("❌ Error al convertir línea de pedido: " + linea);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lista;
    }

    public static List<Bloqueo> cargarBloqueos(String archivo) {
        List<Bloqueo> lista = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                try {
                    String[] partes = linea.split(":");
                    String[] tiempo = partes[0].split("-");
                    int inicio = convertirATiempoMinutos(tiempo[0].trim());
                    int fin = convertirATiempoMinutos(tiempo[1].trim());
                    String[] coords = partes[1].split(",");
                    List<int[]> puntos = new ArrayList<>();
                    for (int i = 0; i < coords.length; i += 2) {
                        puntos.add(new int[]{Integer.parseInt(coords[i].trim()), Integer.parseInt(coords[i + 1].trim())});
                    }
                    lista.add(new Bloqueo(inicio, fin, puntos));
                } catch (Exception e) {
                    System.out.println("❌ Error al convertir línea de bloqueo: " + linea);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lista;
    }

    public static int convertirATiempoMinutos(String texto) {
        try {
            int dias = 0, horas = 0, minutos = 0;
            if (texto.contains("d")) {
                String[] partesDia = texto.split("d", 2);
                dias = Integer.parseInt(partesDia[0].replaceAll("[^0-9]", ""));
                texto = partesDia[1];
            }
            if (texto.contains("h")) {
                String[] partesHora = texto.split("h", 2);
                horas = Integer.parseInt(partesHora[0].replaceAll("[^0-9]", ""));
                texto = partesHora[1];
            }
            if (texto.contains("m")) {
                minutos = Integer.parseInt(texto.replaceAll("[^0-9]", ""));
            }
            return dias * 24 * 60 + horas * 60 + minutos;
        } catch (Exception e) {
            System.out.println("❌ Error al convertir el tiempo: " + texto);
            return -1;
        }
    }

    public static double distancia(int x1, int y1, int x2, int y2) {
        return Math.abs(x2 - x1) + Math.abs(y2 - y1);
    }
}
