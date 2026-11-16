package br.controlenotebook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Liga (Wake-on-LAN), desliga e apaga/acende a tela do notebook. Fala com o servidor controle-notebook (5097). */
public class MainActivity extends Activity {
    WebView web;
    SharedPreferences prefs;
    String token, mac, broadcast;
    List<String> hosts = new ArrayList<String>();
    List<String> box = new ArrayList<String>();   // TV box de casa: liga o notebook de fora (WoL não passa pelo Tailscale)
    volatile String hostAtual;

    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        prefs = getSharedPreferences("controle", MODE_PRIVATE);
        try {
            JSONObject c = new JSONObject(ler(getAssets().open("config.json")));
            token = c.getString("token");
            mac = c.getString("mac");
            broadcast = c.getString("broadcast");
            JSONArray h = c.getJSONArray("hosts");
            for (int i = 0; i < h.length(); i++) hosts.add(h.getString(i));
            JSONArray b = c.optJSONArray("box");
            if (b != null) for (int i = 0; i < b.length(); i++) box.add(b.getString(i));
        } catch (Exception e) {
            token = "";
        }
        getWindow().setStatusBarColor(Color.parseColor("#0f1115"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f1115"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1115"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Ponte(), "App");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    static String ler(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    List<String> candidatos() {
        List<String> l = new ArrayList<String>();
        String s = prefs.getString("host", "");
        if (!s.isEmpty()) l.add(s);
        if (hostAtual != null && !l.contains(hostAtual)) l.add(hostAtual);
        for (String h : hosts) if (!l.contains(h)) l.add(h);
        return l;
    }

    String http(String host, String metodo, String caminho, String corpo, int tempo) throws Exception {
        return http(host, 5097, metodo, caminho, corpo, tempo);
    }

    String http(String host, int porta, String metodo, String caminho, String corpo, int tempo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + porta + caminho).openConnection();
        c.setConnectTimeout(tempo);
        c.setReadTimeout(tempo + 4000);
        c.setRequestMethod(metodo);
        c.setRequestProperty("X-Token", token);
        if (corpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            o.write(corpo.getBytes("UTF-8"));
            o.close();
        }
        int codigo = c.getResponseCode();
        String r = ler(codigo < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (codigo == 401) throw new Exception("código do app recusado — reinstale o APK mais novo");
        return r;
    }

    void js(final String funcao, final String... args) {
        final StringBuilder sb = new StringBuilder(funcao).append('(');
        for (int i = 0; i < args.length; i++) sb.append(i > 0 ? "," : "").append(JSONObject.quote(args[i]));
        sb.append(')');
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(sb.toString(), null); } });
    }

    void wol() throws Exception {
        String[] p = mac.split("[:-]");
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(p[i], 16);
        byte[] pacote = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pacote[i] = (byte) 0xff;
        for (int i = 6; i < pacote.length; i += 6) System.arraycopy(m, 0, pacote, i, 6);
        DatagramSocket s = new DatagramSocket();
        s.setBroadcast(true);
        String[] destinos = {"255.255.255.255", broadcast};
        int[] portas = {9, 7};
        for (int vez = 0; vez < 3; vez++) {
            for (String d : destinos)
                for (int porta : portas)
                    s.send(new DatagramPacket(pacote, pacote.length, InetAddress.getByName(d), porta));
            Thread.sleep(250);
        }
        s.close();
    }

    class Ponte {
        @JavascriptInterface
        public void estado() {
            new Thread(new Runnable() {
                public void run() {
                    String erro = "sem resposta";
                    for (String h : candidatos()) {
                        try {
                            JSONObject o = new JSONObject(http(h, "GET", "/estado", null, 2500));
                            hostAtual = h;
                            o.put("host", h);
                            js("aoEstado", o.toString());
                            return;
                        } catch (Exception e) {
                            erro = String.valueOf(e.getMessage());
                            if (erro.startsWith("código")) break;
                        }
                    }
                    try {
                        js("aoEstado", new JSONObject().put("online", false).put("erro", erro).toString());
                    } catch (Exception ignorado) { }
                }
            }).start();
        }

        @JavascriptInterface
        public void acao(final String caminho, final String corpo) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        String h = hostAtual != null ? hostAtual : candidatos().get(0);
                        js("aoResposta", caminho, http(h, "POST", caminho, corpo, 4000));
                    } catch (Exception e) {
                        try {
                            js("aoResposta", caminho, new JSONObject().put("ok", false).put("erro", String.valueOf(e.getMessage())).toString());
                        } catch (Exception ignorado) { }
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void ligar() {
            new Thread(new Runnable() {
                public void run() {
                    // 1) pacote direto (funciona no Wi-Fi de casa); 2) pede para a TV box mandar (casa ou Tailscale)
                    String erro = "";
                    boolean ok = false;
                    try { wol(); ok = true; } catch (Exception e) { erro = String.valueOf(e.getMessage()); }
                    String pelaBox = "";
                    for (String b : box) {
                        try {
                            JSONObject o = new JSONObject(http(b, 8791, "POST", "/ligar", "{}", 3000));
                            if (o.optBoolean("ok")) { pelaBox = b; break; }
                        } catch (Exception e) { erro = String.valueOf(e.getMessage()); }
                    }
                    if (!pelaBox.isEmpty()) js("aoLigar", "ok", "box");
                    else if (ok) js("aoLigar", "ok", "direto");
                    else js("aoLigar", "erro: " + erro, "");
                }
            }).start();
        }

        @JavascriptInterface
        public String host() {
            String s = prefs.getString("host", "");
            return s.isEmpty() ? (hostAtual != null ? hostAtual : hosts.isEmpty() ? "" : hosts.get(0)) : s;
        }

        @JavascriptInterface
        public void definirHost(String h) {
            prefs.edit().putString("host", h.trim().replaceFirst("^https?://", "").replaceFirst("[:/].*$", "")).apply();
            hostAtual = null;
        }

        @JavascriptInterface
        public String mac() {
            return mac;
        }

        @JavascriptInterface
        public void vibrar() {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignorado) { }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.evaluateJavascript("window.aoVoltar && aoVoltar()", null);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }
}
