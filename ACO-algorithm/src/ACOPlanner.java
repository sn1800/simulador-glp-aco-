import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

public class ACOPlanner {
    public static void main(String[] args) throws FileNotFoundException {
        PrintStream out = new PrintStream("salida_simulacion.txt");
        System.setOut(out);  // Redirige todo el output a un archivo
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
        boolean atendido  = false;

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
        double combustibleGastado;  // galones consumidos en total
        double tara;

        public Camion(String id, double capacidad, double tara) {
            this.id = id;
            this.capacidad = capacidad;
            this.combustibleGastado = 0.0;
            this.tara = tara;
            reset();
        }

        void reset() {
            this.disponible = capacidad;
            this.x = 12;
            this.y = 8;
            this.libreEn = 0;
        }
    }
    static class AntColonyOptimizer {
        List<Pedido> pedidos;
        List<Bloqueo> bloqueos;
        List<Camion> flota;
        Map<String, Map<String, String>> averiasPorTurno = new HashMap<>(); // turno -> camionID -> tipo
        Set<String> camionesInhabilitados = new HashSet<>(); // IDs de camiones afectados actualmente
        Set<String> camionesYaProcesadosTurno = new HashSet<>(); // IDs de camiones ya tratados en el turno actual
        Set<String> camionesQueRecibieronPedidosEnT = new HashSet<>(); // IDs de camiones que sí fueron usados este minuto
        int tMax = 57600;
        double[][] feromonas;
        final int N = 100; // máximo de ubicaciones
        final int ITERACIONES = 50;
        final int HORMIGAS = 10;
        final double ALPHA = 1, BETA = 2, RHO = 0.1, Q = 1000;
        int depositoX = 12, depositoY = 8;
        GridVisualizerGUI visualizador;

        public AntColonyOptimizer(List<Pedido> pedidos, List<Bloqueo> bloqueos) {
            this.pedidos = pedidos;
            this.bloqueos = bloqueos;
            this.flota = inicializarFlota();
            this.visualizador = new GridVisualizerGUI(70, 50);
            this.feromonas = new double[N][N];
            for (double[] row : feromonas) Arrays.fill(row, 1.0);
            cargarAverias("averias.txt");
        }

        public void simular() {
            Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
            for (Pedido p : pedidos) {
                pedidosPorTiempo.computeIfAbsent(p.tiempoCreacion, k -> new ArrayList<>()).add(p);
            }
            String turnoAnterior = "";
            for (int t = 0; t <= tMax; t++) {
                camionesQueRecibieronPedidosEnT.clear();
                if (t % 60 == 0)
                    System.out.printf("\n--- Tiempo %dd %02dh %02dm ---%n", t / 1440, (t / 60) % 24, t % 60);

                for (Camion c : flota) {
                    if (c.libreEn <= t) c.reset();
                }

                List<Pedido> nuevos = pedidosPorTiempo.getOrDefault(t, new ArrayList<>());
                for (Pedido p : nuevos) {
                    System.out.printf("🆕 Pedido #%d en (%d,%d), vol %.1fm³, límite t+%d%n", p.id, p.x, p.y, p.volumen, p.tiempoLimite);
                }
                String turnoActual = turnoDeMinuto(t);
                if (!turnoActual.equals(turnoAnterior)) {
                    camionesYaProcesadosTurno.clear();
                    turnoAnterior = turnoActual;
                }
                Map<String, String> averiasTurno = averiasPorTurno.getOrDefault(turnoActual, new HashMap<>());
                for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
                    String camionId = entry.getKey();
                    String tipo = entry.getValue();
                    Camion c = flota.stream().filter(cam -> cam.id.equals(camionId)).findFirst().orElse(null);
                    if (c != null && c.libreEn <= t && !camionesInhabilitados.contains(c.id) && !camionesYaProcesadosTurno.contains(c.id)) {
                        int penalizacion = tipo.equals("T1") ? 30 : tipo.equals("T2") ? 60 : 90;
                        c.libreEn = t + penalizacion;
                        camionesInhabilitados.add(c.id);
                        camionesYaProcesadosTurno.add(c.id);
                        System.out.printf("⛔ Camión %s inhabilitado por avería tipo %s hasta t+%d%n", c.id, tipo, c.libreEn);
                    }
                }
                camionesQueRecibieronPedidosEnT.clear(); // Asegúrate de esto al inicio de cada minuto
                final int tiempoActual = t;
                camionesInhabilitados.removeIf(id -> {
                    boolean liberado = flota.stream().anyMatch(c -> c.id.equals(id) && c.libreEn <= tiempoActual);
                    if (liberado) {
                        System.out.printf("✅ Camión %s vuelve a estar disponible tras avería%n", id);
                    }
                    return liberado;
                });

                asignarPedidos(t);
                // Logs: camiones libres que no fueron asignados
                if (!pedidosPorTiempo.getOrDefault(t, new ArrayList<>()).isEmpty()) {
                    for (Camion c : flota) {
                        if (c.libreEn <= t && !camionesInhabilitados.contains(c.id) && !camionesQueRecibieronPedidosEnT.contains(c.id)) {
                            System.out.printf("ℹ️ Camión %s está disponible pero no se le asignaron pedidos en t+%d%n", c.id, t);
                        }
                    }
                }
                if (t % 60 == 0) visualizador.render(t, pedidos, flota, bloqueos, depositoX, depositoY);
            }
            // resumen final de consumo
            System.out.println("\n📊 Resumen de Consumo por Camión:");
            double totalConsumo = 0.0;
            for (Camion c : flota) {
                System.out.printf("🚚 %s consumió %.2f galones%n", c.id, c.combustibleGastado);
                totalConsumo += c.combustibleGastado;
            }
            System.out.printf("🔧 Consumo total acumulado por toda la flota: %.2f galones%n", totalConsumo);
        }

