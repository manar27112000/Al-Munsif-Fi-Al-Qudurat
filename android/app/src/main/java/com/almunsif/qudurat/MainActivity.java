package com.almunsif.qudurat;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** المنصف في القدرات — تطبيق بيسحب رسائل تليجرام ويطلّعها PowerPoint من الموبايل نفسه. */
public class MainActivity extends Activity {

    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final int REQ_SAVE = 4411;

    private static final int GREEN = Color.parseColor("#0B7A5E");
    private static final int GREEN_DARK = Color.parseColor("#075A45");
    private static final int BG = Color.parseColor("#F3F6F5");
    private static final int INK = Color.parseColor("#1F2933");
    private static final int MUTED = Color.parseColor("#5B6770");
    private static final int RED = Color.parseColor("#B3261E");

    private final ExecutorService pyExec = Executors.newSingleThreadExecutor();
    private final ExecutorService progressExec = Executors.newSingleThreadExecutor();
    private final ExecutorService fastExec = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private SharedPreferences prefs;
    private PyObject bridge;
    private Runnable backAction = null;

    // حالة الشاشة الرئيسية
    private final List<JSONObject> allChats = new ArrayList<>();
    private final List<JSONObject> shownChats = new ArrayList<>();
    private JSONObject selectedChat;
    private Calendar fromDay;
    private Calendar toDay;
    private File lastResult;

    // عناصر شاشة التصدير
    private TextView progressText;
    private ProgressBar progressBar;
    private Button createBtn;
    private Button cancelBtn;
    private LinearLayout resultBox;
    private boolean exporting = false;
    private boolean pollingProgress = false;

    // ------------------------------------------------------------------ دورة الحياة

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("munsif", MODE_PRIVATE);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(root);

