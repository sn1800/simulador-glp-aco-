package core;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;

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

        ACOPlanner aco = new ACOPlanner(pedidos, bloqueos, averiasPorTurno);
        aco.simularDiaADia(1440*2);
    }
    List<Pedido> pedidos;
    List<Bloqueo> bloqueos;
    Map<String, Map<String, String>> averiasPorTurno;
    List<Camion> flota;
    double[][] feromonas;
    int N;
    final int ITERACIONES = 50;
    final int HORMIGAS = 10;
    final double ALPHA = 1, BETA = 2, RHO = 0.1, Q = 1000;
    List<Tanque> tanquesIntermedios = new ArrayList<>();
    final int depositoX = 12, depositoY = 8;
    Set<String> camionesInhabilitados = new HashSet<>();
    Set<String> averiasAplicadas = new HashSet<>();
    GridVisualizerGUI visualizador;
    private List<EntregaEvent> eventosEntrega = new ArrayList<>();
    public ACOPlanner(List<Pedido> pedidos, List<Bloqueo> bloqueos, Map<String, Map<String, String>> averiasPorTurno) {
        this.pedidos = pedidos;
        this.bloqueos = bloqueos;
        this.averiasPorTurno = averiasPorTurno;
        this.flota = inicializarFlota();
        this.N = pedidos.size();
        this.feromonas = new double[N][N];
        for (double[] row : feromonas) Arrays.fill(row, 1.0);
        this.visualizador = new GridVisualizerGUI(70, 50);
        // Capacidades y ubicaciones de los dos tanques intermedios
        tanquesIntermedios.add(new Tanque(30, 15, 0));
        tanquesIntermedios.add(new Tanque(50, 40, 0));
    }
    public ACOPlanner() {
        this(
                cargarPedidos("pedidos.txt"),
                cargarBloqueos("bloqueos.txt"),
                cargarAverias("averias.txt")
        );
    }
    public void reset() {
        // recarga datos
        this.pedidos = cargarPedidos("pedidos.txt");
        this.bloqueos = cargarBloqueos("bloqueos.txt");
        this.averiasPorTurno = cargarAverias("averias.txt");

        // reinicia flota y feromonas
        this.flota = inicializarFlota();
        this.N = pedidos.size();
        this.feromonas = new double[N][N];
        for (double[] row : feromonas) Arrays.fill(row, 1.0);

        // reinicia tanques intermedios
        tanquesIntermedios.clear();
        tanquesIntermedios.add(new Tanque(30, 15, 1600));
        tanquesIntermedios.add(new Tanque(50, 40, 1600));

        // limpia eventos y averías en curso
        eventosEntrega.clear();
        camionesInhabilitados.clear();
        averiasAplicadas.clear();
    }
    /**
     * Permite agregar manualmente una avería desde la GUI.
     * @param turno  "T1", "T2" o "T3"
     * @param camionId  identificador del camión
     * @param tipo   "T1"=30m, "T2"=60m, "T3"=90m
     */
    public void agregarAveria(String turno, String camionId, String tipo) {
        averiasPorTurno
                .computeIfAbsent(turno, k -> new HashMap<>())
                .put(camionId, tipo);
    }

    // ——————————————————————————————————————————
    //   GETTERS NECESARIOS PARA LA GUI
    // ——————————————————————————————————————————

    /** Para llenar la tabla de camiones en SimuladorUI */
    public List<Camion> getFlota() {
        return Collections.unmodifiableList(flota);
    }

    /** Para llenar la tabla de pedidos en SimuladorUI */
    public List<Pedido> getPedidos() {
        return Collections.unmodifiableList(pedidos);
    }

    /** Para dibujar los tanques intermedios en MapPanel */
    public List<Tanque> getTanquesIntermedios() {
        return Collections.unmodifiableList(tanquesIntermedios);
    }
    public void simularDiaADia(int tMax) {
        long tStart = System.currentTimeMillis();
        String turnoAnterior = "";
        Map<Integer, List<Pedido>> pedidosPorTiempo = new HashMap<>();
        List<Double> holguras = new ArrayList<>();

        // Preprocesamiento: filtrar pedidos con <4h de anticipación
        for (Pedido p : pedidos) {
            if (p.tiempoLimite - p.tiempoCreacion < 4 * 60) {
                p.descartado = true;
                System.out.printf("⚠ Pedido #%d rechazado: menos de 4h de anticipación%n", p.id);
            } else {
                pedidosPorTiempo
                        .computeIfAbsent(p.tiempoCreacion, k -> new ArrayList<>())
                        .add(p);
            }
        }

        for (int t = 0; t <= tMax; t++) {
            int tiempoActual = t;
            boolean replanificar = false;
            // recarga de tanques intermedios
            // al inicio de cada día (t%1440==0)
            if (t > 0 && t % 1440 == 0) {
                for (Tanque tq : tanquesIntermedios) {
                    tq.disponible = tq.capacidadTotal;
                }
                System.out.printf("🔁 t+%d: Tanques intermedios recargados a %.1f m³ cada uno%n",
                        tiempoActual,
                        tanquesIntermedios.get(0).capacidadTotal);
            }

            // 0) Procesar eventos de entrega programados para este minuto
            Iterator<EntregaEvent> itEv = eventosEntrega.iterator();
            while (itEv.hasNext()) {
                EntregaEvent ev = itEv.next();
                if (ev.time == tiempoActual) {
                    System.out.println("▶▶▶ disparando eventoEntrega para Pedido "+ ev.pedido.id);
                    // 1) Guardar capacidad previa
                    double antes = ev.camion.disponible;
                    // 2) Actualizar posición y liberar al camión
                    ev.camion.x = ev.pedido.x;
                    ev.camion.y = ev.pedido.y;
                    ev.camion.libreEn = tiempoActual;
                    // 3) Descontar volumen
                    ev.camion.disponible -= ev.pedido.volumen;
                    // 4) Marcar pedido entregado
                    ev.pedido.atendido = true;
                    double holguraSeg = ev.pedido.tiempoLimite - tiempoActual;
                    holguras.add(holguraSeg);
                    // 5) Log de entrega
                    System.out.printf(
                            "✅ t+%d: Pedido #%d completado por Camión %s en (%d,%d); capacidad: %.1f→%.1f m³%n",
                            tiempoActual, ev.pedido.id, ev.camion.id,
                            ev.pedido.x, ev.pedido.y,
                            antes, ev.camion.disponible
                    );
                    itEv.remove();

                    // 6) Iniciar retorno
                    // — calcula cuánto le falta recargar al camión:
                    double falta = ev.camion.capacidad - ev.camion.disponible;

                    Tanque mejor = null;
                    double distMin = Double.MAX_VALUE;
                    for (Tanque tq : tanquesIntermedios) {
                        // ❌ antes era: tq.disponible >= ev.camion.capacidad
                        // ✅ ahora: comparamos con lo que falta, no con la capacidad total
                        if (tq.disponible >= falta) {
                            int d = Math.abs(ev.camion.x - tq.x) + Math.abs(ev.camion.y - tq.y);
                            if (d < distMin) {
                                distMin = d;
                                mejor = tq;
                            }
                        }
                    }

                    // Si no hay suficiente en ningún tanque intermedio, va a planta:
                    ev.camion.enRetorno    = true;
                    ev.camion.retHora      = tiempoActual;
                    ev.camion.retStartX    = ev.camion.x;
                    ev.camion.retStartY    = ev.camion.y;
                    ev.camion.retDestX     = (mejor != null ? mejor.x : depositoX);
                    ev.camion.retDestY     = (mejor != null ? mejor.y : depositoY);
                    ev.camion.reabastecerEnTanque = mejor;  // null = planta

                    // 7) Construir ruta Manhattan de retorno y asignarla
                    int dx = (mejor != null ? mejor.x : depositoX);
                    int dy = (mejor != null ? mejor.y : depositoY);
                    List<Point> returnPath = buildManhattanPath(ev.camion.x, ev.camion.y, dx, dy);
                    ev.camion.setRuta(returnPath);
                    ev.camion.appendToHistory(returnPath);
                    System.out.printf("⏱️ t+%d: Camión %s inicia retorno a %s%n",
                            tiempoActual, ev.camion.id,
                            (mejor != null ? "tanque intermedio" : "planta principal"));

                }
            }
            // <<< aquí: reabastecimiento automático al llegar a planta >>>
            for (Camion c : flota) {
                // Si aún tiene pasos (ida o retorno), avanza un paso Manhattan
                if (c.tienePasosPendientes()) {
                    c.avanzarUnPaso();
                    System.out.printf("→ Camión %s avanza a (%d,%d)%n", c.id, c.x, c.y);
                }
                // Si acaba de completar todos los pasos y estaba en retorno, recarga
                else if (c.enRetorno) {
                    // → Lógica de recarga en tanque o planta (idéntica a la tuya)
                    double falta = c.capacidad - c.disponible;
                    Tanque tq = c.reabastecerEnTanque;
                    if (tq != null) {
                        tq.disponible -= falta;
                        System.out.printf("🔄 t+%d: Camión %s llegó a tanque (%d,%d) y recargado a %.1f m³%n", tiempoActual, c.id, tq.x, tq.y, c.capacidad);
                        System.out.printf("🔁      Tanque (%d,%d) quedó con %.1f m³%n", tq.x, tq.y, tq.disponible);
                    } else {
                        System.out.printf("🔄 t+%d: Camión %s llegó a planta (%d,%d) y recargado a %.1f m³%n", tiempoActual, c.id, depositoX, depositoY, c.capacidad);
                    }
                    c.disponible = c.capacidad;
                    c.enRetorno = false;
                    c.reabastecerEnTanque = null;
                    // determina destino de retorno
                    int dx = tq != null ? tq.x : depositoX;
                    int dy = tq != null ? tq.y : depositoY;

                    // genera el camino de regreso
                    List<Point> returnPath = buildManhattanPath(c.x, c.y, dx, dy);
                    c.setRuta(returnPath);
                    c.appendToHistory(returnPath);
                }
            }

            // 1. Nuevo pedido
            List<Pedido> nuevos = pedidosPorTiempo.getOrDefault(tiempoActual, Collections.emptyList());
            for (Pedido p : nuevos) {
                System.out.printf("🆕 t+%d: Pedido #%d recibido (destino=(%d,%d), vol=%.1fm³, límite t+%d)%n",
                        tiempoActual, p.id, p.x, p.y, p.volumen, p.tiempoLimite);
            }
            if (!nuevos.isEmpty()) replanificar = true;

            // 2. Vencimientos → colapso
            for (Pedido p : pedidos) {
                if (!p.atendido && !p.descartado && tiempoActual > p.tiempoLimite) {
                    System.out.printf("💥 Colapso en t+%d, pedido %d incumplido%n", tiempoActual, p.id);
                    return;
                }
            }

            // 3. Averías
            String turnoActual = turnoDeMinuto(tiempoActual);
            if (!turnoActual.equals(turnoAnterior)) {
                turnoAnterior = turnoActual;
                averiasAplicadas.clear(); camionesInhabilitados.clear();
            }
            Map<String, String> averiasTurno = averiasPorTurno.getOrDefault(turnoActual, Collections.emptyMap());
            for (Map.Entry<String, String> entry : averiasTurno.entrySet()) {
                String key = turnoActual + "_" + entry.getKey();
                if (averiasAplicadas.contains(key)) continue;
                Camion c = findCamion(entry.getKey());
                if (c != null && c.libreEn <= tiempoActual) {
                    int penal = entry.getValue().equals("T1") ? 30 : entry.getValue().equals("T2") ? 60 : 90;
                    c.libreEn = tiempoActual + penal;
                    averiasAplicadas.add(key);
                    camionesInhabilitados.add(c.id);
                    replanificar = true;
                }
            }
            Iterator<String> it = camionesInhabilitados.iterator();
            while (it.hasNext()) {
                Camion c = findCamion(it.next());
                if (c != null && c.libreEn <= tiempoActual) { it.remove(); replanificar = true; }
            }

            // 4. Actualizar estado real de la flota
            List<CamionEstado> flotaEstado = new ArrayList<>();
            for (Camion c : flota) {
                CamionEstado est = new CamionEstado();
                est.id = c.id;
                est.posX = c.getX();  // ✅ posición real actual
                est.posY = c.getY();  // ✅ posición real actual
                est.capacidadDisponible = c.getDisponible();  // ✅ actual sin calcular artificialmente
                est.tiempoLibre = c.getLibreEn();
                flotaEstado.add(est);
            }

            // 5. Replanificación VRP con ACO
            // ——————————————————————————————
            // 5.1) Mapa de entrega actual
            Map<Pedido,Integer> entregaActual = new HashMap<>();
            for (EntregaEvent ev : eventosEntrega) {
                entregaActual.put(ev.pedido, ev.time);
            }
            // 5.2) Pedidos pendientes de atender (solo los NO programados aún)
            List<Pedido> pendientes = pedidos.stream()
                    .filter(p -> !p.atendido
                            && !p.descartado
                            && !p.programado   // ← filtramos los que ya fueron asignados
                            && p.tiempoCreacion <= tiempoActual)
                    .collect(Collectors.toList());


            // 5.3) Identificar candidatos a reasignar
            // DESPUÉS: forzamos que TODOS los pendientes sean candidatos
            List<Pedido> candidatos = new ArrayList<>();

            for (Pedido p : pendientes) {
                // 🔴 Nuevo: forzar inclusión si faltan menos de 60 minutos
                if (tiempoActual + 60 >= p.tiempoLimite) {
                    candidatos.add(p);
                    continue;
                }
                Integer tPrev = entregaActual.get(p);
                if (tPrev == null) {
                    // nunca asignado → candidato
                    candidatos.add(p);
                } else {
                    // ya asignado: ¿algún otro camión podría hacerlo antes?
                    int mejorAlt = tPrev;
                    for (CamionEstado est : flotaEstado) {
                        if (est.capacidadDisponible < p.volumen) continue;
                        int dt = Math.abs(est.posX - p.x) + Math.abs(est.posY - p.y);
                        int llegada = tiempoActual + dt;
                        if (llegada < mejorAlt) mejorAlt = llegada;
                    }
                    if (mejorAlt < tPrev) {
                        candidatos.add(p);
                    }
                }
            }
            // ←――――――――――――――――――――――――――――――――
            // NUEVO: excluir pedidos con entrega próxima (<=1 min)
            candidatos.removeIf(p -> {
                Integer entregaMin = entregaActual.get(p);
                return entregaMin != null && entregaMin - tiempoActual <= 1;
            });
            // ―――――――――――――――――――――――――――――――――→

            // 5.4) Si hay candidatos y toca replanificar, solo ellos
            if (replanificar && !candidatos.isEmpty()) {
                System.out.printf("⏲️ t+%d: Replanificando, candidatos = %s%n",
                        tiempoActual, candidatos.stream().map(p->p.id).collect(Collectors.toList()));
                // ——— A ———  cancelar cualquier eventoEntrega pendiente de esos candidatos
                // eventosEntrega.removeIf(ev -> candidatos.contains(ev.pedido));
                Set<Integer> idsCandidatos = candidatos.stream().map(p->p.id).collect(toSet());
                eventosEntrega.removeIf(ev -> idsCandidatos.contains(ev.pedido.id));

                // ——— B ———  desprogramar los pedidos para que puedan reasignarse
                for (Pedido p : candidatos) p.programado = false;
                List<Ruta> rutas = ejecutarACO(candidatos, flotaEstado, tiempoActual);
                System.out.printf("    → Rutas devueltas para %s%n",
                        rutas.stream()
                                .flatMap(r->r.pedidos.stream())
                                .map(idx->candidatos.get(idx).id)
                                .collect(Collectors.toList()));
                aplicarRutas(tiempoActual, rutas, candidatos);
            }


            if (tiempoActual % 60 == 0)
                visualizador.render(tiempoActual, pedidos, flota, bloqueos, depositoX, depositoY);
        }
        long tEnd = System.currentTimeMillis();
        long tiempoEjecucionMs = tEnd - tStart;

        double holguraPromedioMin = holguras.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0) / 60.0;
        double consumoTotal = flota.stream()
                .mapToDouble(Camion::getConsumoAcumulado)
                .sum();
        System.out.println("🏁 Reporte Final:");
        System.out.printf("• Tiempo de ejecución: %d ms%n", tiempoEjecucionMs);
        System.out.printf("• Holgura promedio: %.2f min%n", holguraPromedioMin);
        System.out.printf("• Consumo total: %.2f galones%n", consumoTotal);

        //reporteFinal();
    }
    // ------------------ Clases auxiliares ------------------
    static class CamionEstado {
        String id;
        int posX, posY;
        double capacidadDisponible;
        int tiempoLibre;
    }

    static class Ruta {
        CamionEstado estado;
        List<Integer> pedidos = new ArrayList<>();
        double distancia = 0;
        double consumo = 0;
    }
    // ------------------- Algoritmo ACO para VRP -------------------
    public List<Ruta> ejecutarACO(List<Pedido> pedidosActivos, List<CamionEstado> flotaEstado , int tiempoActual) {
        int V = flotaEstado.size(), N = pedidosActivos.size();
        // feromonas[v][i]: feromona para asignación camión v al pedido i
        double[][] tau = new double[V][N];
        for (double[] row : tau) Arrays.fill(row, 1.0);

        List<Ruta> mejorSol = null;
        double mejorCoste = Double.MAX_VALUE;

        for (int it = 0; it < ITERACIONES; it++) {
            List<List<Ruta>> soluciones = new ArrayList<>();
            // Construir soluciones con HORMIGAS hormigas
            for (int h = 0; h < HORMIGAS; h++) {
                List<Integer> noAsignados = new ArrayList<>();
                for (int i = 0; i < N; i++) noAsignados.add(i);

                // Clonar estado inicial de flota para esta hormiga
                List<CamionEstado> clonedFlota = deepCopyFlota(flotaEstado);
                List<Ruta> rutas = initRutas(clonedFlota);

                while (!noAsignados.isEmpty()) {
                    // Calcular probabilidades para pares (camión, pedido)
                    double[][] prob = calcularProbabilidades(rutas, pedidosActivos, noAsignados, tau , tiempoActual);
                    // Seleccionar par con exploración/expLOT
                    Seleccion sel = muestrearPar(prob, rutas, pedidosActivos, noAsignados);
                    asignarPedidoARuta(sel.camionIdx, sel.pedidoIdx, rutas, pedidosActivos,tiempoActual);
                    noAsignados.remove(Integer.valueOf(sel.pedidoIdx));
                }
                soluciones.add(rutas);
            }
            // Evaporación
            for (int v = 0; v < V; v++) for (int i = 0; i < N; i++) tau[v][i] *= (1 - RHO);
            // Depósito de feromona y búsqueda de mejor
            // Construir mapa de id → índice original
            Map<String, Integer> idToIndex = new HashMap<>();
            for (int i = 0; i < flotaEstado.size(); i++) {
                idToIndex.put(flotaEstado.get(i).id, i);
            }
            for (List<Ruta> sol : soluciones) {
                double coste = calcularCosteTotal(sol);
                if (coste < mejorCoste && verificaVentanas(sol)) {
                    mejorCoste = coste;
                    mejorSol = sol;
                }
                // Actualizar feromonas por cada ruta y pedido
                for (Ruta ruta : sol) {
                    int v = idToIndex.getOrDefault(ruta.estado.id, -1);
                    if (v >= 0) {
                        for (int idx : ruta.pedidos) {
                            tau[v][idx] += Q / coste;
                        }
                    }
                }
            }
        }
        return mejorSol != null ? mejorSol : Collections.emptyList();
    }
    // Métodos a implementar:
    /**
     * Realiza una copia profunda del estado de la flota para cada hormiga.
     */
    private List<CamionEstado> deepCopyFlota(List<CamionEstado> original) {
        List<CamionEstado> copia = new ArrayList<>();
        for (CamionEstado est : original) {
            CamionEstado cl = new CamionEstado();
            cl.id = est.id;
            cl.posX = est.posX;
            cl.posY = est.posY;
            cl.capacidadDisponible = est.capacidadDisponible;
            cl.tiempoLibre = est.tiempoLibre;
            copia.add(cl);
        }
        return copia;
    }

    /**
     * Inicializa una ruta vacía para cada camión en la flota.
     */
    private List<Ruta> initRutas(List<CamionEstado> flota) {
        List<Ruta> rutas = new ArrayList<>();
        for (CamionEstado est : flota) {
            Ruta r = new Ruta();
            r.estado = est;
            rutas.add(r);
        }
        return rutas;
    }

    /**
     * Calcula la matriz de probabilidades para asignar cada pedido no asignado a cada ruta.
     */
    private double[][] calcularProbabilidades(
            List<Ruta> rutas,
            List<Pedido> pedidosActivos,
            List<Integer> noAsignados,
            double[][] tau , int tiempoActual) {
        int V = rutas.size(), M = noAsignados.size();
        double[][] prob = new double[V][pedidosActivos.size()];
        for (int v = 0; v < rutas.size(); v++) {
            Ruta r = rutas.get(v);
            for (int idx : noAsignados) {
                // ❌ Bloquear si no cabe por volumen
                if (r.estado.tiempoLibre > tiempoActual) {
                    prob[v][idx] = 0;
                    continue;
                }
                Pedido p = pedidosActivos.get(idx);
                if (r.estado.capacidadDisponible < p.volumen) {
                    prob[v][idx] = 0;
                    continue;
                }
                // ⏳ Penalización si el camión aún está ocupado
                int delay = Math.max(0, r.estado.tiempoLibre - tiempoActual);
                double penalTiempo = 1.0 / (1 + delay);

                // 📏 Distancia Manhattan
                int dx = Math.abs(r.estado.posX - p.x);
                int dy = Math.abs(r.estado.posY - p.y);
                double eta = 1.0 / (dx + dy + 1);

                // Combinar heurística con penalización
                eta *= penalTiempo;

                prob[v][idx] = Math.pow(tau[v][idx], ALPHA)
                        * Math.pow(eta, BETA);
            }
        }

        return prob;
    }

    /**
     * Muestrea un par (camión, pedido) según las probabilidades calculadas.
     */
    private class Seleccion { int camionIdx, pedidoIdx; }
    private Seleccion muestrearPar(
            double[][] prob,
            List<Ruta> rutas,
            List<Pedido> pedidosActivos,
            List<Integer> noAsignados) {
        // sumar todas las probabilidades
        double total = 0;
        for (int v = 0; v < prob.length; v++)
            for (int idx : noAsignados) total += prob[v][idx];
        double r = Math.random() * total;
        double acumulado = 0;
        for (int v = 0; v < prob.length; v++) {
            for (int idx : noAsignados) {
                acumulado += prob[v][idx];
                if (acumulado >= r) {
                    Seleccion s = new Seleccion();
                    s.camionIdx = v;
                    s.pedidoIdx = idx;
                    return s;
                }
            }
        }
        // fallback
        Seleccion s = new Seleccion();
        s.camionIdx = 0;
        s.pedidoIdx = noAsignados.get(0);
        return s;
    }

    /**
     * Asigna un pedido a la ruta seleccionada y actualiza estado del camión.
     */
    private void asignarPedidoARuta(
            int camionIdx,
            int pedidoIdx,
            List<Ruta> rutas,
            List<Pedido> pedidosActivos,int tiempoActual) {

        Ruta ruta = rutas.get(camionIdx);
        CamionEstado c = ruta.estado;
        Pedido p   = pedidosActivos.get(pedidoIdx);

        // 1) Evitar duplicados
        if (ruta.pedidos.contains(pedidoIdx)) return;

        // 2) Comprobar capacidad
        if (c.capacidadDisponible < p.volumen) return;

        // 3) Distancia Manhattan (y tiempo = distancia)
        int dx = Math.abs(c.posX - p.x);
        int dy = Math.abs(c.posY - p.y);
        int dist  = dx + dy;
        // si has metido ya esa lógica de 50 km/h:
        double minutosPorKm = 60.0 / 50.0;
        int tiempoViaje = (int)Math.ceil(dist * minutosPorKm);
        c.tiempoLibre = tiempoActual + tiempoViaje;

        // 4) Consumo real usando tu función
        //ruta.consumo += calcularConsumo(dist, p.volumen, c.capacidadDisponible);
        //ruta.consumo += dist * (1 + p.volumen / c.capacidadDisponible);
        // 5) Actualizar posición y capacidad
        ruta.distancia += dist;
        ruta.consumo += dist * (1 + p.volumen / c.capacidadDisponible);
        c.posX = p.x;
        c.posY = p.y;
        c.capacidadDisponible -= p.volumen;

        // 6) Registrar la entrega en la ruta
        ruta.pedidos.add(pedidoIdx);
    }



    /**
     * Calcula el coste total de la solución (sumatoria de consumos).
     */
    private double calcularCosteTotal(List<Ruta> sol) {
        double total = 0;
        for (Ruta r : sol) total += r.consumo;
        return total;
    }

    /**
     * Verifica que todas las entregas cumplen su ventana de tiempo.
     * (Aquí simplificada siempre a true; puede extenderse con tiempos acumulados.)
     */
    private boolean verificaVentanas(List<Ruta> sol) {
        return true;
    }
    public Camion findCamion(String id) {
        for (Camion c : flota) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    /**
     * Aplica las rutas calculadas al estado real de los camiones y pedidos.
     */
    /** Construye ruta Manhattan unitaria de (sx,sy) a (ex,ey). */
    private List<Point> buildManhattanPath(int sx, int sy, int ex, int ey) {
        List<Point> path = new ArrayList<>();
        int cx = sx, cy = sy;
        // X
        while (cx != ex) {
            cx += Integer.signum(ex - cx);
            path.add(new Point(cx, cy));
        }
        // Y
        while (cy != ey) {
            cy += Integer.signum(ey - cy);
            path.add(new Point(cx, cy));
        }
        return path;
    }

    private void aplicarRutas(int tiempoActual, List<Ruta> rutas, List<Pedido> activos) {
        for (Ruta ruta : rutas) {
            CamionEstado est = ruta.estado;
            Camion camion = findCamion(est.id);
            // 1) Partimos de la posición actual del camión
            int cx = camion.x, cy = camion.y;
            for (int idx : ruta.pedidos) {
                Pedido p = activos.get(idx);

                // 1) Log de asignación
                System.out.printf("⏱️ t+%d: Asignando Pedido #%d al Camión %s%n",
                        tiempoActual, p.id, camion.id);
                // → Construir ruta Manhattan paso a paso
                List<Point> path = buildManhattanPath(cx, cy, p.x, p.y);
                System.out.println("Manhattan path desde ("+cx+","+cy+") hasta ("+p.x+","+p.y+"): " );


                // 2) Distancia Manhattan y tiempo de viaje
                int dx = Math.abs(cx - p.x);
                int dy = Math.abs(cy - p.y);
                int dist = dx + dy;
                // 50 km/h → 1.2 min/km
                double minutosPorKm = 60.0 / 50.0;
                int tiempoViaje = (int) Math.ceil(dist * minutosPorKm);
                // 3) Asigna ruta e historial
                camion.setRuta(path);
                camion.appendToHistory(path);

                // 4) Programar evento de entrega
                eventosEntrega.add(new EntregaEvent(
                        tiempoActual + tiempoViaje,
                        camion,
                        p
                ));
                System.out.printf("🕒 eventoEntrega programado para t+%d en (%d,%d)%n",
                        tiempoActual + tiempoViaje, p.x, p.y);

                // 5) Actualiza la posición del camión
                camion.x = p.x;
                camion.y = p.y;
                cx = p.x;
                cy = p.y;
                System.out.printf("Ruta %s → start=(%d,%d) end=(%d,%d) size=%d%n",
                        camion.getId(),
                        path.get(0).x, path.get(0).y,
                        path.get(path.size()-1).x, path.get(path.size()-1).y,
                        path.size());

                // 6) Marca el pedido
                p.programado = true;
            }
        }
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


