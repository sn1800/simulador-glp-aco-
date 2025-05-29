package ui;

import core.ACOPlanner;
import core.Pedido;
import core.Camion;
import core.Bloqueo;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Interfaz gráfica principal para el simulador GLP.
 */
public class SimuladorUI extends JFrame {
    private final ACOPlanner planner;
    private final MapPanel mapPanel;
    private final JTable camionesTable;
    private final JTable pedidosTable;
    private final JButton btnEjecutar;
    private final JButton btnLimpiar;
    private final JButton btnAgregarAveria;
    private final List<Pedido> pedidos;      // ← field
    private final List<Bloqueo> bloqueos;    // ← field
    private final Map<String,Map<String,String>> averias; // ← field


    private Timer timer;
    public SimuladorUI() {
        super("Simulador GLP");

        // 0) Carga los datos iniciales desde archivos
        this.pedidos  = ACOPlanner.cargarPedidos("pedidos.txt");
        this.bloqueos = ACOPlanner.cargarBloqueos("bloqueos.txt");
        this.averias  = ACOPlanner.cargarAverias("averias.txt");
        this.planner  = new ACOPlanner(pedidos, bloqueos, averias);
        this.mapPanel = new MapPanel(planner);

        // Configuración básica de la ventana
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1024, 768);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 2) Panel de controles (botones)
        JPanel controlPanel = new JPanel();
        btnEjecutar      = new JButton("Ejecutar ACO");
        btnLimpiar       = new JButton("Limpiar");
        btnAgregarAveria = new JButton("Agregar Avería");
        controlPanel.add(btnEjecutar);
        controlPanel.add(btnLimpiar);
        controlPanel.add(btnAgregarAveria);
        add(controlPanel, BorderLayout.NORTH);

        // 3) Panel central: mapa interactivo
        add(mapPanel, BorderLayout.CENTER);

        // 4) Panel derecho: tablas de camiones y pedidos
        JTabbedPane tabbedPane = new JTabbedPane();
        camionesTable = new JTable(new DefaultTableModel(new Object[]{"ID","Pos","Disponible","LibreEn"}, 0));
        pedidosTable  = new JTable(new DefaultTableModel(new Object[]{"ID","Destino","Volumen","Límite","Atendido"}, 0));
        tabbedPane.addTab("Camiones", new JScrollPane(camionesTable));
        tabbedPane.addTab("Pedidos",  new JScrollPane(pedidosTable));
        // ↓ Cuando el usuario seleccione un camión en la tabla…
        camionesTable.getSelectionModel().addListSelectionListener(e -> {
                int row = camionesTable.getSelectedRow();
                if (row >= 0) {
                        String id = (String)camionesTable.getValueAt(row, 0);
                        Camion c = planner.findCamion(id);
                        mapPanel.setSelectedCamion(c);
                    }
        });
        tabbedPane.setPreferredSize(new Dimension(300, 0));
        add(tabbedPane, BorderLayout.EAST);

        // 5) Listeners de los botones
        btnEjecutar.addActionListener(e -> onEjecutar());
        btnLimpiar .addActionListener(e -> onLimpiar());
        btnAgregarAveria.addActionListener(e -> onAgregarAveria());

        // 6) Carga inicial de las tablas
        refreshTables();
    }

    /** Arranca la simulación en segundo plano */
    private void onEjecutar() {
        planner.reset();

        // ——————————————  0.a) Fraccionar pedidos demasiado grandes ——————————————
           // calculamos la capacidad máxima de un solo camión
                   double maxCapacidad = planner.getFlota().stream()
                   .mapToDouble(c -> c.getCapacidad())
                   .max()
                   .orElse(0);

           // construimos una lista nueva partiendo cada pedido > maxCapacidad
                   List<Pedido> originales = planner.getPedidos();
        List<Pedido> particionados = new ArrayList<>();
        for (Pedido p : originales) {
           if (p.getVolumen() > maxCapacidad) {
                  int nPartes = (int) Math.ceil(p.getVolumen() / maxCapacidad);
                   double restante = p.getVolumen();
                   for (int i = 1; i <= nPartes; i++) {
                           double parte = Math.min(maxCapacidad, restante);
                           Pedido sub = new Pedido(
                                       p.getId() * 100 + i,
                                       p.getTiempoCreacion(),
                                       p.getX(),
                                       p.getY(),
                                       parte,
                                       p.getTiempoLimite()
                                           );
                           particionados.add(sub);
                           restante -= parte;
                       }
               } else {
                   particionados.add(p);
               }
        }
        // reemplazamos la lista interna del planner
           planner.setPedidos(particionados);

           // ahora sí construimos el mapa tiempo→pedidos
                   Map<Integer,List<Pedido>> pedidosPorTiempo = new HashMap<>();
        for (Pedido p : planner.getPedidos()) {
           pedidosPorTiempo
                       .computeIfAbsent(p.getTiempoCreacion(), k->new ArrayList<>())
                       .add(p);
        }
        // —————————————————————————————————————————————————————————————


        planner.reset();
        // **PRE-SELECCIÓN** del camión 0 para que MapPanel ya lo pinte
        if (!planner.getFlota().isEmpty()) {
            mapPanel.setSelectedCamion(planner.getFlota().get(0));
        }
        // pinta el estado inicial (t=0) antes de empezar el timer
        onTick(0);
        btnEjecutar.setEnabled(false);
        Timer t = new Timer(100, null);
        t.addActionListener(ev -> {
            int now = planner.stepOneMinute(pedidosPorTiempo);
            onTick(now);
            if (planner.isFinished()) {
                ((Timer)ev.getSource()).stop();
                btnEjecutar.setEnabled(true);
            }
        });
        t.start();
    }

    /** Reinicia el estado de la simulación */
    private void onLimpiar() {
        planner.reset();    // debes implementar reset() en ACOPlanner
        mapPanel.repaint();
        refreshTables();
    }

    /** Pide datos de avería y los registra en el planner */
    private void onAgregarAveria() {
        String camionId = JOptionPane.showInputDialog(this, "ID del camión:");
        if (camionId == null || camionId.trim().isEmpty()) return;
        String turno = JOptionPane.showInputDialog(this, "Turno (T1,T2,T3):");
        String tipo  = JOptionPane.showInputDialog(this, "Tipo (T1=30m, T2=60m, T3=90m):");
        planner.agregarAveria(turno.trim(), camionId.trim(), tipo.trim());
        refreshTables();
    }

    /** Recarga las tablas de camiones y pedidos desde el planner */
    private void refreshTables() {
        DefaultTableModel mCam = (DefaultTableModel) camionesTable.getModel();
        mCam.setRowCount(0);
        for (Camion c : planner.getFlota()) {
            mCam.addRow(new Object[]{
                    c.getId(),
                    c.getX() + "," + c.getY(),
                    String.format("%.1f", c.getDisponible()),
                    c.getLibreEn()
            });
        }

        DefaultTableModel mPed = (DefaultTableModel) pedidosTable.getModel();
        mPed.setRowCount(0);
        for (Pedido p : planner.getPedidos()) {
            mPed.addRow(new Object[]{
                    p.getId(),
                    p.getX() + "," + p.getY(),
                    String.format("%.1f", p.getVolumen()),
                    p.getTiempoLimite(),
                    p.isAtendido()
            });
        }
    }
    private void onTick(int tiempoActual) {
        mapPanel.setCurrentTime(tiempoActual);
        mapPanel.setBloqueos(planner.getBloqueos());
        mapPanel.repaint();
        refreshTables();  // si quieres actualizar posición/estado de tablas en cada minuto
    }
    /** Punto de entrada de la aplicación */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimuladorUI ui = new SimuladorUI();
            ui.setVisible(true);
        });
    }
}
