import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

public class ACOPlanner {
    public static void main(String[] args) {
        System.out.println("📦 Inicializando estado global...");

        // CARGA DE ARCHIVOS DE DATOS
        List<Pedido> pedidos = cargarPedidos("pedidos.txt");
        if (pedidos.isEmpty()) {
            System.out.println("❌ No se cargaron pedidos válidos. Terminando ejecución.");
            return;
        }
        System.out.println("Pedidos cargados: " + pedidos.size());
        List<Bloqueo> bloqueos = cargarBloqueos("bloqueos.txt");
        if (bloqueos.isEmpty()) {
            System.out.println("❌ No se cargaron bloqueos válidos. Terminando ejecución.");
            return;
        }
        System.out.println("Bloqueos cargados: " + bloqueos.size());

        System.out.println("Inicialización completa.");
        System.out.println("--- Iniciando Simulación por 57600 minutos ---");

        AntColonyOptimizer aco = new AntColonyOptimizer(pedidos, bloqueos);
        aco.simular();
    }

    static class Pedido {
        int id;
        int tiempoCreacion;
        int x, y;
        double volumen;
        int tiempoLimite;
        boolean entregado = false;

        public Pedido(int id, int tiempoCreacion, int x, int y, double volumen, int tiempoLimite) {
            this.id = id;
            this.tiempoCreacion = tiempoCreacion;
            this.x = x;
            this.y = y;
            this.volumen = volumen;
            this.tiempoLimite = tiempoLimite;
        }
    }

    static class Bloqueo {
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

    static class Camion {
        String id;
        double capacidad;
        double disponible;
        int x = 12, y = 8;
        int libreEn = 0; // minuto en que estará libre

        public Camion(String id, double capacidad) {
            this.id = id;
            this.capacidad = capacidad;
            this.disponible = capacidad;
        }

        void reset() {
            disponible = capacidad;
            x = 12;
            y = 8;
            libreEn = 0;
        }
    }
    static class AntColonyOptimizer {
        List<Pedido> pedidos;
        List<Bloqueo> bloqueos;
        List<Camion> flota;
        int tMax = 57600;
        int depositoX = 12, depositoY = 8;
        GridVisualizerGUI visualizador;

        public AntColonyOptimizer(List<Pedido> pedidos, List<Bloqueo> bloqueos) {
            this.pedidos = pedidos;
            this.bloqueos = bloqueos;
            this.flota = inicializarFlota();
            this.visualizador = new GridVisualizerGUI(70, 50);
        }

        public void simular() {
            Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
            for (Pedido p : pedidos) {
                pedidosPorTiempo.computeIfAbsent(p.tiempoCreacion, k -> new ArrayList<>()).add(p);
            }

            for (int t = 0; t <= tMax; t++) {
                if (t % 60 == 0)
                    System.out.printf("\n--- Tiempo %dd %02dh %02dm ---%n", t / 1440, (t / 60) % 24, t % 60);

                for (Camion c : flota) {
                    if (c.libreEn <= t) c.reset();
                }

                List<Pedido> nuevos = pedidosPorTiempo.getOrDefault(t, new ArrayList<>());
                for (Pedido p : nuevos) {
                    System.out.printf("🆕 Pedido #%d en (%d,%d), vol %.1fm³, límite t+%d%n", p.id, p.x, p.y, p.volumen, p.tiempoLimite);
                }

                asignarPedidos(t);

                if (t % 60 == 0) visualizador.render(t, pedidos, flota, bloqueos, depositoX, depositoY);
            }
        }

        private void asignarPedidos(int t) {
            List<Pedido> activos = new ArrayList<>();
            for (Pedido p : pedidos) {
                if (!p.entregado && p.tiempoCreacion <= t && p.tiempoLimite > t) {
                    activos.add(p);
                }
            }

            for (Camion c : flota) {
                if (c.libreEn > t) continue;

                Pedido mejor = null;
                double mejorScore = -1;
                for (Pedido p : activos) {
                    if (c.disponible < p.volumen) continue;
                    if (hayBloqueo(t, c.x, c.y, p.x, p.y)) continue;
                    int ida = distancia(c.x, c.y, p.x, p.y);
                    int vuelta = distancia(p.x, p.y, depositoX, depositoY);
                    int llegada = t + ida;
                    if (llegada > p.tiempoLimite) continue;

                    double score = 1.0 / (ida + vuelta);
                    if (score > mejorScore) {
                        mejorScore = score;
                        mejor = p;
                    }
                }

                if (mejor != null) {
                    entregar(c, mejor, t);
                    activos.remove(mejor); // Evita que otros camiones lo vean en esta iteración
                }
            }
        }

