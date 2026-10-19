package app.stockdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal stock watchlist. Tickers are stored only on this device (SharedPreferences).
 * Prices come from Yahoo Finance's public chart endpoint (no account or API key needed)
 * and refresh every minute while the app is open.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "watchlist";
    private static final String KEY_TICKERS = "tickers";
    private static final long REFRESH_MS = 60_000;

    private final List<String> tickers = new ArrayList<>();
    private final Map<String, Quote> quotes = new HashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean visible = false;
    private int pending = 0;

    private EditText input;
    private TextView statusView, emptyView;
    private StockAdapter adapter;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            refreshAll();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    static class Quote {
        String name, currency, error;
        double price, prevClose;
        long time; // unix seconds of last trade
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = findViewById(R.id.input);
        statusView = findViewById(R.id.status);
        emptyView = findViewById(R.id.empty);
        ListView list = findViewById(R.id.list);
        Button addButton = findViewById(R.id.btn_add);
        Button refreshButton = findViewById(R.id.btn_refresh);

        loadTickers();
        adapter = new StockAdapter();
        list.setAdapter(adapter);

        addButton.setOnClickListener(v -> addFromInput());
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput();
                return true;
            }
            return false;
        });
        refreshButton.setOnClickListener(v -> refreshAll());
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            confirmRemove(tickers.get(pos));
            return true;
        });

        updateEmpty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        main.removeCallbacks(autoRefresh);
        main.post(autoRefresh);
    }

    @Override
    protected void onPause() {
        visible = false;
        main.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------- Watchlist ----------------

    private void addFromInput() {
        String t = input.getText().toString().trim().toUpperCase(Locale.US).replace(" ", "");
        if (t.isEmpty()) return;
        if (tickers.contains(t)) {
            Toast.makeText(this, t + " is already on your list", Toast.LENGTH_SHORT).show();
            return;
        }
        tickers.add(t);
        saveTickers();
        input.setText("");
        adapter.notifyDataSetChanged();
        updateEmpty();
        fetch(t);
    }

    private void confirmRemove(String t) {
        new AlertDialog.Builder(this)
                .setMessage("Remove " + t + "?")
                .setPositiveButton("Remove", (d, w) -> {
                    tickers.remove(t);
                    quotes.remove(t);
                    saveTickers();
                    adapter.notifyDataSetChanged();
                    updateEmpty();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadTickers() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TICKERS, "");
        tickers.clear();
        for (String s : saved.split(",")) {
            if (!s.trim().isEmpty()) tickers.add(s.trim());
        }
    }

    private void saveTickers() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(KEY_TICKERS, String.join(",", tickers));
        e.apply();
    }

    private void updateEmpty() {
        emptyView.setVisibility(tickers.isEmpty() ? View.VISIBLE : View.GONE);
        if (tickers.isEmpty()) statusView.setText("");
    }

    // ---------------- Prices ----------------

    private void refreshAll() {
        if (tickers.isEmpty()) return;
        for (String t : new ArrayList<>(tickers)) fetch(t);
    }

    private void fetch(String ticker) {
        pending++;
        statusView.setText("Updating…");
        pool.execute(() -> {
            Quote q = download(ticker);
            main.post(() -> {
                pending = Math.max(0, pending - 1);
                if (tickers.contains(ticker)) {
                    Quote old = quotes.get(ticker);
                    // Keep the last good price if a refresh fails (e.g. brief network drop)
                    if (q.error != null && old != null && old.error == null) {
                        statusView.setText("Couldn't refresh " + ticker + "; showing last price");
                    } else {
                        quotes.put(ticker, q);
                    }
                    adapter.notifyDataSetChanged();
                }
                if (pending == 0) {
                    statusView.setText("Updated " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                            + (visible ? " · auto-refresh every minute" : ""));
                }
            });
        });
    }

    private static Quote download(String ticker) {
        Quote q = new Quote();
        HttpURLConnection conn = null;
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                    + URLEncoder.encode(ticker, "UTF-8") + "?interval=1d&range=5d";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) StocksApp");
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code == 404) {
                q.error = "Ticker not found";
                return q;
            }
            if (code != 200) {
                q.error = "Server error (" + code + ")";
                return q;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            JSONObject chart = new JSONObject(sb.toString()).getJSONObject("chart");
            JSONArray results = chart.optJSONArray("result");
            if (results == null || results.length() == 0) {
                q.error = "Ticker not found";
                return q;
            }
            JSONObject result = results.getJSONObject(0);
            JSONObject meta = result.getJSONObject("meta");

            q.price = meta.optDouble("regularMarketPrice", Double.NaN);
            q.currency = meta.optString("currency", "");
            q.name = meta.has("shortName") ? meta.optString("shortName") : meta.optString("longName", "");
            q.time = meta.optLong("regularMarketTime", 0);
            q.prevClose = previousClose(result, meta);

            if (Double.isNaN(q.price)) q.error = "No price available";
        } catch (java.net.UnknownHostException e) {
            q.error = "No internet connection";
        } catch (Exception e) {
            q.error = "Couldn't load price";
        } finally {
            if (conn != null) conn.disconnect();
        }
        return q;
    }

    /**
     * Previous trading day's close. Uses the last daily close before today's bar,
     * falling back to the values Yahoo puts in the metadata.
     */
    private static double previousClose(JSONObject result, JSONObject meta) {
        try {
            JSONArray stamps = result.getJSONArray("timestamp");
            JSONArray closes = result.getJSONObject("indicators").getJSONArray("quote")
                    .getJSONObject(0).getJSONArray("close");
            long lastTrade = meta.optLong("regularMarketTime", 0);
            int gmtOffset = meta.optInt("gmtoffset", 0);
            long tradeDay = Math.floorDiv(lastTrade + gmtOffset, 86400L);
            for (int i = stamps.length() - 1; i >= 0; i--) {
                long day = Math.floorDiv(stamps.getLong(i) + gmtOffset, 86400L);
                if (day < tradeDay && !closes.isNull(i)) return closes.getDouble(i);
            }
        } catch (Exception ignored) {
        }
        double p = meta.optDouble("previousClose", Double.NaN);
        if (!Double.isNaN(p)) return p;
        return meta.optDouble("chartPreviousClose", Double.NaN);
    }

    // ---------------- List UI ----------------

    private class StockAdapter extends BaseAdapter {
        @Override public int getCount() { return tickers.size(); }
        @Override public Object getItem(int i) { return tickers.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View row, ViewGroup parent) {
            if (row == null) row = getLayoutInflater().inflate(R.layout.row_stock, parent, false);
            TextView symbol = row.findViewById(R.id.symbol);
            TextView name = row.findViewById(R.id.name);
            TextView price = row.findViewById(R.id.price);
            TextView change = row.findViewById(R.id.change);

            String t = tickers.get(pos);
            Quote q = quotes.get(t);
            symbol.setText(t);

            if (q == null) {
                name.setText("Loading…");
                price.setText("");
                change.setText("…");
                change.setBackgroundColor(0xFF9CA3AF);
                return row;
            }
            if (q.error != null) {
                name.setText(q.error);
                price.setText("");
                change.setText("—");
                change.setBackgroundColor(0xFF9CA3AF);
                return row;
            }

            String when = q.time > 0
                    ? new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(q.time * 1000))
                    : "";
            name.setText((q.name == null || q.name.isEmpty() ? "" : q.name + " · ") + when);
            price.setText(formatPrice(q.price) + " " + q.currency);

            if (Double.isNaN(q.prevClose) || q.prevClose == 0) {
                change.setText("—");
                change.setBackgroundColor(0xFF9CA3AF);
            } else {
                double diff = q.price - q.prevClose;
                double pct = diff / q.prevClose * 100;
                change.setText(String.format(Locale.US, "%+.2f  (%+.2f%%)", diff, pct));
                change.setBackgroundColor(diff > 0 ? 0xFF16A34A : diff < 0 ? 0xFFDC2626 : 0xFF6B7280);
            }
            return row;
        }
    }

    private static String formatPrice(double p) {
        if (Math.abs(p) >= 1000) return String.format(Locale.US, "%,.2f", p);
        if (Math.abs(p) >= 1) return String.format(Locale.US, "%.2f", p);
        return String.format(Locale.US, "%.4f", p);
    }
}
