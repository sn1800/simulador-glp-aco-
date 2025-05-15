package core;

public class Tanque {
    int x, y;
    double capacidadTotal;
    double disponible;
    Tanque(int x, int y, double cap) {
        this.x = x; this.y = y;
        this.capacidadTotal = cap;
        this.disponible    = cap;
    }
    // → getters para pintar el mapa:
    public double getCapacidadTotal() { return capacidadTotal; }
    public double getDisponible()    { return disponible; }
    public int getX()                { return x; }
    public int getY()                { return y; }

    // → cuando recargas o descontas:
    public void setDisponible(double d) { this.disponible = d; }
}