        fromDay = Calendar.getInstance();
        fromDay.add(Calendar.DAY_OF_YEAR, -7);
        toDay = Calendar.getInstance();

        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            bridge = Python.getInstance().getModule("bridge");
        } catch (Throwable t) {
            showFatal("تعذر تشغيل محرك التحويل: " + t);
            return;
        }

        if (prefs.getString("api_id", "").isEmpty() || prefs.getString("api_hash", "").isEmpty()) {
            showSetup();
        } else {
            connectAndRoute();
        }
    }

    @Override
    public void onBackPressed() {
        if (exporting) {
            Toast.makeText(this, "في تصدير شغال. اضغطي إلغاء الأول.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (backAction != null) {
            Runnable r = backAction;
            backAction = null;
            r.run();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollingProgress = false;
    }

    // ------------------------------------------------------------------ مساعدات UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setScreen(View v) {
        root.removeAllViews();
        root.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(24), dp(18), dp(24));
        return l;
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(child, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText edit(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextSize(17);
        e.setTextColor(INK);
        e.setHintTextColor(Color.parseColor("#8A94A0"));
        e.setSingleLine(true);
        e.setGravity(Gravity.START);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#C9D2CF"));
        e.setBackground(bg);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : GREEN_DARK);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(GREEN);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), GREEN);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void showFatal(String msg) {
        LinearLayout c = column();
        c.addView(text("المنصف في القدرات", 24, GREEN_DARK, true));
        c.addView(text(msg, 15, RED, false));
        setScreen(scroll(c));
    }

    private void busy(String msg) {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(this);
        c.addView(pb);
        TextView t = text(msg, 16, INK, false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        setScreen(c);
        backAction = null;
    }

    private String errText(String code, String detail) {
        if (code == null) code = "unknown";
        if (code.startsWith("flood:")) {
            return "تليجرام طلب الانتظار " + code.substring(6) + " ثانية قبل المحاولة تاني.";
        }
        switch (code) {
            case "bad_credentials":
                return "الـ api_id أو الـ api_hash غلط. راجعيهم من موقع my.telegram.org.";
            case "invalid_phone":
                return "رقم التليفون غير صحيح. اكتبيه بصيغة دولية، مثال: +201012345678";
            case "code_invalid":
                return "الكود غلط. راجعيه من محادثة Telegram جوه تطبيق تليجرام.";
            case "code_expired":
                return "الكود انتهى. ارجعي واطلبي كود جديد.";
            case "password_invalid":
                return "كلمة سر التحقق بخطوتين غلط.";
            case "network":
                return "مشكلة في الإنترنت أو تليجرام مش متاح دلوقتي. جربي تاني.";
            case "empty":
                return "مفيش رسائل مناسبة في الفترة دي (أو كلها اتشالت كأختام/مكررة).";
            case "cancelled":
                return "تم الإلغاء.";
            default:
                return "حصل خطأ غير متوقع (" + code + "). " + (detail == null ? "" : detail);
        }
    }

    // ------------------------------------------------------------------ استدعاء بايثون

    private interface Done {
        void run(JSONObject r);
    }

    private void callPython(final String fn, final Done done, final Object... args) {
        pyExec.execute(() -> {
            JSONObject r;
            try {
                String s = bridge.callAttr(fn, args).toString();
                r = new JSONObject(s);
            } catch (Throwable t) {
                r = new JSONObject();
                try {
                    r.put("ok", false);
                    r.put("error", "unknown");
                    r.put("detail", String.valueOf(t));
                } catch (Exception ignored) {
                }
            }
            final JSONObject rr = r;
            ui.post(() -> done.run(rr));
        });
    }

    // ------------------------------------------------------------------ شاشة 1: المفاتيح

    private void showSetup() {
        backAction = null;
        LinearLayout c = column();
        c.addView(text("المنصف في القدرات", 26, GREEN_DARK, true));
        c.addView(text("خطوة واحدة بتتعمل مرة واحدة بس: مفتاحين مجانيين من تليجرام نفسه عشان التطبيق يقدر يقرا رسايل حسابك.", 15, MUTED, false));
        c.addView(text("1) اضغطي الزرار ده وسجّلي دخول بحسابك (الكود هيوصلك جوه تطبيق تليجرام).\n"
                + "2) اختاري API development tools وعبّي أي اسم للتطبيق (مثلًا Munsif) واضغطي Create.\n"
                + "3) انسخي App api_id و App api_hash وارجعي هنا والصقيهم.", 15, INK, false));

        Button open = button("افتحي موقع المفاتيح (my.telegram.org)", false);
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps")));
            } catch (ActivityNotFoundException e) {
                toast("افتحي المتصفح واكتبي: my.telegram.org/apps");
            }
        });
        c.addView(open);

        final EditText id = edit("api_id (أرقام)", InputType.TYPE_CLASS_NUMBER);
        final EditText hash = edit("api_hash", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        c.addView(id);
        c.addView(hash);

        Button next = button("متابعة", true);
        next.setOnClickListener(v -> {
            String a = id.getText().toString().trim();
            String h = hash.getText().toString().trim();
            if (a.isEmpty() || h.length() < 20) {
                toast("اكتبي api_id و api_hash كاملين.");
                return;
            }
            prefs.edit().putString("api_id", a).putString("api_hash", h).apply();
            connectAndRoute();
        });
        c.addView(next);
        c.addView(text("المفتاحين بيتحفظوا على موبايلك بس، ومش بيتبعتوا لأي حد.", 13, MUTED, false));
        setScreen(scroll(c));
    }

    private void connectAndRoute() {
        busy("بتتصل بتليجرام…");
        File sessionDir = new File(getFilesDir(), "session");
        callPython("init", r -> {
            if (!r.optBoolean("ok")) {
                showError(errText(r.optString("error"), r.optString("detail")), true);
                return;
            }
            if (r.optBoolean("authorized")) {
                loadChats();
            } else {
                showPhone();
            }
        }, prefs.getString("api_id", ""), prefs.getString("api_hash", ""), sessionDir.getAbsolutePath());
    }

    private void showError(String msg, final boolean offerReset) {
        backAction = null;
        LinearLayout c = column();
        c.addView(text("المنصف في القدرات", 24, GREEN_DARK, true));
        c.addView(text(msg, 16, RED, false));
        Button retry = button("حاولي تاني", true);
        retry.setOnClickListener(v -> connectAndRoute());
        c.addView(retry);
        if (offerReset) {
            Button reset = button("تغيير المفاتيح (api_id / api_hash)", false);
            reset.setOnClickListener(v -> {
                prefs.edit().remove("api_id").remove("api_hash").apply();
                showSetup();
            });
            c.addView(reset);
        }
        setScreen(scroll(c));
    }

    // ------------------------------------------------------------------ شاشة 2: الرقم والكود

    private void showPhone() {
        backAction = null;
        LinearLayout c = column();
        c.addView(text("تسجيل الدخول", 24, GREEN_DARK, true));
        c.addView(text("اكتبي رقم تليفونك اللي عليه حساب تليجرام بالصيغة الدولية. الكود هيوصلك جوه تطبيق تليجرام نفسه (محادثة اسمها Telegram) أو برسالة SMS.", 15, MUTED, false));
        final EditText phone = edit("+201012345678", InputType.TYPE_CLASS_PHONE);
        c.addView(phone);
        Button send = button("ابعتي الكود", true);
        send.setOnClickListener(v -> {
            String p = phone.getText().toString().trim().replace(" ", "");
            if (p.length() < 8) {
                toast("اكتبي الرقم كامل مع كود الدولة.");
                return;
            }
            busy("بيتبعت الكود…");
            callPython("send_code", r -> {
                if (r.optBoolean("ok")) {
                    showCode();
                } else {
                    toast(errText(r.optString("error"), r.optString("detail")));
                    showPhone();
                }
            }, p);
        });
        c.addView(send);
        Button change = button("تغيير المفاتيح (api_id / api_hash)", false);
        change.setOnClickListener(v -> showSetup());
        c.addView(change);
        setScreen(scroll(c));
    }

    private void showCode() {
        backAction = this::showPhone;
        LinearLayout c = column();
        c.addView(text("اكتبي الكود", 24, GREEN_DARK, true));
        c.addView(text("افتحي تطبيق تليجرام وهتلاقي محادثة اسمها Telegram فيها كود من 5 أرقام. اكتبيه هنا.", 15, MUTED, false));
        final EditText code = edit("الكود", InputType.TYPE_CLASS_NUMBER);
        c.addView(code);
        Button go = button("دخول", true);
        go.setOnClickListener(v -> {
            String s = code.getText().toString().trim();
            if (s.length() < 4) {
                toast("اكتبي الكود كامل.");
                return;
            }
            busy("بيتسجل الدخول…");
            callPython("sign_in_code", r -> {
                if (!r.optBoolean("ok")) {
                    toast(errText(r.optString("error"), r.optString("detail")));
                    showCode();
                } else if ("need_password".equals(r.optString("status"))) {
                    showPassword();
                } else {
                    loadChats();
                }
            }, s);
        });
        c.addView(go);
        setScreen(scroll(c));
    }

    private void showPassword() {
        backAction = null;
        LinearLayout c = column();
        c.addView(text("التحقق بخطوتين", 24, GREEN_DARK, true));
        c.addView(text("حسابك عليه كلمة سر إضافية. اكتبيها (هي نفس اللي بتكتبيها لما تدخلي من جهاز جديد).", 15, MUTED, false));
        final EditText pw = edit("كلمة السر", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(pw);
        Button go = button("دخول", true);
        go.setOnClickListener(v -> {
            String s = pw.getText().toString();
            if (s.isEmpty()) return;
            busy("بيتسجل الدخول…");
            callPython("sign_in_password", r -> {
                if (r.optBoolean("ok")) {
                    loadChats();
                } else {
                    toast(errText(r.optString("error"), r.optString("detail")));
                    showPassword();
                }
            }, s);
        });
        c.addView(go);
        setScreen(scroll(c));
    }

    // ------------------------------------------------------------------ شاشة 3: اختيار المحادثة

    private void loadChats() {
        busy("بيتحمّل قايمة المحادثات…");
        callPython("list_chats", r -> {
            if (!r.optBoolean("ok")) {
                showError(errText(r.optString("error"), r.optString("detail")), false);
                return;
            }
            allChats.clear();
            JSONArray arr = r.optJSONArray("chats");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    allChats.add(arr.optJSONObject(i));
                }
            }
            showChatList();
        });
    }

    private void showChatList() {
        backAction = null;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(18), dp(14), dp(8));

        c.addView(text("اختاري الجروب", 22, GREEN_DARK, true));
        final EditText search = edit("ابحثي بالاسم…", InputType.TYPE_CLASS_TEXT);
        c.addView(search);
        final CheckBox withUsers = new CheckBox(this);
        withUsers.setText("اعرضي المحادثات الخاصة كمان");
        withUsers.setTextColor(MUTED);
        c.addView(withUsers);

        final ListView list = new ListView(this);
        list.setDividerHeight(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        c.addView(list, lp);

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);

        final Runnable refresh = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shownChats.clear();
            adapter.clear();
            for (JSONObject ch : allChats) {
                String kind = ch.optString("kind");
                if ("user".equals(kind) && !withUsers.isChecked()) continue;
                String name = ch.optString("name");
                if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
                shownChats.add(ch);
                String icon = "group".equals(kind) ? "👥 " : ("channel".equals(kind) ? "📢 " : "👤 ");
                adapter.add(icon + name);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c2) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c2) {
            }

            public void afterTextChanged(Editable s) {
                refresh.run();
            }
        });
        withUsers.setOnCheckedChangeListener((b, checked) -> refresh.run());
        list.setOnItemClickListener((AdapterView<?> parent, View view, int pos, long id) -> {
            if (pos >= 0 && pos < shownChats.size()) {
                selectedChat = shownChats.get(pos);
                showOptions();
            }
        });
        refresh.run();

        Button out = button("تسجيل خروج", false);
        out.setOnClickListener(v -> {
            busy("بيتسجل الخروج…");
            callPython("logout", r -> showPhone());
        });
        c.addView(out);
        setScreen(c);
    }

    // ------------------------------------------------------------------ شاشة 4: الخيارات والتصدير

    private String fmt(Calendar cal) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private void showOptions() {
        backAction = this::showChatList;
        exporting = false;
        LinearLayout c = column();
        c.addView(text(selectedChat.optString("name"), 22, GREEN_DARK, true));

        c.addView(text("من تاريخ", 14, MUTED, false));
        final Button fromBtn = button(fmt(fromDay), false);
        c.addView(fromBtn);
        c.addView(text("إلى تاريخ (شامل اليوم ده)", 14, MUTED, false));
        final Button toBtn = button(fmt(toDay), false);
        c.addView(toBtn);

        fromBtn.setOnClickListener(v -> pickDate(fromDay, fromBtn));
        toBtn.setOnClickListener(v -> pickDate(toDay, toBtn));

        c.addView(text("الفجوة بالثواني (الرسايل المتتالية من نفس الشخص خلال المدة دي بتتجمّع في شريحة واحدة)", 14, MUTED, false));
        final EditText gap = edit("60", InputType.TYPE_CLASS_NUMBER);
        gap.setText(String.valueOf(prefs.getInt("gap", 60)));
        c.addView(gap);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int[] vals = {10, 30, 60, 120};
        for (final int v : vals) {
            Button b = new Button(this);
            b.setText(String.valueOf(v));
            b.setAllCaps(false);
            b.setOnClickListener(x -> gap.setText(String.valueOf(v)));
            chips.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        c.addView(chips);

        createBtn = button("إنشاء العرض", true);
        c.addView(createBtn);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        c.addView(progressBar);
        progressText = text("", 15, INK, false);
        c.addView(progressText);
        cancelBtn = button("إلغاء", false);
        cancelBtn.setVisibility(View.GONE);
        c.addView(cancelBtn);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(resultBox);

        createBtn.setOnClickListener(v -> {
            int g;
            try {
                g = Integer.parseInt(gap.getText().toString().trim());
            } catch (NumberFormatException e) {
                g = -1;
            }
            if (g < 0 || g > 86400) {
                toast("الفجوة لازم تكون رقم بالثواني (مثلًا 60).");
                return;
            }
            if (toDay.before(fromDay) && !fmt(toDay).equals(fmt(fromDay))) {
                toast("تاريخ النهاية قبل تاريخ البداية.");
                return;
            }
            prefs.edit().putInt("gap", g).apply();
            startExport(g);
        });
        cancelBtn.setOnClickListener(v -> {
            cancelBtn.setEnabled(false);
            progressText.setText("بيتلغي…");
            // لازم thread منفصل: التصدير شغال على pyExec
            fastExec.execute(() -> {
                try {
                    bridge.callAttr("cancel");
                } catch (Throwable ignored) {
                }
            });
        });
        setScreen(scroll(c));
    }

    private void pickDate(final Calendar cal, final Button target) {
        new DatePickerDialog(this, (view, y, m, d) -> {
            cal.set(Calendar.YEAR, y);
            cal.set(Calendar.MONTH, m);
            cal.set(Calendar.DAY_OF_MONTH, d);
            target.setText(fmt(cal));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void startExport(int gapSeconds) {
        Calendar f = (Calendar) fromDay.clone();
        f.set(Calendar.HOUR_OF_DAY, 0);
        f.set(Calendar.MINUTE, 0);
        f.set(Calendar.SECOND, 0);
        f.set(Calendar.MILLISECOND, 0);
        Calendar t = (Calendar) toDay.clone();
        t.set(Calendar.HOUR_OF_DAY, 0);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        t.add(Calendar.DAY_OF_YEAR, 1);

        long fromTs = f.getTimeInMillis() / 1000L;
        long toTs = t.getTimeInMillis() / 1000L;
        String stem = selectedChat.optString("name") + "_" + fmt(fromDay) + "_" + fmt(toDay);
        File outDir = new File(getFilesDir(), "exports");
        long chatId = selectedChat.optLong("id");
        String chatName = selectedChat.optString("name");

        exporting = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createBtn.setEnabled(false);
        cancelBtn.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
        progressText.setTextColor(INK);
        progressText.setText("بيبدأ…  سيبي التطبيق مفتوح لحد ما يخلص.");
        resultBox.removeAllViews();
        startPolling();

        callPython("export", r -> {
            exporting = false;
            pollingProgress = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            createBtn.setEnabled(true);
            cancelBtn.setVisibility(View.GONE);
            cancelBtn.setEnabled(true);
            progressBar.setVisibility(View.GONE);
            if (!r.optBoolean("ok")) {
                progressText.setTextColor(RED);
                progressText.setText(errText(r.optString("error"), r.optString("detail")));
                return;
            }
            lastResult = new File(r.optString("path"));
            progressText.setTextColor(GREEN_DARK);
            StringBuilder sb = new StringBuilder("تم ✅  عدد الشرائح: " + r.optInt("slides"));
            if (r.optInt("removed") > 0) sb.append("\nاتشال ").append(r.optInt("removed")).append(" مكرر");
            if (r.optInt("skipped") > 0) sb.append("\nصور اتخطّت (صيغة مش مدعومة): ").append(r.optInt("skipped"));
            progressText.setText(sb.toString());
            showResultButtons();
        }, chatId, chatName, fromTs, toTs, gapSeconds, outDir.getAbsolutePath(), stem);
    }

    private void startPolling() {
        pollingProgress = true;
        progressExec.execute(new Runnable() {
            @Override
            public void run() {
                while (pollingProgress) {
                    try {
                        String s = bridge.callAttr("progress").toString();
                        final JSONObject o = new JSONObject(s);
                        ui.post(() -> showProgress(o));
                        Thread.sleep(700);
                    } catch (Throwable t) {
                        return;
                    }
                }
            }
        });
    }

    private void showProgress(JSONObject o) {
        if (!exporting || progressText == null) return;
        String phase = o.optString("phase");
        int done = o.optInt("done");
        int total = o.optInt("total");
        String msg;
        switch (phase) {
            case "fetch":
                msg = "بيقرا الرسايل… " + done;
                break;
            case "download":
                msg = "بينزّل الصور بجودتها الأصلية… " + done + " / " + total;
                break;
            case "hash":
                msg = "بيدوّر على المكرر… " + done + " / " + total;
                break;
            case "build":
                msg = "بيبني الشرائح… " + done + " / " + total;
                break;
            default:
                return;
        }
        progressText.setText(msg + "\n(سيبي التطبيق مفتوح)");
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(total);
            progressBar.setProgress(done);
        } else {
            progressBar.setIndeterminate(true);
        }
    }

    // ------------------------------------------------------------------ النتيجة: مشاركة / حفظ / فتح

    private void showResultButtons() {
        resultBox.removeAllViews();
        Button share = button("مشاركة الملف", true);
        share.setOnClickListener(v -> shareFile());
        resultBox.addView(share);
        Button save = button("حفظ في الموبايل", false);
        save.setOnClickListener(v -> saveFile());
        resultBox.addView(save);
        Button open = button("فتح", false);
        open.setOnClickListener(v -> openFile());
        resultBox.addView(open);
    }

    private Uri lastUri() {
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", lastResult);
    }

    private void shareFile() {
        if (lastResult == null || !lastResult.exists()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(PPTX_MIME);
        i.putExtra(Intent.EXTRA_STREAM, lastUri());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "مشاركة العرض"));
    }

    private void openFile() {
        if (lastResult == null || !lastResult.exists()) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastUri(), PPTX_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("مفيش برنامج على الموبايل بيفتح PowerPoint. استخدمي \"حفظ\" أو \"مشاركة\".");
        }
    }

    private void saveFile() {
        if (lastResult == null || !lastResult.exists()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(PPTX_MIME);
        i.putExtra(Intent.EXTRA_TITLE, lastResult.getName());
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri dest = data.getData();
        final File src = lastResult;
        pyExec.execute(() -> {
            boolean ok = false;
            try {
                ContentResolver cr = getContentResolver();
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = cr.openOutputStream(dest)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = true;
                }
            } catch (Throwable t) {
                ok = false;
            }
            final boolean fine = ok;
            ui.post(() -> toast(fine ? "اتحفظ الملف ✅" : "فشل الحفظ."));
        });
    }
}
