package com.example.runtracker;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private MapView mapView;
    private Polyline routePolyline;
    private LocationManager locationManager;
    private LocationListener locationListener;

    private Button btnStartStop;
    private TextView tvDistance, tvSpeed, tvAltitude, tvTime;

    private final List<GeoPoint> routePoints = new ArrayList<>();
    private Location lastLocation;
    private double totalDistanceMeters = 0;
    private long startTimeMillis = 0;
    private boolean isTracking = false;

    // Запрос разрешения на геолокацию (это разрешение Android, не Google — без аккаунта)
    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startTracking();
                } else {
                    tvDistance.setText("Нужно разрешение на геолокацию");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Настройка osmdroid ДО setContentView.
        // Храним тайлы карты в кэше самого приложения — без доступа к внешней памяти.
        Configuration.getInstance().setOsmdroidBasePath(new File(getCacheDir(), "osmdroid"));
        Configuration.getInstance().setOsmdroidTileCache(new File(getCacheDir(), "osmdroid/tiles"));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDistance = findViewById(R.id.tvDistance);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvTime = findViewById(R.id.tvTime);
        btnStartStop = findViewById(R.id.btnStartStop);

        mapView = findViewById(R.id.map);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false); // pinch-to-zoom уже есть, кнопки +/- не нужны
        mapView.getController().setZoom(15.0);
        // Дефолтный центр, чтобы при первом запуске (до получения GPS-фикса)
        // не было видно точку (0, 0) посреди океана
        mapView.getController().setCenter(new GeoPoint(51.1694, 71.4491)); // Астана, можно заменить на свой город

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        btnStartStop.setOnClickListener(v -> {
            if (!isTracking) {
                checkPermissionAndStart();
            } else {
                stopTracking();
            }
        });

        createLocationListener();
    }

    private void checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void createLocationListener() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                onNewLocation(location);
            }
        };
    }

    /** Сброс состояния и старт новой пробежки. */
    private void startTracking() {
        isTracking = true;
        routePoints.clear();
        totalDistanceMeters = 0;
        lastLocation = null;
        routePolyline = null;
        startTimeMillis = System.currentTimeMillis();
        btnStartStop.setText("Стоп");

        mapView.getOverlays().clear();
        mapView.invalidate();

        requestLocationUpdates();
    }

    /** Только подписка на обновления локации (используется и в onResume). */
    private void requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // Обновления каждые 2 секунды или каждые 3 метра смещения
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000, 3, locationListener, Looper.getMainLooper());
    }

    private void stopTracking() {
        isTracking = false;
        btnStartStop.setText("Начать");
        locationManager.removeUpdates(locationListener);

        if (!routePoints.isEmpty()) {
            addMarker(routePoints.get(0), "Старт");
            addMarker(routePoints.get(routePoints.size() - 1), "Финиш");
            mapView.invalidate();
        }
    }

    private void addMarker(GeoPoint point, String title) {
        Marker marker = new Marker(mapView);
        marker.setPosition(point);
        marker.setTitle(title);
        mapView.getOverlays().add(marker);
    }

    private void onNewLocation(Location location) {
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        routePoints.add(point);

        if (lastLocation != null) {
            totalDistanceMeters += lastLocation.distanceTo(location);
        }
        lastLocation = location;

        updateMap(point);
        updateStats(location);
    }

    private void updateMap(GeoPoint point) {
        if (routePolyline == null) {
            routePolyline = new Polyline();
            routePolyline.setWidth(8f);
            mapView.getOverlays().add(routePolyline);
        }
        List<GeoPoint> points = new ArrayList<>(routePolyline.getPoints());
        points.add(point);
        routePolyline.setPoints(points);

        mapView.getController().animateTo(point);
        mapView.invalidate();
    }

    private void updateStats(Location location) {
        double distanceKm = totalDistanceMeters / 1000.0;
        double speedKmh = location.getSpeed() * 3.6; // м/с -> км/ч
        double altitude = location.getAltitude();

        long elapsedMs = System.currentTimeMillis() - startTimeMillis;
        long minutes = (elapsedMs / 1000) / 60;
        long seconds = (elapsedMs / 1000) % 60;

        tvDistance.setText(String.format(Locale.getDefault(), "Дистанция: %.2f км", distanceKm));
        tvSpeed.setText(String.format(Locale.getDefault(), "Скорость: %.1f км/ч", speedKmh));
        tvAltitude.setText(String.format(Locale.getDefault(), "Высота: %.0f м", altitude));
        tvTime.setText(String.format(Locale.getDefault(), "Время: %02d:%02d", minutes, seconds));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        if (isTracking) {
            requestLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
        if (isTracking) {
            locationManager.removeUpdates(locationListener);
        }
    }
}
