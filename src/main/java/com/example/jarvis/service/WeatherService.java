package com.example.jarvis.service;

import com.example.jarvis.dto.BestMonths;
import com.example.jarvis.dto.WeatherDay;
import com.example.jarvis.model.ActivityType;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// open-meteo: free, no key needed
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max";
    private static final int FORECAST_DAYS = 15;

    private final RestClient http;
    private final String forecastUrl;
    private final String archiveUrl;
    private final Map<String, double[][]> climateCache = new ConcurrentHashMap<>();

    public WeatherService(RestClient.Builder builder,
                          @Value("${jarvis.url.open-meteo-forecast}") String forecastUrl,
                          @Value("${jarvis.url.open-meteo-archive}") String archiveUrl) {
        this.http = builder.build();
        this.forecastUrl = forecastUrl;
        this.archiveUrl = archiveUrl;
    }

    public Map<LocalDate, WeatherDay> daily(double lat, double lng, LocalDate from, LocalDate to) {
        Map<LocalDate, WeatherDay> out = new TreeMap<>();
        if (from == null || to == null || to.isBefore(from)) return out;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate forecastEnd = today.plusDays(FORECAST_DAYS);

        // real forecast for the near dates
        if (!from.isAfter(forecastEnd) && !to.isBefore(today)) {
            LocalDate s = from.isBefore(today) ? today : from;
            LocalDate e = to.isAfter(forecastEnd) ? forecastEnd : to;
            out.putAll(fetch(forecastUrl, lat, lng, s, e, 0, "FORECAST"));
        }
        // anything else: same dates last year as a "typical weather" stand-in
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!out.containsKey(d)) missing.add(d);
        }
        if (!missing.isEmpty()) {
            LocalDate s = missing.get(0);
            LocalDate e = missing.get(missing.size() - 1);
            int yearsBack = 1;
            while (!e.minusYears(yearsBack).isBefore(today.minusDays(6))) yearsBack++;
            Map<LocalDate, WeatherDay> past = fetch(archiveUrl, lat, lng, s.minusYears(yearsBack), e.minusYears(yearsBack),
                    yearsBack, "TYPICAL");
            for (LocalDate d : missing) {
                WeatherDay w = past.get(d);
                if (w != null) out.put(d, w);
            }
        }
        return out;
    }

    private Map<LocalDate, WeatherDay> fetch(String base, double lat, double lng, LocalDate s, LocalDate e,
                                             int shiftYears, String source) {
        Map<LocalDate, WeatherDay> out = new HashMap<>();
        try {
            URI uri = UriComponentsBuilder.fromUriString(base)
                    .queryParam("latitude", lat)
                    .queryParam("longitude", lng)
                    .queryParam("daily", DAILY)
                    .queryParam("timezone", "auto")
                    .queryParam("start_date", s.toString())
                    .queryParam("end_date", e.toString())
                    .build().encode().toUri();
            JsonNode root = JsonSupport.parse(http.get().uri(uri).retrieve().body(String.class));
            if (root == null) return out;
            JsonNode daily = root.path("daily");
            JsonNode times = daily.path("time");
            for (int i = 0; i < times.size(); i++) {
                LocalDate d = LocalDate.parse(times.get(i).asText()).plusYears(shiftYears);
                Integer code = num(daily.path("weather_code").path(i)) == null ? null : daily.path("weather_code").path(i).asInt();
                Double tMax = num(daily.path("temperature_2m_max").path(i));
                Double tMin = num(daily.path("temperature_2m_min").path(i));
                Double precip = num(daily.path("precipitation_sum").path(i));
                Double wind = num(daily.path("wind_speed_10m_max").path(i));
                out.put(d, new WeatherDay(d, code, tMax, tMin, precip, wind, describe(code), source));
            }
        } catch (Exception ex) {
            log.warn("open-meteo call failed ({}): {}", source, ex.getMessage());
        }
        return out;
    }

    private static Double num(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    public static String describe(Integer code) {
        if (code == null) return "Unknown";
        if (code == 0) return "Clear sky";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 65 || code == 67) return "Heavy rain";
        if (code >= 61 && code <= 67) return "Rain";
        if (code == 75 || code == 86) return "Heavy snow";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return code == 82 ? "Violent rain showers" : "Rain showers";
        if (code == 85) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "Mixed";
    }

    // what's wrong with the day for an outdoor plan, null if nothing
    public static String severeReason(WeatherDay w) {
        if (w == null) return null;
        Integer c = w.code();
        double wind = w.windKmh() == null ? 0 : w.windKmh();
        double precip = w.precipitationMm() == null ? 0 : w.precipitationMm();
        boolean heavySnow = c != null && (c == 75 || c == 86);
        if (heavySnow && wind >= 40) return "blizzard conditions (heavy snow and " + Math.round(wind) + " km/h winds)";
        if (heavySnow) return "heavy snow";
        if (c != null && c >= 95) return "thunderstorms";
        if (c != null && (c == 65 || c == 67 || c == 82)) return "heavy rain";
        if (precip >= 20) return Math.round(precip) + " mm of rain";
        if (wind >= 55) return "very strong winds (" + Math.round(wind) + " km/h)";
        if (w.tempMaxC() != null && w.tempMaxC() >= 38) return "extreme heat (" + Math.round(w.tempMaxC()) + "°C)";
        if (w.tempMinC() != null && w.tempMinC() <= -18) return "extreme cold (" + Math.round(w.tempMinC()) + "°C)";
        return null;
    }

    // indoor-friendly swap for a bad weather day
    public static ActivityType saferAlternative(ActivityType chosen, WeatherDay w) {
        if (chosen == null || !chosen.isOutdoor()) return null;
        if (w != null && w.tempMaxC() != null && w.tempMaxC() >= 38) return ActivityType.RELAXING;
        return ActivityType.HISTORICAL;
    }

    public BestMonths bestMonths(double lat, double lng, ActivityType type) {
        double[][] monthly = climate(lat, lng);
        String note;
        if (monthly == null) {
            monthly = roughClimate(lat);
            note = "Rough estimate from latitude (weather service unreachable).";
        } else {
            note = "Based on last year's daily weather from Open-Meteo.";
        }
        Integer[] order = new Integer[12];
        for (int i = 0; i < 12; i++) order[i] = i;
        final double[][] m = monthly;
        Arrays.sort(order, Comparator.comparingDouble(i -> -score(type, m[i][0], m[i][1])));
        List<String> best = new ArrayList<>();
        for (int i = 0; i < 3; i++) best.add(Month.of(order[i] + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        return new BestMonths(type, best, note);
    }

    // m[month] = {avg max temp, total precip}
    private double[][] climate(double lat, double lng) {
        String k = Math.round(lat * 10) + ":" + Math.round(lng * 10);
        if (climateCache.containsKey(k)) return climateCache.get(k);
        int year = LocalDate.now(ZoneOffset.UTC).getYear() - 1;
        try {
            URI uri = UriComponentsBuilder.fromUriString(archiveUrl)
                    .queryParam("latitude", lat)
                    .queryParam("longitude", lng)
                    .queryParam("daily", "temperature_2m_max,precipitation_sum")
                    .queryParam("timezone", "auto")
                    .queryParam("start_date", year + "-01-01")
                    .queryParam("end_date", year + "-12-31")
                    .build().encode().toUri();
            JsonNode root = JsonSupport.parse(http.get().uri(uri).retrieve().body(String.class));
            if (root == null) return null;
            JsonNode times = root.path("daily").path("time");
            if (times.size() == 0) return null;
            double[][] m = new double[12][2];
            int[] counts = new int[12];
            for (int i = 0; i < times.size(); i++) {
                int month = LocalDate.parse(times.get(i).asText()).getMonthValue() - 1;
                JsonNode t = root.path("daily").path("temperature_2m_max").path(i);
                JsonNode p = root.path("daily").path("precipitation_sum").path(i);
                if (!t.isNull()) {
                    m[month][0] += t.asDouble();
                    counts[month]++;
                }
                if (!p.isNull()) m[month][1] += p.asDouble();
            }
            for (int i = 0; i < 12; i++) if (counts[i] > 0) m[i][0] /= counts[i];
            climateCache.put(k, m);
            return m;
        } catch (Exception e) {
            log.warn("open-meteo climate failed: {}", e.getMessage());
            return null;
        }
    }

    static double[][] roughClimate(double lat) {
        double[][] m = new double[12][2];
        double abs = Math.abs(lat);
        double meanMax = 30 - abs * 0.35;
        double swing = Math.min(18, abs * 0.3);
        for (int i = 0; i < 12; i++) {
            // northern summer peaks in july, southern in january
            double phase = Math.cos(2 * Math.PI * (i - 6) / 12.0);
            if (lat < 0) phase = -phase;
            m[i][0] = meanMax + swing * phase;
            m[i][1] = 70 - 25 * phase;
        }
        return m;
    }

    static double score(ActivityType type, double tMax, double precip) {
        double comfort;
        switch (type) {
            case ADVENTURE, SCENIC -> comfort = -Math.abs(tMax - 22) * 2 - precip * 0.25;
            case RELAXING -> comfort = -Math.abs(tMax - 25) * 1.5 - precip * 0.2;
            case PARTY -> comfort = -Math.abs(tMax - 24) - precip * 0.1;
            default -> comfort = -Math.abs(tMax - 20) - precip * 0.05;
        }
        return comfort;
    }
}
