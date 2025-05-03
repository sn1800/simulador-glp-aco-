public class TiempoUtils {
    public static int parsearMarcaDeTiempo(String marca) {
        // Ejemplo de entrada: 01d00h24m
        String[] partes = marca.split("[dhm]");
        int dias = Integer.parseInt(partes[0]);
        int horas = Integer.parseInt(partes[1]);
        int minutos = Integer.parseInt(partes[2]);
        return dias * 24 * 60 + horas * 60 + minutos;
    }
}