        private void asignarPedidos(int t) {
            for (Pedido p : pedidos) {
                if (p.tiempoCreacion > t || p.atendido) continue;

                Camion mejor = null;
                double mejorCosto = Double.MAX_VALUE;

                for (Camion c : flota) {
                    if (c.libreEn > t || camionesInhabilitados.contains(c.id)) continue;
                    if (c.disponible < p.volumen) continue;
                    if (hayBloqueo(t, c.x, c.y, p.x, p.y)) continue;
                    double d1 = distancia(depositoX, depositoY, p.x, p.y);
                    double tiempoIda = d1 / 0.5;
                    double tiempoTotal = tiempoIda * 2;

                    if (t + (int) tiempoTotal > p.tiempoLimite) continue;

                    if (d1 < mejorCosto) {
                        mejor = c;
                        mejorCosto = d1;
                    }
                }

                if (mejor != null) {
                    double d1 = distancia(depositoX, depositoY, p.x, p.y);
                    double tiempoIda = d1 / 0.5;
                    double tiempoTotal = tiempoIda * 2;
                    int fin = t + (int) tiempoTotal;
                    mejor.libreEn = fin;
                    mejor.disponible -= p.volumen;
                    double consumo = calcularConsumo(d1, p.volumen, mejor.tara);
                    mejor.combustibleGastado += consumo;
                    camionesQueRecibieronPedidosEnT.add(mejor.id);
                    p.atendido = true;
                    System.out.printf("✅ %s entrega Pedido #%d: (%d,%d), vol %.1f → t+%d, regreso t+%d, consumo %.2f gal, disp. %.1f m³%n",
                            mejor.id, p.id, p.x, p.y, p.volumen, t + (int) tiempoIda, fin, consumo, mejor.disponible);
                }
            }
        }

        private double calcularConsumo(double distancia, double volumen, double tara) {
            double pesoTotal = tara + volumen * 500; // volumen en m³ → 500 kg/m³
            return 0.00005 * distancia * pesoTotal;  // fórmula provista por profesores
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

            // TA: 2 camiones de 25 m³ - tara 7500 kg
            flota.add(new Camion("TA01", 25.0, 7500));
            flota.add(new Camion("TA02", 25.0, 7500));

            // TB: 4 camiones de 15 m³ - tara 5000 kg
            flota.add(new Camion("TB01", 15.0, 5000));
            flota.add(new Camion("TB02", 15.0, 5000));
            flota.add(new Camion("TB03", 15.0, 5000));
            flota.add(new Camion("TB04", 15.0, 5000));

            // TC: 4 camiones de 10 m³ - tara 4000 kg
            flota.add(new Camion("TC01", 10.0, 4000));
            flota.add(new Camion("TC02", 10.0, 4000));
            flota.add(new Camion("TC03", 10.0, 4000));
            flota.add(new Camion("TC04", 10.0, 4000));

            // TD: 10 camiones de 5 m³ - tara 3000 kg
            for (int i = 1; i <= 10; i++) {
                flota.add(new Camion(String.format("TD%02d", i), 5.0, 3000));
            }

            return flota;
        }

        private void cargarAverias(String filename) {
            try {
                List<String> lineas = Files.readAllLines(Paths.get(filename));
                for (String linea : lineas) {
                    String[] partes = linea.split("_");
                    if (partes.length == 3) {
                        String turno = partes[0];
                        String camionId = partes[1];
                        String tipo = partes[2];
                        averiasPorTurno
                                .computeIfAbsent(turno, k -> new HashMap<>())
                                .put(camionId, tipo);
                    }
                }
            } catch (IOException e) {
                System.out.println("⚠️ No se pudo leer averias.txt: " + e.getMessage());
            }
        }
        private String turnoDeMinuto(int t) {
            int minutoDia = t % 1440;
            if (minutoDia < 480) return "T1";
            else if (minutoDia < 960) return "T2";
            else return "T3";
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
                    grid[p.y][p.x] = p.atendido ? 'E' : 'P';
                }
            }

            if (depositoX >= 0 && depositoX < ancho && depositoY >= 0 && depositoY < alto) {
                grid[depositoY][depositoX] = 'C';
            }

            panel.repaint();
        }
    }
}


