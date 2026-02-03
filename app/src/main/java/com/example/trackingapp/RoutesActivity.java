package com.example.trackingapp;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.influxdb.client.DeleteApi;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RoutesActivity extends AppCompatActivity {
    private ListView routesListView;
    private InfluxDBClient influxDBClient;
    private final List<Map<String, String>> routesList = new ArrayList<>();
    private SimpleAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Ustawienie ciemnego tła dla całej aktywności
        setContentView(R.layout.activity_routes);
        // Pobieramy root view i ustawiamy kolor tła (jeśli w xml nie jest ustawiony)
        findViewById(android.R.id.content).setBackgroundColor(getResources().getColor(R.color.colorPrimary));

        routesListView = findViewById(R.id.routesListView);

        // Usunięcie domyślnych separatorów listy, bo mamy teraz ładne karty
        routesListView.setDivider(null);
        routesListView.setDividerHeight(0);

        initializeInfluxDB();
        fetchRoutes();
    }

    private void initializeInfluxDB() {
        // Pobieranie danych z AppConstants
        String url = AppConstants.BASE_URL;
        String token = AppConstants.TOKEN;
        influxDBClient = InfluxDBClientFactory.create(url, token.toCharArray());
    }

    private void fetchRoutes() {
        new Thread(() -> {
            try {
                String query = String.format("""
    from(bucket: "%s")
      |> range(start: 0)
      |> filter(fn: (r) => r._measurement == "location")
      |> keep(columns: ["name", "_time"])
      |> unique(column: "name")
      |> sort(columns: ["_time"], desc: true)
    """, AppConstants.BUCKET);

                routesList.clear();

                influxDBClient.getQueryApi().query(query, AppConstants.ORG).forEach(table -> table.getRecords().forEach(record -> {
                    String name = "Nieznana";
                    if(record.getValueByKey("name") != null)
                        name = record.getValueByKey("name").toString();

                    String dateStr = record.getTime().toString();
                    // Proste formatowanie daty
                    String date = dateStr.substring(0, 10) + " " + dateStr.substring(11, 16);

                    Map<String, String> map = new HashMap<>();
                    map.put("name", name);
                    map.put("date", date);
                    map.put("full_time", dateStr); // do przekazania dalej
                    routesList.add(map);
                }));

                runOnUiThread(() -> {
                    // TUTAJ PODPINAMY NOWY LAYOUT (route_list_item)
                    adapter = new SimpleAdapter(
                            RoutesActivity.this,
                            routesList,
                            R.layout.route_list_item, // Nowy plik layoutu
                            new String[]{"name", "date"},
                            new int[]{R.id.routeName, R.id.routeDate}
                    ) {
                        @Override
                        public View getView(int position, View convertView, android.view.ViewGroup parent) {
                            View view = super.getView(position, convertView, parent);

                            // Obsługa kliknięcia w KOSZ (usuwanie)
                            View deleteButton = view.findViewById(R.id.deleteRoute);
                            deleteButton.setOnClickListener(v -> {
                                String selectedRoute = routesList.get(position).get("name");
                                showDeleteConfirmationDialog(selectedRoute, position);
                            });

                            // Obsługa kliknięcia w CAŁĄ KARTĘ (otwarcie mapy)
                            view.setOnClickListener(v -> {
                                String selectedRoute = routesList.get(position).get("name");
                                String time = routesList.get(position).get("date");
                                Intent intent = new Intent(RoutesActivity.this, MapActivity.class);
                                intent.putExtra("routeName", selectedRoute);
                                intent.putExtra("routeDuration", time);
                                startActivity(intent);
                            });

                            return view;
                        }
                    };
                    routesListView.setAdapter(adapter);
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void showDeleteConfirmationDialog(String routeName, int position) {
        new AlertDialog.Builder(this)
                .setTitle("Usuń trasę")
                .setMessage("Czy na pewno chcesz usunąć trasę: " + routeName + "?")
                .setPositiveButton("Usuń", (dialog, which) -> deleteRoute(routeName, position))
                .setNegativeButton("Anuluj", null)
                .show();
    }

    private void deleteRoute(String routeName, int position) {
        new Thread(() -> {
            try {
                DeleteApi deleteApi = influxDBClient.getDeleteApi();

                OffsetDateTime start = OffsetDateTime.parse("1970-01-01T00:00:00Z");
                OffsetDateTime stop = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

                String predicate = String.format("_measurement=\"location\" AND name=\"%s\"", routeName);

                // Próba usunięcia
                deleteApi.delete(start, stop, predicate, AppConstants.BUCKET, AppConstants.ORG);

                runOnUiThread(() -> {
                    routesList.remove(position);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Trasa usunięta!", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                // TUTAJ JEST FIX DLA TWOJEGO BŁĘDU 405
                String msg = e.getMessage();
                Log.e("InfluxDelete", "Błąd: " + msg);

                runOnUiThread(() -> {
                    if (msg != null && msg.contains("405")) {
                        // Jeśli to błąd 405, wyświetlamy ładny komunikat
                        new AlertDialog.Builder(RoutesActivity.this)
                                .setTitle("Funkcja niedostępna")
                                .setMessage("Twoja baza danych (InfluxDB Cloud Serverless) nie obsługuje usuwania pojedynczych tras. To ograniczenie darmowej wersji chmury.")
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        // Inny błąd
                        Toast.makeText(this, "Błąd usuwania: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (influxDBClient != null) {
            influxDBClient.close();
        }
        super.onDestroy();
    }
}