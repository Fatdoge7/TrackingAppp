package com.example.trackingapp;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;
import com.example.trackingapp.AppConstants;

public class InfluxHelper {

    public static List<GeoPoint> fetchRoutePoints(String routeName) {
        List<GeoPoint> points = new ArrayList<>();

        // Pobieramy dane z AppConstants
        String url = AppConstants.BASE_URL;
        String token = AppConstants.TOKEN;
        String org = AppConstants.ORG;
        String bucket = AppConstants.BUCKET;

        InfluxDBClient client = InfluxDBClientFactory.create(url, token.toCharArray());

        // Uwaga: Tutaj też musi być nazwa bucketa pobierana dynamicznie lub wpisana poprawnie
        String query = String.format("""
            from(bucket: "%s")
              |> range(start: 0)
              |> filter(fn: (r) => r._measurement == "location" and r.name == "%s")
              |> pivot(rowKey:["_time"], columnKey: ["_field"], valueColumn: "_value")
              |> sort(columns: ["_time"])
              |> keep(columns: ["latitude", "longitude"])
            """, bucket, routeName);

        try {
            client.getQueryApi().query(query, org).forEach(table -> {
                table.getRecords().forEach(record -> {
                    Double lat = (Double) record.getValueByKey("latitude");
                    Double lon = (Double) record.getValueByKey("longitude");
                    if (lat != null && lon != null) {
                        points.add(new GeoPoint(lat, lon));
                    }
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            client.close();
        }
        return points;
    }
}
