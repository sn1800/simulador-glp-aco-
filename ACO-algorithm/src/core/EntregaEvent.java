package core;

public class EntregaEvent {
    int time;
    Camion camion;
    Pedido pedido;
    EntregaEvent(int time, Camion camion, Pedido pedido) {
        this.time = time;
        this.camion = camion;
        this.pedido = pedido;
    }
}