        private void entregar(Camion c, Pedido p, int t) {
            int ida = distancia(c.x, c.y, p.x, p.y);
            int vuelta = distancia(p.x, p.y, depositoX, depositoY);
            int tiempoTotal = ida + vuelta;

            c.libreEn = t + tiempoTotal;
            c.disponible -= p.volumen;
            c.x = depositoX;
            c.y = depositoY;
            p.entregado = true;

            System.out.printf("✅ %s entrega Pedido #%d: (%d,%d), vol %.1f → t+%d, regreso t+%d%n",
                    c.id, p.id, p.x, p.y, p.volumen, t + ida, c.libreEn);
        }

        private boolean hayBloqueo(int t, int x1, int y1, int x2, int y2) {
            for (Bloqueo b : bloqueos) {
                if (b.afecta(t, x1, y1, x2, y2)) return true;
            }
            return false;
        }

        private int distancia(int x1, int y1, int x2, int y2) {
            return Math.abs(x2 - x1) + Math.abs(y2 - y1);
        }

        private List<Camion> inicializarFlota() {
            List<Camion> flota = new ArrayList<>();
            for (int i = 1; i <= 2; i++) flota.add(new Camion("TA0" + i, 25));
            for (int i = 1; i <= 4; i++) flota.add(new Camion("TB0" + i, 15));
            for (int i = 1; i <= 4; i++) flota.add(new Camion("TC0" + i, 10));
            for (int i = 1; i <= 10; i++) flota.add(new Camion("TD" + String.format("%02d", i), 5));
            return flota;
        }
    }

    public static List<Pedido> cargarPedidos(String archivo) {
        List<Pedido> lista = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            int id = 0;
            while ((linea = br.readLine()) != null) {
                try {
                    String[] partes = linea.split(":", 2);
                    int tiempo = convertirATiempoMinutos(partes[0].trim());
                    String[] datos = partes[1].split(",");
                    int x = Integer.parseInt(datos[0].trim());
                    int y = Integer.parseInt(datos[1].trim());
                    double vol = Double.parseDouble(datos[3].replace("m3", "").trim());
                    int limite = tiempo + convertirATiempoMinutos(datos[4].trim());
                    lista.add(new Pedido(id++, tiempo, x, y, vol, limite));
                } catch (Exception e) {
                    System.out.println("❌ Error en línea de pedido: " + linea);
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
                    int ini = convertirATiempoMinutos(tiempo[0].trim());
                    int fin = convertirATiempoMinutos(tiempo[1].trim());
                    String[] coords = partes[1].split(",");
                    List<int[]> puntos = new ArrayList<>();
                    for (int i = 0; i < coords.length; i += 2) {
                        puntos.add(new int[]{Integer.parseInt(coords[i].trim()), Integer.parseInt(coords[i + 1].trim())});
                    }
                    lista.add(new Bloqueo(ini, fin, puntos));
                } catch (Exception e) {
                    System.out.println("❌ Error en línea de bloqueo: " + linea);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lista;
    }

    public static int convertirATiempoMinutos(String texto) {
        int d = 0, h = 0, m = 0;
        try {
            if (texto.contains("d")) {
                String[] split = texto.split("d", 2);
                d = Integer.parseInt(split[0].replaceAll("\\D", ""));
                texto = split[1];
            }
            if (texto.contains("h")) {
                String[] split = texto.split("h", 2);
                h = Integer.parseInt(split[0].replaceAll("\\D", ""));
                texto = split[1];
            }
            if (texto.contains("m")) {
                m = Integer.parseInt(texto.replaceAll("\\D", ""));
            }
        } catch (Exception e) {
            System.out.println("❌ Error convirtiendo tiempo: " + texto);
        }
        return d * 1440 + h * 60 + m;
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
                                case 'E': g.setColor(Color.GREEN); break;
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

        public void render(int t, List<Pedido> pedidos, List<Camion> camiones, List<Bloqueo> bloqueos, int depositoX, int depositoY) {
            for (char[] row : grid) Arrays.fill(row, '.');

            for (Bloqueo b : bloqueos) {
                if (t >= b.inicio && t <= b.fin) {
                    for (String arista : b.aristasBloqueadas) {
                        String[] extremos = arista.split("[-,]");
                        int x = Integer.parseInt(extremos[0]);
                        int y = Integer.parseInt(extremos[1]);
                        if (x >= 0 && x < ancho && y >= 0 && y < alto) grid[y][x] = 'B';
                    }
                }
            }

            for (Pedido p : pedidos) {
                if (p.x >= 0 && p.x < ancho && p.y >= 0 && p.y < alto) {
                    grid[p.y][p.x] = p.entregado ? 'E' : 'P';
                }
            }

            if (depositoX >= 0 && depositoX < ancho && depositoY >= 0 && depositoY < alto) {
                grid[depositoY][depositoX] = 'C';
            }

            panel.repaint();
        }
    }
}


