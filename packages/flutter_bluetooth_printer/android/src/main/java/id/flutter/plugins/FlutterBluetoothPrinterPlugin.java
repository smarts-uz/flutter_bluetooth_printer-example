package id.flutter.plugins;

import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.app.Activity;
import android.app.AsyncNotedAppOp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, EventChannel.StreamHandler {
    private MethodChannel channel;
    private Activity activity;
    private BluetoothAdapter bluetoothAdapter;
    private FlutterPluginBinding flutterPluginBinding;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            BluetoothManager bluetoothManager = flutterPluginBinding.getApplicationContext().getSystemService(BluetoothManager.class);
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }


        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer");
        channel.setMethodCallHandler(this);

        EventChannel discoveryChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "maseka.dev/flutter_bluetooth_printer/discovery");
        discoveryChannel.setStreamHandler(this);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        flutterPluginBinding.getApplicationContext().registerReceiver(receiver, filter);

        IntentFilter stateFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        flutterPluginBinding.getApplicationContext().registerReceiver(stateReceiver, stateFilter);
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int value = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                if (value == BluetoothAdapter.STATE_ON) {
                    channel.invokeMethod("onBluetoothStateChanged", 2);
                } else if (value == BluetoothAdapter.STATE_OFF) {
                    channel.invokeMethod("onBluetoothStateChanged", 1);
                }
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final Map<String, Object> map = deviceToMap(device);

                for (EventChannel.EventSink sink : sinkList.values()) {
                    sink.success(map);
                }
            }
        }
    };

    private Map<String, Object> deviceToMap(BluetoothDevice device) {
        final HashMap<String, Object> map = new HashMap<>();
        map.put("name", device.getName());
        map.put("address", device.getAddress());
        map.put("type", device.getType());
        return map;
    }

    private boolean ensurePermission() {
        if (SDK_INT >= Build.VERSION_CODES.M) {
            if (SDK_INT >= 31) {
                 final boolean bluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                 final boolean bluetoothScan = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                 final boolean bluetoothConnect = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;

                 if ( bluetooth && bluetoothScan && bluetoothConnect){
                     return true;
                 }

                activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 919191);
            } else {
                boolean bluetooth = activity.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED;
                boolean fineLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarseLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                if (bluetooth && (fineLocation || coarseLocation)) {
                    return true;
                }

                activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 919191);
            }

            return false;
        }

        return true;
    }

    private void startDiscovery() {
        if (!ensurePermission()) {
            return;
        }

        if (!bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.startDiscovery();
        }

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : bonded) {
            final Map<String, Object> map = deviceToMap(device);
            for (EventChannel.EventSink sink : sinkList.values()) {
                sink.success(map);
            }
        }
    }


    private void stopDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
    }

    private void updatePrintingProgress(int total, int progress) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Map<String, Object> data = new HashMap<>();
            data.put("total", total);
            data.put("progress", progress);

            channel.invokeMethod("onPrintingProgress", data);
        });
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        final String method = call.method;
        switch (method) {
            case "getState": {
                if (!ensurePermission()){
                    result.success(3);
                    return;
                }

                if (!bluetoothAdapter.isEnabled()){
                    result.success(1);
                    return;
                }

                final int state = bluetoothAdapter.getState();
                if (state == BluetoothAdapter.STATE_OFF){
                    result.success(1);
                    return;
                }

                if (state == BluetoothAdapter.STATE_ON){
                    result.success(2);
                    return;
                }
                return;
            }

            case "write": {
                // CONNECTING
                channel.invokeMethod("didUpdateState", 1);

                AsyncTask.execute(() -> {
                    synchronized (FlutterBluetoothPrinterPlugin.this) {
                        try {
                            String address = call.argument("address");
                            byte[] data = call.argument("data");

                            final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                            BluetoothSocket bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                            bluetoothSocket.connect();
                            try {
                                OutputStream writeStream = bluetoothSocket.getOutputStream();

                                // PRINTING
                                new Handler(Looper.getMainLooper()).post(() -> channel.invokeMethod("didUpdateState", 2));
                                updatePrintingProgress(data.length, 0);

                                writeStream.write(data);
                                writeStream.flush();

                                updatePrintingProgress(data.length, data.length);

                                // waiting for printing completed
                                Thread.sleep(3000);
                                writeStream.close();

                                new Handler(Looper.getMainLooper()).post(() -> {
                                    // COMPLETED
                                    channel.invokeMethod("didUpdateState", 3);

                                    // DONE
                                    result.success(true);
                                });
                            } finally {
                                bluetoothSocket.close();
                            }
                        } catch (Exception e) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                result.error("error", e.getMessage(), null);
                            });
                        }
                    }
                });
                return;
            }

            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(receiver);
        flutterPluginBinding.getApplicationContext().unregisterReceiver(stateReceiver);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 919191) {
            for (int i = 0; i < grantResults.length; i++) {
                final int result = grantResults[i];
                if (result != PackageManager.PERMISSION_GRANTED) {
                    for (EventChannel.EventSink sink : sinkList.values()) {
                        sink.error("permissionDenied", permissions[i], "");
                    }

                    channel.invokeMethod("onBluetoothStateChanged", 3);
                    return true;
                }
            }

            channel.invokeMethod("onBluetoothStateChanged", 4);
            startDiscovery();
            return true;
        }

        return false;
    }

    private final Map<Object, EventChannel.EventSink> sinkList = new HashMap<>();

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        sinkList.put(arguments, events);
        startDiscovery();
    }

    @Override
    public void onCancel(Object arguments) {
        sinkList.remove(arguments);
        if (sinkList.isEmpty()) {
            stopDiscovery();
        }
    }
}
