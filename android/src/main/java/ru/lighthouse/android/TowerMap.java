package ru.lighthouse.android;

import android.app.*;
import android.content.Context;
import android.net.Uri;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import ru.lighthouse.core.NetworkCheckResult;

/** The map uses matched database coordinates, never device coordinates as tower positions. */
final class TowerMap {
    private static final Map<String,JSONObject> cache = new ConcurrentHashMap<>();
    static void clearCache() { cache.clear(); }
    static void show(Activity activity, List<NetworkCheckResult> observations) {
        String key = activity.getSharedPreferences("tower-map",Context.MODE_PRIVATE).getString("key","");
        if (key.isEmpty()) { new AlertDialog.Builder(activity).setTitle(UiLanguage.text("Карта сот")).setMessage(UiLanguage.text("Укажите личный OpenCellID API key в Settings. Без ответа базы координаты сот неизвестны.")).setPositiveButton(UiLanguage.text("Закрыть"),null).show(); return; }
        TextView status = new TextView(activity); status.setPadding(24,24,24,24); status.setText(UiLanguage.text("Поиск наблюдаемых сот в OpenCellID…"));
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL); content.addView(status);
        WebView web = new WebView(activity); web.setVisibility(android.view.View.GONE); content.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(UiLanguage.text("Карта сот • OpenCellID")).setView(content).setPositiveButton(UiLanguage.text("Закрыть"),null).create();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        dialog.setOnDismissListener(d -> { worker.shutdownNow(); web.stopLoading(); web.destroy(); }); dialog.show();
        dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.85));
        worker.execute(() -> {
            JSONArray points = new JSONArray(); int missing = 0; Set<String> requested = new HashSet<>();
            int[] failures = new int[5]; // invalid key, limit, missing cell, mismatch, network/server
            for (NetworkCheckResult observation : observations) {
                if (Thread.currentThread().isInterrupted()) return;
                Map<String,String> m = observation.metrics;
                if (!m.containsKey("cellLookupKey") || RadioPage.age(m)<0 || RadioPage.age(m)>120_000) continue;
                String radio = switch (m.getOrDefault("technology","")) { case "5G NR" -> "NR"; case "WCDMA" -> "UMTS"; case "GSM" -> "GSM"; case "LTE" -> "LTE"; default -> ""; };
                String identity = radio+":"+m.get("cellLookupKey");
                if (radio.isEmpty() || !requested.add(identity)) continue;
                try {
                    JSONObject position = cache.get(identity);
                    if (position == null) {
                        Uri url = Uri.parse("https://opencellid.org/cell/get").buildUpon().appendQueryParameter("key",key)
                            .appendQueryParameter("mcc",m.get("mcc")).appendQueryParameter("mnc",m.get("mnc"))
                            .appendQueryParameter("lac",RadioCsv.value(m,"tac","lac")).appendQueryParameter("cellid",m.get("cellId"))
                            .appendQueryParameter("radio",radio).appendQueryParameter("format","json").build();
                        HttpURLConnection connection = (HttpURLConnection)new URL(url.toString()).openConnection();
                        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
                        try {
                            int responseCode=connection.getResponseCode();
                            if (responseCode==401) throw new java.io.IOException("invalid_key");
                            if (responseCode==429) throw new java.io.IOException("daily_limit");
                            if (responseCode!=200) throw new java.io.IOException("network_or_server");
                            try (java.io.InputStream input = connection.getInputStream()) {
                                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read;
                                while ((read=input.read(buffer))!=-1) { if(bytes.size()+read>65536)throw new java.io.IOException("Response limit"); bytes.write(buffer,0,read); }
                                position = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                            }
                            int code=position.optInt("code",-1);
                            if (code==1) throw new java.io.IOException("cell_not_found");
                            if (code==2) throw new java.io.IOException("invalid_key");
                            if (code==7) throw new java.io.IOException("daily_limit");
                            if (position.has("error")) throw new java.io.IOException("network_or_server");
                            if (!matches(position,m,radio)) throw new java.io.IOException("mismatch");
                            cache.put(identity,position);
                        } finally { connection.disconnect(); }
                    }
                    JSONObject point = new JSONObject(); point.put("lat",position.getDouble("lat")); point.put("lon",position.getDouble("lon"));
                    long signal = RadioPage.signal(observation);
                    point.put("color",signal == Long.MIN_VALUE ? "#87929e" : signal >= -85 ? "#38a878" : signal >= -105 ? "#d79532" : "#cf5665");
                    point.put("connected","true".equals(m.get("registered")));
                    point.put("label",radio+" "+m.get("cellLookupKey")+" | "+(signal==Long.MIN_VALUE?UiLanguage.text("dBm неизвестен"):signal+" dBm")+UiLanguage.text(" | оценка OpenCellID")); points.put(point);
                } catch (Exception error) {
                    missing++;
                    String reason=error.getMessage();
                    if("invalid_key".equals(reason))failures[0]++;
                    else if("daily_limit".equals(reason))failures[1]++;
                    else if("cell_not_found".equals(reason))failures[2]++;
                    else if("mismatch".equals(reason))failures[3]++;
                    else failures[4]++;
                }
            }
            int unavailable = missing;
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isFinishing()) return;
                String reason=failures[0]>0?"OpenCellID rejected the API key (HTTP 401). Check API Access Tokens in your account."
                    :failures[1]>0?"OpenCellID daily request limit reached (HTTP 429)."
                    :requested.isEmpty()?"No recent cells with complete MCC, MNC, LAC/TAC and Cell ID were reported by Android."
                    :failures[4]>0?"Some requests failed due to network or server errors."
                    :failures[3]>0?"OpenCellID returned records that did not match measured cell identifiers."
                    :failures[2]>0?"OpenCellID has no record for some measured cells; the API key may still be valid.":"";
                status.setText(UiLanguage.text("Найдено: ")+points.length()+UiLanguage.text(" • нет подтверждённых координат: ")+unavailable+"\n"+reason+"\n"+UiLanguage.text("Позиции из базы приблизительные; сигнал измерен телефоном. Зелёный контур — зарегистрированная сота."));
                if(points.length()==0)return;
                web.setVisibility(android.view.View.VISIBLE); web.getSettings().setJavaScriptEnabled(true);
                web.getSettings().setAllowFileAccess(false); web.getSettings().setAllowContentAccess(false); web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                web.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { if(request.isForMainFrame()) status.setText(UiLanguage.text("Карта недоступна: ошибка загрузки")); }
                });
                String json = points.toString().replace("<","\\u003c");
                String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width, initial-scale=1'><link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'><style>html,body,#map{height:100%;margin:0}#error{position:absolute;top:0;z-index:9999;background:white;color:#222}</style></head><body><div id='map'></div><div id='error'></div><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><script>try{const map=L.map('map');L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors | OpenCelliD, CC BY-SA 4.0'}).on('tileerror',()=>document.getElementById('error').textContent="+JSONObject.quote(UiLanguage.text("Не удалось загрузить часть карты"))+ ").addTo(map);const points="+json+";const bounds=[];points.forEach(p=>{let text=document.createElement('span');text.textContent=p.label;L.circleMarker([p.lat,p.lon],{radius:9,color:p.connected?'#16cf69':p.color,weight:p.connected?4:1,fillColor:p.color,fillOpacity:.85}).addTo(map).bindPopup(text);bounds.push([p.lat,p.lon]);});map.fitBounds(bounds,{maxZoom:15,padding:[24,24]});}catch(e){document.getElementById('error').textContent="+JSONObject.quote(UiLanguage.text("Карта недоступна: проверьте интернет"))+";}</script></body></html>";
                web.loadDataWithBaseURL("https://lighthouse.invalid/",html,"text/html","UTF-8",null);
            }); worker.shutdown();
        });
    }
    static boolean matches(JSONObject p,Map<String,String> m,String radio) throws JSONException {
        double lat=p.getDouble("lat"),lon=p.getDouble("lon");
        return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180
            && p.getLong("mcc")==Long.parseLong(m.get("mcc")) && p.getLong("mnc")==Long.parseLong(m.get("mnc"))
            && p.getLong("lac")==Long.parseLong(RadioCsv.value(m,"tac","lac")) && p.getLong("cellid")==Long.parseLong(m.get("cellId"))
            && radio.equalsIgnoreCase(p.getString("radio"));
    }
}
