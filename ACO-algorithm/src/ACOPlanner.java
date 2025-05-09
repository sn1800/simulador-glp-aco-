import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class ACOPlanner {
    public static void main(String[] args) throws FileNotFoundException {
        PrintStream out = new PrintStream("salida_simulacion.txt");
        System.setOut(out);
        System.out.println("📦 Inicializando estado global...");

        List<Pedido> pedidos = cargarPedidos("pedidos.txt");
        List<Bloqueo> bloqueos = cargarBloqueos("bloqueos.txt");
        Map<String, Map<String, String>> averiasPorTurno = cargarAverias("averias.txt");

        if (pedidos.isEmpty() || bloqueos.isEmpty()) {
            System.out.println("❌ Error cargando archivos.");
            return;
        }

        AntColonyOptimizerACO aco = new AntColonyOptimizerACO(pedidos, bloqueos, averiasPorTurno);
        aco.simularDiaADia();
    }

    static class Pedido {
        int id, x, y, tiempoCreacion, tiempoLimite;
        double volumen;
        boolean atendido = false;
        boolean descartado = false;

        public Pedido(int id, int tiempoCreacion, int x, int y, double volumen, int tiempoLimite) {
            this.id = id; this.tiempoCreacion = tiempoCreacion;
            this.x = x; this.y = y; this.volumen = volumen;
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
    static class AntColonyOptimizerACO {
        List<Pedido> pedidos;
        List<Bloqueo> bloqueos;
        Map<String, Map<String, String>> averiasPorTurno;
        List<Camion> flota;
        double[][] feromonas;
        final int N;
        final int ITERACIONES = 50;
        final int HORMIGAS = 10;
        final double ALPHA = 1, BETA = 2, RHO = 0.1, Q = 1000;
        final int depositoX = 12, depositoY = 8;
        Set<String> camionesInhabilitados = new HashSet<>();
        Set<String> averiasAplicadas = new HashSet<>();
        GridVisualizerGUI visualizador;
        
        public AntColonyOptimizerACO(List<Pedido> pedidos, List<Bloqueo> bloqueos, Map<String, Map<String, String>> averiasPorTurno) {
            this.pedidos = pedidos;
            this.bloqueos = bloqueos;
            this.averiasPorTurno = averiasPorTurno;
            this.flota = inicializarFlota();
            this.N = pedidos.size();
            this.feromonas = new double[N][N];
            for (double[] row : feromonas) Arrays.fill(row, 1.0);
            this.visualizador = new GridVisualizerGUI(70, 50);
        }
        public void simularDiaADia() {
            String turnoAnterior = "";
            Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
            for (Pedido p : pedidos) {
                pedidosPorTiempo.computeIfAbsent(p.tiempoCreacion, k -> new ArrayList<>()).add(p);
            }

            for (int t = 0; t <= 57600; t++) {
                if (t % 60 == 0)
                    System.out.printf("\n--- Tiempo %02dd %02dh %02dm ---%n", t / 1440, (t / 60) % 24, t % 60);
                if (t % 60 == 0) visualizador.render(t, pedidos, flota, bloqueos, depositoX, depositoY);

                // ACO cada hora
                if (t % 60 == 0) {
                    List<Pedido> activos = new ArrayList<>();
                    for (Pedido p : pedidos) {
                        if (!p.atendido && !p.descartado && p.tiempoCreacion <= t && p.tiempoLimite >= t){
                            System.out.printf("🆕 Pedido #%d en (%d,%d), vol %.1fm³, límite t+%d%n", p.id, p.x, p.y, p.volumen, p.tiempoLimite);
                            activos.add(p);
                        }
                    }
                    if (!activos.isEmpty()) {
                        List<Integer> mejorRuta = ejecutarACO(activos);
                        asignarPedidos(t, mejorRuta, activos);
                    }
                }

                // Averías y liberaciones
                // (mantener el bloque existente aquí)
                String turnoActual = turnoDeMinuto(t);
                if (!turnoActual.equals(turnoAnterior)) {
                    turnoAnterior = turnoActual;
                    averiasAplicadas.clear();
                    camionesInhabilitados.clear();
                }

                Map<String, String> averiasTurno = averiasPorTurno.getOrDefault(turnoActual, new HashMap<>());
                for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
                    String key = turnoActual + "_" + entry.getKey();
                    if (averiasAplicadas.contains(key)) continue;
                    Camion c = flota.stream().filter(cam -> cam.id.equals(entry.getKey())).findFirst().orElse(null);
                    if (c != null && c.libreEn <= t) {
                        int penalizacion = entry.getValue().equals("T1") ? 30 : entry.getValue().equals("T2") ? 60 : 90;
                        c.libreEn = t + penalizacion;
                        camionesInhabilitados.add(c.id);
                        averiasAplicadas.add(key);
                        System.out.printf("⛔ Camión %s inhabilitado por avería tipo %s hasta t+%d%n", c.id, entry.getValue(), c.libreEn);
                    }
                }

                for (Camion c : flota) {
                    if (camionesInhabilitados.contains(c.id) && c.libreEn <= t) {
                        camionesInhabilitados.remove(c.id);
                        System.out.printf("✅ Camión %s vuelve a estar disponible tras avería%n", c.id);
                    }
                }
            }
            // resumen final (mantener existente)
            int totalPedidos = pedidos.size();
            int pedidosAtendidos = (int) pedidos.stream().filter(p -> p.atendido).count();
            int pedidosNoAtendidos = (int) pedidos.stream().filter(p -> p.descartado).count();
            double porcentajeAtendidos = (100.0 * pedidosAtendidos) / totalPedidos;

            System.out.println("\n📊 Análisis Final:");
            System.out.printf("Pedidos Atendidos: %d/%d (%.2f%%)%n", pedidosAtendidos, totalPedidos, porcentajeAtendidos);
            System.out.printf("Pedidos No Atendidos: %d%n", pedidosNoAtendidos);

            System.out.println("\n📊 Resumen de Consumo por Camión:");
            double totalConsumo = 0.0;
            for (Camion c : flota) {
                System.out.printf("🚚 %s consumió %.2f galones\n", c.id, c.combustibleGastado);
                totalConsumo += c.combustibleGastado;
            }
            System.out.printf("🔧 Consumo total acumulado: %.2f galones\n", totalConsumo);
        }
        private List<Integer> ejecutarACO(List<Pedido> activos) {
            int N = activos.size();
            double[][] feromonas = new double[N][N];
            for (double[] row : feromonas) Arrays.fill(row, 1.0);

            List<Integer> mejorRuta = null;
            double mejorLongitud = Double.MAX_VALUE;

            for (int iter = 0; iter < ITERACIONES; iter++) {
                List<List<Integer>> rutas = new ArrayList<>();
                for (int k = 0; k < HORMIGAS; k++) {
                    List<Integer> ruta = construirRutaACO(N, feromonas, activos);
                    rutas.add(ruta);
                }
                evaporarFeromonas(feromonas);
                for (List<Integer> ruta : rutas) {
                    double longitud = calcularLongitud(ruta, activos);
                    if (longitud < mejorLongitud) {
                        mejorLongitud = longitud;
                        mejorRuta = ruta;
                    }
                    for (int i = 0; i < ruta.size() - 1; i++) {
                        int a = ruta.get(i), b = ruta.get(i + 1);
                        feromonas[a][b] += Q / longitud;
                    }
                }
            }
            return mejorRuta != null ? mejorRuta : new ArrayList<>();
        }
        private List<Integer> construirRutaACO(int N, double[][] feromonas, List<Pedido> activos) {
            List<Integer> ruta = new ArrayList<>();
            boolean[] visitados = new boolean[N];
            int actual = new Random().nextInt(N);
            ruta.add(actual);
            visitados[actual] = true;

            while (ruta.size() < N) {
                int siguiente = seleccionarSiguiente(actual, visitados, feromonas, activos);
                if (siguiente == -1) break;
                ruta.add(siguiente);
                visitados[siguiente] = true;
                actual = siguiente;
            }
            return ruta;
        }
        private double calcularLongitud(List<Integer> ruta, List<Pedido> activos) {
            double total = 0.0;
            for (int i = 0; i < ruta.size() - 1; i++) {
                total += distancia(activos.get(ruta.get(i)), activos.get(ruta.get(i + 1)));
            }
            return total;
        }
        public void simularYAsignar() {
            List<Integer> mejorRuta = null;
            double mejorLongitud = Double.MAX_VALUE;

            for (int iter = 0; iter < ITERACIONES; iter++) {
                List<List<Integer>> rutasHormigas = new ArrayList<>();
                for (int k = 0; k < HORMIGAS; k++) {
                    rutasHormigas.add(construirRuta());
                }
                evaporarFeromonas();
                for (List<Integer> ruta : rutasHormigas) {
                    double longitud = calcularLongitud(ruta);
                    if (longitud < mejorLongitud) {
                        mejorLongitud = longitud;
                        mejorRuta = ruta;
                    }
                    for (int i = 0; i < ruta.size() - 1; i++) {
                        int a = ruta.get(i);
                        int b = ruta.get(i + 1);
                        feromonas[a][b] += Q / longitud;
                    }
                }
            }

            if (mejorRuta == null) {
                System.out.println("❌ No se encontró ruta óptima.");
                return;
            }

            String turnoAnterior = "";
            Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
            for (Pedido p : pedidos) {
                pedidosPorTiempo.computeIfAbsent(p.tiempoCreacion, k -> new ArrayList<>()).add(p);
            }

            for (int t = 0; t <= 57600; t++) {
                if( t == 1465)
                    continue;
                if (t % 60 == 0)
                    System.out.printf("\n--- Tiempo %02dd %02dh %02dm ---%n", t / 1440, (t / 60) % 24, t % 60);
                if (t % 60 == 0) visualizador.render(t, pedidos, flota, bloqueos, depositoX, depositoY);
                List<Pedido> nuevos = pedidosPorTiempo.getOrDefault(t, new ArrayList<>());
                for (Pedido p : nuevos) {
                    System.out.printf("🆕 Pedido #%d en (%d,%d), vol %.1fm³, límite t+%d%n", p.id, p.x, p.y, p.volumen, p.tiempoLimite);
                }

                String turnoActual = turnoDeMinuto(t);
                if (!turnoActual.equals(turnoAnterior)) {
                    turnoAnterior = turnoActual;
                    averiasAplicadas.clear();
                    camionesInhabilitados.clear();
                }

                Map<String, String> averiasTurno = averiasPorTurno.getOrDefault(turnoActual, new HashMap<>());
                for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
                    String key = turnoActual + "_" + entry.getKey();
                    if (averiasAplicadas.contains(key)) continue;
                    Camion c = flota.stream().filter(cam -> cam.id.equals(entry.getKey())).findFirst().orElse(null);
                    if (c != null && c.libreEn <= t) {
                        int penalizacion = entry.getValue().equals("T1") ? 30 : entry.getValue().equals("T2") ? 60 : 90;
                        c.libreEn = t + penalizacion;
                        camionesInhabilitados.add(c.id);
                        averiasAplicadas.add(key);
                        System.out.printf("⛔ Camión %s inhabilitado por avería tipo %s hasta t+%d%n", c.id, entry.getValue(), c.libreEn);
                    }
                }

                for (Camion c : flota) {
                    if (camionesInhabilitados.contains(c.id) && c.libreEn <= t) {
                        camionesInhabilitados.remove(c.id);
                        System.out.printf("✅ Camión %s vuelve a estar disponible tras avería%n", c.id);
                    }
                }

                for (int i : mejorRuta) {
                    Pedido p = pedidos.get(i);
                    if (p.atendido || p.descartado || p.tiempoCreacion > t) continue;

                    Camion mejorCamion = null;
                    double mejorCosto = Double.MAX_VALUE;

                    for (Camion c : flota) {
                        if (c.disponible < p.volumen || c.libreEn > t || camionesInhabilitados.contains(c.id)) continue;
                        if (hayBloqueo(t, c.x, c.y, p.x, p.y)) continue;
                        double d = distancia(c.x, c.y, p.x, p.y);
                        if (d < mejorCosto) {
                            mejorCamion = c;
                            mejorCosto = d;
                        }
                    }

                    if (mejorCamion != null) {
                        double consumo = calcularConsumo(mejorCosto, p.volumen, mejorCamion.tara);
                        mejorCamion.combustibleGastado += consumo;
                        mejorCamion.disponible -= p.volumen;
                        int ida = (int)(mejorCosto / 0.5);
                        mejorCamion.libreEn = t + ida * 2;
                        p.atendido = true;
                        System.out.printf("✅ %s entrega Pedido #%d: (%d,%d), vol %.1f → t+%d, regreso t+%d, consumo %.2f gal, disp. %.1f m³%n",
                                mejorCamion.id, p.id, p.x, p.y, p.volumen, t + ida, mejorCamion.libreEn, consumo, mejorCamion.disponible);
                    } else if (!p.atendido && !p.descartado && t > p.tiempoLimite) {
                        System.out.printf("❌ Pedido #%d no fue entregado a tiempo (límite t+%d), se descartó.%n", p.id, p.tiempoLimite);
                        p.descartado = true;
                    }
                }
            }

            int totalPedidos = pedidos.size();
            int pedidosAtendidos = (int) pedidos.stream().filter(p -> p.atendido).count();
            int pedidosNoAtendidos = (int) pedidos.stream().filter(p -> p.descartado).count();
            double porcentajeAtendidos = (100.0 * pedidosAtendidos) / totalPedidos;

            System.out.println("\n📊 Análisis Final:");
            System.out.printf("Pedidos Atendidos: %d/%d (%.2f%%)%n", pedidosAtendidos, totalPedidos, porcentajeAtendidos);
            System.out.printf("Pedidos No Atendidos: %d%n", pedidosNoAtendidos);

            System.out.println("\n📊 Resumen de Consumo por Camión:");
            double totalConsumo = 0.0;
            for (Camion c : flota) {
                System.out.printf("🚚 %s consumió %.2f galones\n", c.id, c.combustibleGastado);
                totalConsumo += c.combustibleGastado;
            }
            System.out.printf("🔧 Consumo total acumulado: %.2f galones\n", totalConsumo);
        }
        private void asignarPedidos(int t, List<Integer> ruta, List<Pedido> activos) {
            for (int i : ruta) {
                Pedido p = activos.get(i);
                if (p.atendido || p.descartado || p.tiempoCreacion > t) continue;

                Camion mejorCamion = null;
                double mejorCosto = Double.MAX_VALUE;

                for (Camion c : flota) {
                    if (c.disponible < p.volumen || c.libreEn > t || camionesInhabilitados.contains(c.id)) continue;
                    if (hayBloqueo(t, c.x, c.y, p.x, p.y)) continue;
                    double d = distancia(c.x, c.y, p.x, p.y);
                    if (d < mejorCosto) {
                        mejorCamion = c;
                        mejorCosto = d;
                    }
                }

                if (mejorCamion != null) {
                    double consumo = calcularConsumo(mejorCosto, p.volumen, mejorCamion.tara);
                    mejorCamion.combustibleGastado += consumo;
                    mejorCamion.disponible -= p.volumen;
                    int ida = (int)(mejorCosto / 0.5);
                    mejorCamion.libreEn = t + ida * 2;
                    p.atendido = true;
                    System.out.printf("✅ %s entrega Pedido #%d: (%d,%d), vol %.1f → t+%d, regreso t+%d, consumo %.2f gal, disp. %.1f m³%n",
                            mejorCamion.id, p.id, p.x, p.y, p.volumen, t + ida, mejorCamion.libreEn, consumo, mejorCamion.disponible);
                } else if (!p.atendido && !p.descartado && t > p.tiempoLimite) {
                    System.out.printf("❌ Pedido #%d no fue entregado a tiempo (límite t+%d), se descartó.%n", p.id, p.tiempoLimite);
                    p.descartado = true;
                }
            }
        }
        private void evaporarFeromonas(double[][] feromonas) {
            for (int i = 0; i < feromonas.length; i++)
                for (int j = 0; j < feromonas[i].length; j++)
                    feromonas[i][j] *= (1 - RHO);
        }
        private List<Integer> construirRuta() {
            List<Integer> ruta = new ArrayList<>();
            boolean[] visitados = new boolean[N];
            int actual = new Random().nextInt(N); // tiene que ser el nodo base
            ruta.add(actual);
            visitados[actual] = true;
            while (ruta.size() < N) {
                int siguiente = seleccionarSiguiente(actual, visitados);
                if (siguiente == -1) break;
                ruta.add(siguiente);
                visitados[siguiente] = true;
                actual = siguiente;
            }
            return ruta;
        }
        private int seleccionarSiguiente(int actual, boolean[] visitados, double[][] feromonas, List<Pedido> activos) {
            int N = activos.size();
            double[] prob = new double[N];
            double suma = 0.0;
            for (int j = 0; j < N; j++) {
                if (!visitados[j]) {
                    double tau = feromonas[actual][j];
                    double eta = 1.0 / (distancia(activos.get(actual), activos.get(j)) + 1);
                    prob[j] = Math.pow(tau, ALPHA) * Math.pow(eta, BETA);
                    suma += prob[j];
                }
            }
            if (suma == 0) return -1;
            double r = Math.random() * suma, acumulado = 0;
            for (int j = 0; j < N; j++) {
                if (!visitados[j]) {
                    acumulado += prob[j];
                    if (acumulado >= r) return j;
                }
            }
            return -1;
        }
        private int seleccionarSiguiente(int actual, boolean[] visitados) {
            double suma = 0.0;
            double[] probabilidades = new double[N];
            for (int j = 0; j < N; j++) {
                if (!visitados[j]) {
                    double tau = feromonas[actual][j];
                    double eta = 1.0 / (distancia(pedidos.get(actual), pedidos.get(j)) + 1);
                    probabilidades[j] = Math.pow(tau, ALPHA) * Math.pow(eta, BETA);
                    suma += probabilidades[j];
                }
            }
            if (suma == 0) return -1;
            double r = Math.random() * suma, acumulado = 0;
            for (int j = 0; j < N; j++) {
                if (!visitados[j]) {
                    acumulado += probabilidades[j];
                    if (acumulado >= r) return j;
                }
            }
            return -1;
        }

        private void evaporarFeromonas() {
            for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) feromonas[i][j] *= (1 - RHO);
        }

        private double calcularLongitud(List<Integer> ruta) {
            double total = 0.0;
            for (int i = 0; i < ruta.size() - 1; i++) {
                total += distancia(pedidos.get(ruta.get(i)), pedidos.get(ruta.get(i + 1)));
            }
            return total;
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

        private int distancia(Pedido a, Pedido b) {
            return Math.abs(b.x - a.x) + Math.abs(b.y - a.y);
        }

        private int distancia(int x1, int y1, int x2, int y2) {
            return Math.abs(x2 - x1) + Math.abs(y2 - y1);
        }
        static class Punto {
            int x, y;
            int costoG;
            int estimadoH;
            Punto padre;

            Punto(int x, int y, int costoG, int estimadoH, Punto padre) {
                this.x = x;
                this.y = y;
                this.costoG = costoG;
                this.estimadoH = estimadoH;
                this.padre = padre;
            }

            int f() {
                return costoG + estimadoH;
            }
        }

        static class AStarPathfinder {
            int ancho, alto;
            Set<String> bloqueadas;

            AStarPathfinder(int ancho, int alto, List<Bloqueo> bloqueos, int tiempo) {
                this.ancho = ancho;
                this.alto = alto;
                bloqueadas = new HashSet<>();
                for (Bloqueo b : bloqueos) {
                    if (tiempo >= b.inicio && tiempo <= b.fin) {
                        bloqueadas.addAll(b.aristasBloqueadas);
                    }
                }
            }

            public List<int[]> encontrarRuta(int xIni, int yIni, int xFin, int yFin) {
                PriorityQueue<Punto> abiertos = new PriorityQueue<>(Comparator.comparingInt(Punto::f));
                Map<String, Punto> visitados = new HashMap<>();

                Punto inicio = new Punto(xIni, yIni, 0, manhattan(xIni, yIni, xFin, yFin), null);
                abiertos.add(inicio);
                visitados.put(coordId(xIni, yIni), inicio);

                int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

                while (!abiertos.isEmpty()) {
                    Punto actual = abiertos.poll();
                    if (actual.x == xFin && actual.y == yFin) return reconstruir(actual);

                    for (int[] dir : dirs) {
                        int nx = actual.x + dir[0], ny = actual.y + dir[1];
                        if (nx < 0 || ny < 0 || nx >= ancho || ny >= alto) continue;
                        String arista = Bloqueo.aristaId(actual.x, actual.y, nx, ny);
                        if (bloqueadas.contains(arista)) continue;

                        int nuevoCosto = actual.costoG + 1;
                        String id = coordId(nx, ny);
                        if (!visitados.containsKey(id) || nuevoCosto < visitados.get(id).costoG) {
                            Punto siguiente = new Punto(nx, ny, nuevoCosto, manhattan(nx, ny, xFin, yFin), actual);
                            abiertos.add(siguiente);
                            visitados.put(id, siguiente);
                        }
                    }
                }
                return Collections.emptyList(); // no se encontró ruta
            }

            private List<int[]> reconstruir(Punto fin) {
                List<int[]> camino = new ArrayList<>();
                for (Punto p = fin; p != null; p = p.padre) camino.add(0, new int[]{p.x, p.y});
                return camino;
            }

            private String coordId(int x, int y) {
                return x + "," + y;
            }

            private int manhattan(int x1, int y1, int x2, int y2) {
                return Math.abs(x1 - x2) + Math.abs(y1 - y2);
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
    public static Map<String, Map<String, String>> cargarAverias(String archivo) {
        Map<String, Map<String, String>> averias = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(archivo))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split("_");
                if (partes.length == 3) {
                    averias.computeIfAbsent(partes[0], k -> new HashMap<>()).put(partes[1], partes[2]);
                }
            }
        } catch (IOException e) {
            System.out.println("⚠️ No se pudo leer averias.txt: " + e.getMessage());
        }
        return averias;
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
            this.ancho = ancho; this.alto = alto;
            grid = new char[alto][ancho];
            setTitle("Simulación GLP");
            setSize(ancho * 10 + 50, alto * 10 + 50);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setLocationRelativeTo(null);
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
            for (Pedido p : pedidos)
                if (p.x >= 0 && p.x < ancho && p.y >= 0 && p.y < alto)
                    grid[p.y][p.x] = p.atendido ? 'E' : 'P';
            for (Camion c : camiones)
                if (c.x >= 0 && c.x < ancho && c.y >= 0 && c.y < alto)
                    grid[c.y][c.x] = 'C';
            for (Bloqueo b : bloqueos)
                if (t >= b.inicio && t <= b.fin)
                    for (String arista : b.aristasBloqueadas) {
                        String[] puntos = arista.split("[-,]");
                        int x = Integer.parseInt(puntos[0]);
                        int y = Integer.parseInt(puntos[1]);
                        if (x >= 0 && x < ancho && y >= 0 && y < alto) grid[y][x] = 'B';
                    }
            panel.repaint();
        }
    }
}


