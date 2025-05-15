package core;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

public class Camion {
    String id;
    double capacidad;
    double disponible;
    int x = 12, y = 8;
    int libreEn = 0; // minuto en que estará libre
    double combustibleGastado;  // galones consumidos en total
    double tara;
    boolean enRetorno = false;
    private List<Point> rutaActual = new ArrayList<>();
    private int pasoActual = 0;
    private double consumoAcumulado = 0.0;
    private final double pesoTara = 2.5;           // valor referencial
    private final double pesoCargoPorM3 = 0.5;     // valor referencial


    int retHora=0;
    // en Camion
    int retStartX, retStartY;
    int retDestX,  retDestY;
    private List<Point> ruta = Collections.emptyList();
    Tanque reabastecerEnTanque = null;
    private List<Point> history = new ArrayList<>();

    public Camion(String id, double capacidad, double tara) {
        this.id = id;
        this.capacidad = capacidad;
        this.combustibleGastado = 0.0;
        this.tara = tara;
        reset();
    }

    void reset() {
        history.clear();
        this.disponible = capacidad;
        this.x = 12;
        this.y = 8;
        this.libreEn = 0;
        this.enRetorno = false;
    }
    public void appendToHistory(List<Point> path) {
        if (path != null) history.addAll(path);
        // Si es la primera vez que se llama, agregar la posición actual
        if (history.isEmpty()) {
            history.add(new Point(x, y)); // posición actual del camión
        }
        history.addAll(path);
    }
    // → getters que necesitas en la UI:
    public String getId() { return id; }
    public double getCapacidad() { return capacidad; }
    public double getDisponible() { return disponible; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getLibreEn() { return libreEn; }
    public boolean isEnRetorno() { return enRetorno; }
    public List<Point> getRuta() {
        return ruta;
    }
    public List<Point> getHistory() {
        return history;
    }
    public double getConsumoAcumulado() {
        return consumoAcumulado;
    }
    // → setters o métodos de estado, si los usas en la planificación:
    public void setX(int x) { this.x = x; }
    public void setY(int y) { this.y = y; }
    public void setDisponible(double d) { this.disponible = d; }
    public void setLibreEn(int t) { this.libreEn = t; }
    public void setEnRetorno(boolean r) { this.enRetorno = r; }
    /** Define la ruta Manhattan paso a paso */
    public void setRuta(List<Point> ruta) {
        this.rutaActual = new ArrayList<>(ruta);
        this.pasoActual = 0;
    }
    /** ¿Le quedan pasos por recorrer? */
    public boolean tienePasosPendientes() {
        return pasoActual < rutaActual.size();
    }

    /** Avanza un solo paso en la ruta */
    public void avanzarUnPaso() {
        double pesoTotal = pesoTara + (this.disponible * pesoCargoPorM3);
        consumoAcumulado += pesoTotal / 180.0;
        if (!tienePasosPendientes()) return;
        Point p = rutaActual.get(pasoActual++);
        moverA(p);
    }
    public void moverA(Point p) {
        this.x = p.x;
        this.y = p.y;
        history.add(new Point(x, y));
    }
}
