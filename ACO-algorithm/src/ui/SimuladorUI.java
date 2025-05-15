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

    public SimuladorUI() {
        super("Simulador GLP");

        // 0) Carga los datos iniciales desde archivos
        List<Pedido> pedidos = new ArrayList<>();
        List<Bloqueo> bloqueos = new ArrayList<>();
        Map<String, Map<String, String>> averias = new HashMap<>();
        try {
            pedidos   = ACOPlanner.cargarPedidos("pedidos.txt");
            bloqueos  = ACOPlanner.cargarBloqueos("bloqueos.txt");
            averias   = ACOPlanner.cargarAverias("averias.txt");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Error cargando datos:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }

        // 1) Crea el planner con los datos cargados
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
        btnEjecutar.setEnabled(false);
        new Thread(() -> {
            // 1) Ejecuta la simulación completa de 2 días (usa ACO internamente)
            planner.simularDiaADia(1440 * 2);

            // 2) Regresamos al hilo principal para actualizar la vista
            SwingUtilities.invokeLater(() -> {
                refreshTables();

                // 3) Mostramos la ruta del primer camión como ejemplo
                Camion c = planner.getFlota().get(0);
                mapPanel.setSelectedCamion(c);
                mapPanel.repaint();

                btnEjecutar.setEnabled(true);
            });
        }).start();
    }

    /** Reinicia el estado de la simulación */
    private void onLimpiar() {
        planner.reset();    // debes implementar reset() en ACOPlanner
        refreshTables();
        mapPanel.repaint();
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

    /** Punto de entrada de la aplicación */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SimuladorUI ui = new SimuladorUI();
            ui.setVisible(true);
        });
    }
}
