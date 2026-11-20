package com.greenbackterminal.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The incoming-call screen, drawn natively so it appears at once — over the lock screen and with the app closed —
 * like a normal phone call. Answer opens the app straight into the call; Decline, "Message" and "Remind me" work
 * without unlocking the phone. It closes by itself when the caller hangs up, the call is answered on another device
 * or nobody answers in 45 seconds.
 */
public class IncomingCallActivity extends Activity {

    private static volatile IncomingCallActivity current;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int call;
    private String from, key, room;

    static void show(Context c, int call, String from, String key, String room) {
        Intent i = new Intent(c, IncomingCallActivity.class)
                .putExtra("call", call).putExtra("from", from).putExtra("key", key).putExtra("room", room)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        c.startActivity(i);
    }

    /** the call stopped ringing (hung up, answered elsewhere, missed): close the screen */
    static void close(int call) {
        IncomingCallActivity a = current;
        if (a != null && (call <= 0 || a.call == call)) a.ui.post(a::finish);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        call = in.getIntExtra("call", 0);
        from = in.getStringExtra("from");
        key = in.getStringExtra("key");
        room = in.getStringExtra("room");
        if (from == null || from.isEmpty()) from = "A Greenback member";
        current = this;

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(0xFF0B0E14);
        w.setNavigationBarColor(0xFF0B0E14);
        setContentView(build());
        ui.postDelayed(this::finish, 46000); // nobody answered
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getIntExtra("call", 0) != call) { setIntent(intent); recreate(); }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // like a phone: back hides the screen, the call keeps ringing in the notification
        moveTaskToBack(true);
    }

    /* ---------------------------------------------------------------- actions */

    private void answer() {
        Calls.stop(this, call);
        if (call == 0) { android.widget.Toast.makeText(this, "✓ It works — your phone rings for Greenback calls, even when locked.", android.widget.Toast.LENGTH_LONG).show(); finish(); return; }
        Intent i = new Intent(this, MainActivity.class).setAction("GB_OPEN").putExtra("link", "/m?call=" + call + "&answer=1")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void decline() {
        if (call == 0) { Calls.stop(this, 0); finish(); return; }
        sendBroadcast(new Intent(this, CallReceiver.class).setAction("GB_DECLINE").putExtra("call", call).putExtra("key", key == null ? "" : key));
        finish();
    }

    private void message() {
        decline();
        if (room == null || room.isEmpty()) return;
        Intent i = new Intent(this, MainActivity.class).setAction("GB_OPEN").putExtra("link", "/m?chat=" + room)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void remind() {
        Reminders.callBack(this, from, room, 10);
        decline();
    }

    /* ---------------------------------------------------------------- the screen */

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private static int colorFor(String s) {
        int[] c = {0xFF2B5FD9, 0xFFB0457A, 0xFFC27A1E, 0xFF127A68, 0xFF6B4FD1, 0xFF1A9A6B, 0xFFC0392B};
        return c[Math.floorMod(s.hashCode(), c.length)];
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF12301F, 0xFF0B0E14, 0xFF0B0E14});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(56), dp(24), dp(48));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView brand = text("Greenback voice call", 13, 0xFFC3CAD5, false);
        GradientDrawable chip = new GradientDrawable(); chip.setCornerRadius(dp(20)); chip.setColor(0x14FFFFFF);
        brand.setBackground(chip); brand.setPadding(dp(14), dp(6), dp(14), dp(6));
        col.addView(brand, wrap());

        // avatar with rings that pulse while it rings
        FrameLayout av = new FrameLayout(this);
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(230), dp(230)); avp.topMargin = dp(48);
        col.addView(av, avp);
        for (int k = 0; k < 3; k++) {
            View ring = new View(this);
            GradientDrawable r = new GradientDrawable(); r.setShape(GradientDrawable.OVAL); r.setStroke(dp(2), 0x8022C55E);
            ring.setBackground(r);
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(124), dp(124), Gravity.CENTER);
            av.addView(ring, rp);
            ring.setAlpha(0f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.85f), sy = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.85f), a = ObjectAnimator.ofFloat(ring, "alpha", .9f, 0f);
            for (ObjectAnimator o : new ObjectAnimator[]{sx, sy, a}) { o.setRepeatCount(ValueAnimator.INFINITE); o.setDuration(2400); o.setStartDelay(k * 800L); }
            AnimatorSet set = new AnimatorSet(); set.playTogether(sx, sy, a); set.start();
        }
        TextView face = text(from.substring(0, 1).toUpperCase(), 48, Color.WHITE, true);
        face.setGravity(Gravity.CENTER);
        GradientDrawable fc = new GradientDrawable(); fc.setShape(GradientDrawable.OVAL); fc.setColor(colorFor(from)); fc.setStroke(dp(6), 0x4022C55E);
        face.setBackground(fc);
        av.addView(face, new FrameLayout.LayoutParams(dp(124), dp(124), Gravity.CENTER));

        TextView name = text(from, 30, Color.WHITE, true);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = wrap(); np.topMargin = dp(18);
        col.addView(name, np);
        TextView sub = text("Incoming voice call", 16, 0xFFA3ACBA, false);
        LinearLayout.LayoutParams sp = wrap(); sp.topMargin = dp(6);
        col.addView(sub, sp);

        View gap = new View(this);
        col.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout quick = new LinearLayout(this);
        quick.setGravity(Gravity.CENTER);
        if (room != null && !room.isEmpty()) quick.addView(pill("Message", this::message));
        View sp2 = new View(this); quick.addView(sp2, new LinearLayout.LayoutParams(dp(12), 1));
        quick.addView(pill("Remind me in 10 min", this::remind));
        LinearLayout.LayoutParams qp = wrap(); qp.bottomMargin = dp(36);
        col.addView(quick, qp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        btns.addView(round("Decline", 0xFFE5484D, true, this::decline), new LinearLayout.LayoutParams(0, -2, 1f));
        btns.addView(round("Answer", 0xFF22C55E, false, this::answer), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView pill(String label, Runnable r) {
        TextView t = text(label, 14, 0xFFE6EAF0, true);
        GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(22)); d.setColor(0x17FFFFFF);
        t.setBackground(d); t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /** a big round button with a phone drawn in it, and its label underneath */
    private View round(String label, int color, boolean hangUp, Runnable r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        PhoneButton b = new PhoneButton(this, color, hangUp);
        box.addView(b, new LinearLayout.LayoutParams(dp(78), dp(78)));
        TextView t = text(label, 14, 0xFFC3CAD5, true);
        LinearLayout.LayoutParams tp = wrap(); tp.topMargin = dp(10);
        box.addView(t, tp);
        b.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        if (!hangUp) { // a little nudge on Answer, like a phone
            ObjectAnimator o = ObjectAnimator.ofFloat(b, "translationY", 0, -dp(6), 0, -dp(3), 0);
            o.setDuration(900); o.setRepeatCount(ValueAnimator.INFINITE); o.setRepeatMode(ValueAnimator.RESTART); o.setStartDelay(600); o.start();
        }
        return box;
    }

    /** round button with a phone handset icon (rotated for "hang up") */
    static final class PhoneButton extends View {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean hangUp;
        PhoneButton(Context c, int color, boolean hangUp) {
            super(c);
            this.hangUp = hangUp;
            fill.setColor(color);
            ink.setColor(hangUp ? Color.WHITE : 0xFF052E16);
            ink.setStyle(android.graphics.Paint.Style.STROKE);
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            setClickable(true);
            setContentDescription(hangUp ? "Decline" : "Answer");
        }
        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            cv.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, fill);
            // the same handset path as the website's icon (24×24), scaled into the middle of the button
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(22f, 16.9f); p.rLineTo(0, 3f); p.cubicTo(22f, 21.1f, 21f, 22f, 19.8f, 21.9f);
            p.cubicTo(16.7f, 21.6f, 13.8f, 20.5f, 11.2f, 18.8f); p.cubicTo(8.8f, 17.3f, 6.7f, 15.2f, 5.2f, 12.8f);
            p.cubicTo(3.5f, 10.2f, 2.4f, 7.2f, 2.1f, 4.2f); p.cubicTo(2f, 3f, 2.9f, 2f, 4.1f, 2f); p.rLineTo(3f, 0);
            p.cubicTo(8.1f, 2f, 9f, 2.7f, 9.1f, 3.7f); p.cubicTo(9.2f, 4.7f, 9.5f, 5.6f, 9.8f, 6.5f); p.cubicTo(10.1f, 7.2f, 9.9f, 8.1f, 9.3f, 8.6f);
            p.lineTo(8.1f, 9.9f); p.cubicTo(9.5f, 12.4f, 11.6f, 14.5f, 14.1f, 15.9f); p.lineTo(15.4f, 14.6f);
            p.cubicTo(15.9f, 14.1f, 16.8f, 13.9f, 17.5f, 14.2f); p.cubicTo(18.4f, 14.5f, 19.3f, 14.8f, 20.3f, 14.9f); p.cubicTo(21.3f, 15f, 22f, 15.9f, 22f, 16.9f);
            p.close();
            float s = Math.min(w, h) * 0.42f / 24f;
            cv.save();
            cv.translate(w / 2, h / 2);
            if (hangUp) cv.rotate(135);
            cv.scale(s, s);
            cv.translate(-12, -12);
            ink.setStrokeWidth(2.2f);
            cv.drawPath(p, ink);
            cv.restore();
        }
    }
}
