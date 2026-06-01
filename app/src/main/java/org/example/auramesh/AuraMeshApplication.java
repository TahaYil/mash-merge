package org.example.auramesh;

import android.app.Application;
import org.example.auramesh.hardware.Bluetooth.AuraBluetoothService;
import org.example.auramesh.hardware.BluetoothLE.AuraBleAdvertiser;
import org.example.auramesh.hardware.BluetoothLE.AuraBleScanner;
import org.example.auramesh.routing.GossipRouter;
import org.example.auramesh.routing.StateManager;

public class AuraMeshApplication extends Application {

    private AuraBluetoothService bluetoothService;
    private AuraBleAdvertiser bleAdvertiser;
    private AuraBleScanner bleScanner;
    private GossipRouter gossipRouter;
    private StateManager stateManager;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    public void startMeshServices() {
        if (bluetoothService == null) {
            stateManager = new StateManager(this);
            bluetoothService = new AuraBluetoothService();
            gossipRouter = new GossipRouter(this);
            bleAdvertiser = new AuraBleAdvertiser();
            bleScanner = new AuraBleScanner();
            bleAdvertiser.start();
            bleScanner.start();
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (bluetoothService != null) bluetoothService.onDestroy();
        if (gossipRouter != null) gossipRouter.onDestroy();
        if (stateManager != null) stateManager.onDestroy();
        if (bleAdvertiser != null) bleAdvertiser.stop();
        if (bleScanner != null) bleScanner.stop();
    }
}
