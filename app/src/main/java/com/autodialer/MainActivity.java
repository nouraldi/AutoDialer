package com.autodialer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.util.Xml;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    static final int REQ_PICK = 1, REQ_PERM = 2;
    SharedPreferences sp;
    ArrayList<String[]> list = new ArrayList<>();
    int idx = 0;
    boolean running = false, inCall = false, callSeen = false, registered = false;
    String cur = "";
    Handler h = new Handler(Looper.getMainLooper());
    TextView status;
    EditText delayEt;

    Runnable next = new Runnable() { public void run() { dialNext(); } };
    Runnable watchdog = new Runnable() {
        public void run() { if (running && !callSeen) h.postDelayed(next, delayMs()); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("p", MODE_PRIVATE);
        idx = sp.getInt("idx", 0);
        loadList();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);

        Button pick = new Button(this);
        pick.setText("اختار ملف الأرقام (Excel / CSV)");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK);
        });

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, 40, 0, 40);

        TextView dl = new TextView(this);
        dl.setText("الفاصل بين المكالمات (ثواني):");
        delayEt = new EditText(this);
        delayEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        delayEt.setText(String.valueOf(sp.getInt("delay", 5)));

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> {
            if (list.isEmpty()) { toast("اختار ملف الأول"); return; }
            if (!hasPerms()) {
                requestPermissions(new String[]{Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_PHONE_STATE}, REQ_PERM);
            } else begin();
        });

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            running = false;
            h.removeCallbacks(next);
            h.removeCallbacks(watchdog);
            refresh();
        });

        root.addView(pick); root.addView(status); root.addView(dl); root.addView(delayEt);
        root.addView(start); root.addView(stop);
        setContentView(root);
        refresh();
    }

    boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int rq, String[] p, int[] g) {
        if (rq == REQ_PERM) { if (hasPerms()) begin(); else toast("لازم توافق على الأذونات"); }
    }

    void begin() {
        register();
        if (idx >= list.size()) idx = 0;
        running = true;
        dialNext();
    }

    void dialNext() {
        if (!running) return;
        if (idx >= list.size()) {
            running = false; cur = "خلصت كل الأرقام";
            refresh(); return;
        }
        String[] r = list.get(idx);
        callSeen = false; inCall = false;
        cur = r[0] + " " + r[1];
        try {
            startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", r[1], null)));
        } catch (Exception e) { toast("فشل الاتصال بـ " + r[1]); }
        idx++;
        sp.edit().putInt("idx", idx).apply();
        refresh();
        h.removeCallbacks(watchdog);
        h.postDelayed(watchdog, 30000);
    }

    void onState(int s) {
        if (s == TelephonyManager.CALL_STATE_OFFHOOK) {
            callSeen = true; inCall = true; h.removeCallbacks(watchdog);
        } else if (s == TelephonyManager.CALL_STATE_IDLE && inCall) {
            inCall = false;
            if (running) h.postDelayed(next, delayMs());
        }
    }

    int delayMs() {
        try {
            int s = Integer.parseInt(delayEt.getText().toString().trim());
            sp.edit().putInt("delay", s).apply();
            return Math.max(0, s) * 1000;
        } catch (Exception e) { return 5000; }
    }

    void register() {
        if (registered) return;
        registered = true;
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) {
            tm.registerTelephonyCallback(getMainExecutor(), new Cb31());
        } else {
            tm.listen(new PhoneStateListener() {
                @Override public void onCallStateChanged(int s, String n) { onState(s); }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    @android.annotation.TargetApi(31)
    class Cb31 extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int s) { onState(s); }
    }

    void refresh() {
        String t = list.isEmpty() ? "مفيش أرقام. اختار ملف."
            : "عدد الأرقام: " + list.size() + "\nاتصل بيهم: " + Math.min(idx, list.size())
              + "\n" + (running ? "شغال" : "واقف");
        if (!cur.isEmpty() && running) t += "\n" + cur;
        status.setText(t);
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---------- تخزين ----------
    void saveList() {
        try (FileOutputStream f = openFileOutput("list.txt", MODE_PRIVATE)) {
            StringBuilder sb = new StringBuilder();
            for (String[] r : list) sb.append(r[0].replace('\t', ' ')).append('\t').append(r[1]).append('\n');
            f.write(sb.toString().getBytes("UTF-8"));
        } catch (Exception e) { }
    }

    void loadList() {
        list.clear();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(openFileInput("list.txt"), "UTF-8"))) {
            String l;
            while ((l = br.readLine()) != null) {
                String[] p = l.split("\t", 2);
                if (p.length == 2) list.add(p);
            }
        } catch (Exception e) { }
    }

    // ---------- قراءة الملف ----------
    @Override protected void onActivityResult(int rq, int rs, Intent d) {
        if (rq == REQ_PICK && rs == RESULT_OK && d != null && d.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(d.getData());
                byte[] b = readAll(in);
                in.close();
                if (b.length > 1 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF) {
                    toast("الملف xls قديم. احفظه xlsx أو csv"); return;
                }
                list.clear();
                if (b.length > 1 && b[0] == 'P' && b[1] == 'K') parseXlsx(b);
                else parseCsv(new String(b, "UTF-8"));
                idx = 0;
                running = false;
                saveList();
                sp.edit().putInt("idx", 0).apply();
                toast("تم قراءة " + list.size() + " رقم");
            } catch (Exception e) { toast("مقدرتش أقرأ الملف"); }
            refresh();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        return o.toByteArray();
    }

    void addRow(List<String> cells) {
        String phone = null, name = "";
        for (String c : cells) {
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (phone == null && t.matches("[+0-9 ()\\-]{7,}") && !t.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String d = t.replaceAll("[^+0-9]", "");
                if (d.replaceAll("\\D", "").length() >= 7) { phone = d; continue; }
            }
            if (name.isEmpty()) name = t;
        }
        if (phone != null) {
            if (phone.matches("1[0-9]{9}")) phone = "0" + phone; // الصفر اللي إكسل بيشيله
            list.add(new String[]{name, phone});
        }
    }

    void parseCsv(String txt) {
        if (txt.startsWith("\uFEFF")) txt = txt.substring(1);
        for (String line : txt.split("\\r?\\n")) {
            ArrayList<String> cells = new ArrayList<>();
            for (String c : line.replace("\"", "").split("[,;\\t]")) cells.add(c);
            addRow(cells);
        }
    }

    void parseXlsx(byte[] b) throws Exception {
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b));
        ZipEntry e;
        byte[] shared = null, sheet = null, other = null;
        while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            if (n.equals("xl/sharedStrings.xml")) shared = readAll(z);
            else if (n.equals("xl/worksheets/sheet1.xml")) sheet = readAll(z);
            else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml") && other == null) other = readAll(z);
        }
        if (sheet == null) sheet = other;
        if (sheet == null) throw new Exception("no sheet");

        ArrayList<String> ss = new ArrayList<>();
        if (shared != null) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new ByteArrayInputStream(shared), "UTF-8");
            StringBuilder sb = null;
            boolean inT = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String t = p.getName();
                    if (t.equals("si")) sb = new StringBuilder();
                    else if (t.equals("t")) inT = true;
                } else if (ev == XmlPullParser.TEXT && inT && sb != null) {
                    sb.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String t = p.getName();
                    if (t.equals("t")) inT = false;
                    else if (t.equals("si")) { ss.add(sb == null ? "" : sb.toString()); sb = null; }
                }
            }
        }

        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(sheet), "UTF-8");
        ArrayList<String> row = null;
        String type = null;
        int col = 0;
        boolean inV = false, inT = false;
        StringBuilder val = new StringBuilder();
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String t = p.getName();
                if (t.equals("row")) row = new ArrayList<>();
                else if (t.equals("c")) {
                    type = p.getAttributeValue(null, "t");
                    col = colIndex(p.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                    val.setLength(0);
                } else if (t.equals("v")) inV = true;
                else if (t.equals("t")) inT = true;
            } else if (ev == XmlPullParser.TEXT) {
                if (inV || inT) val.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String t = p.getName();
                if (t.equals("v")) inV = false;
                else if (t.equals("t")) inT = false;
                else if (t.equals("c") && row != null) {
                    String s = val.toString();
                    if ("s".equals(type)) {
                        try { s = ss.get(Integer.parseInt(s.trim())); } catch (Exception ex) { s = ""; }
                    } else if ((type == null || "n".equals(type)) && s.toUpperCase().contains("E")) {
                        try { s = new BigDecimal(s).toPlainString(); } catch (Exception ex) { }
                    }
                    while (row.size() < col) row.add("");
                    row.add(s);
                } else if (t.equals("row") && row != null) {
                    addRow(row);
                    row = null;
                }
            }
        }
    }

    static int colIndex(String ref, int fallback) {
        if (ref == null) return fallback;
        int n = 0;
        for (char ch : ref.toCharArray()) {
            if (ch >= 'A' && ch <= 'Z') n = n * 26 + (ch - 'A' + 1); else break;
        }
        return n == 0 ? fallback : n - 1;
    }
}
