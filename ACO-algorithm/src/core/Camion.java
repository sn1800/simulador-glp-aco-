package core;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa un camión con capacidad de carga, combustible y lógica de movimiento.
 */
public class Camion {
    /** Estados del camión durante la simulación */
    public enum TruckStatus {
        AVAILABLE,   // Puede recibir nuevos pedidos
        DELIVERING,  // En ruta de entrega
        RETURNING    // Regresando a depósito
    }

    // --- Identificación y capacidades ---
    private final String id;
    private final double capacidadCarga;       // m³ de carga útil
    private double disponible;                 // m³ de carga restante
    private final double tara;                 // peso en vacío (valor referencial)
    private static final double pesoTara = 2.5;
    private static final double pesoCargoPorM3 = 0.5;

    // --- Combustible ---
    private final double capacidadCombustible; // galones totales
    private double combustibleDisponible;      // galones restantes

    // --- Posición y timing ---
    private int x = 12, y = 8;    // coordenadas actuales (depósito por defecto)
    private int libreEn = 0;      // minuto en que estará libre
    private boolean enRetorno = false;
    private TruckStatus status = TruckStatus.AVAILABLE;

    // --- Rutas y trayectoria ---
    private final List<Pedido> rutaPendiente = new ArrayList<>();      // pedidos aún no servidos
    private List<Point> rutaActual = Collections.emptyList();          // camino Manhattan paso a paso
    private int pasoActual = 0;                                        // índice en rutaActual
    private final List<Point> history = new ArrayList<>();            // recorrido histórico

    // --- Estadísticas de consumo ---
    private double consumoAcumulado = 0.0; // combustible utilizado solo por avance
    private double combustibleGastado = 0.0; // galones totales consumidos

    // --- Para mecánica de recarga en tanque ---
    public Tanque reabastecerEnTanque = null;
    int retHora = 0, retStartX = 0, retStartY = 0, retDestX = 0, retDestY = 0;

    /**
     * Constructor principal.
     * @param id                 Identificador único
     * @param capacidadCarga     Capacidad de carga en m³
     * @param tara               Peso en vacío
     * @param capacidadCombustible Capacidad de combustible en galones
     */
    public Camion(String id, double capacidadCarga, double tara, double capacidadCombustible) {
        this.id = id;
        this.capacidadCarga = capacidadCarga;
        this.tara = tara;
        this.capacidadCombustible = capacidadCombustible;
        this.combustibleDisponible  = capacidadCombustible;
        reset();
    }

    /**
     * Vuelve el camión a su estado inicial (lleno y en depósito).
     */
    public void reset() {
        this.disponible = capacidadCarga;
        this.combustibleDisponible = capacidadCombustible;
        this.consumoAcumulado = 0;
        this.combustibleGastado = 0;
        this.x = 12; this.y = 8;
        this.libreEn = 0;
        this.enRetorno = false;
        this.status = TruckStatus.AVAILABLE;
        this.rutaPendiente.clear();
        this.rutaActual = Collections.emptyList();
        this.pasoActual = 0;
        this.history.clear();
        this.reabastecerEnTanque = null;
    }

    /**
     * Recarga el combustible al máximo.
     */
    public void recargarCombustible() {
        this.combustibleDisponible = capacidadCombustible;
    }

    /**
     * Avanza un solo paso en la ruta actual, consumiendo combustible.
     */
    public void avanzarUnPaso() {
        if (!tienePasosPendientes()) return;
        // Consumo proporcional al peso total (tara + carga) / eficiencia
        double pesoTotal = pesoTara + (disponible * pesoCargoPorM3);
        double gasto = pesoTotal / 180.0;  // galones por paso
        consumoAcumulado += gasto;
        combustibleDisponible -= gasto;
        combustibleGastado += gasto;
        Point next = rutaActual.get(pasoActual++);
        moverA(next);
    }

    /**
     * Mueve al camión a la posición p y registra en historial.
     */
    public void moverA(Point p) {
        this.x = p.x; this.y = p.y;
        history.add(new Point(x, y));
    }

    /**
     * Define la ruta Manhattan de pasos a seguir.
     */
    public void setRuta(List<Point> ruta) {
        this.rutaActual = new ArrayList<>(ruta);
        this.pasoActual = 0;
    }

    /**
     * Añade un camino al historial (sin resetear). Ideal al comienzo.
     */
    public void appendToHistory(List<Point> path) {
        if (history.isEmpty()) history.add(new Point(x, y));
        if (path != null) history.addAll(path);
    }

    // --- Checkers de ruta ---
    public boolean tienePasosPendientes() {
        return pasoActual < rutaActual.size();
    }

    // --- Getters y Setters ---
    public String getId() { return id; }
    public double getCapacidad() { return capacidadCarga; }
    public double getDisponible() { return disponible; }
    public void setDisponible(double d) { this.disponible = d; }
    public double getTara() { return tara; }
    public int getX() { return x; }
    public int getY() { return y; }
    public void setX(int x_aux) {this.x = x_aux;}
    public void setY(int y_aux) {this.y = y_aux;}
    public int getLibreEn() { return libreEn; }
    public void setLibreEn(int t) { this.libreEn = t; }
    public boolean isEnRetorno() { return enRetorno; }
    public void setEnRetorno(boolean enRet) { this.enRetorno = enRet; }
    public TruckStatus getStatus() { return status; }
    public void setStatus(TruckStatus s) { this.status = s; }
    public List<Pedido> getRutaPendiente() { return rutaPendiente; }
    public List<Point> getRuta() { return rutaActual; }
    public List<Point> getHistory() { return history; }
    public double getConsumoAcumulado() { return consumoAcumulado; }
    public double getCombustibleGastado() { return combustibleGastado; }
    public double getCapacidadCombustible() { return capacidadCombustible; }
    public double getCombustibleDisponible() { return combustibleDisponible; }
    public void setCombustibleDisponible(double c) { this.combustibleDisponible = c; }
}