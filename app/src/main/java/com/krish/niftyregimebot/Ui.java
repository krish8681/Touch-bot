package com.krish.niftyregimebot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The dark terminal look: colours and small view builders shared by every screen. */
final class Ui {
    static final int BG = Color.parseColor("#0A0F14"), PANEL = Color.parseColor("#121A22"),
            TEXT = Color.parseColor("#E6EDF3"), DIM = Color.parseColor("#7D8B99"),
            GREEN = Color.parseColor("#00E676"), RED = Color.parseColor("#FF1744"),
            AMBER = Color.parseColor("#FFC400"), BLUE = Color.parseColor("#40C4FF");

    private final Activity a;

    Ui(Activity a) { this.a = a; }

    /** Green for zero or more, red below zero. */
    static int pnl(double v) { return v >= 0 ? GREEN : RED; }

    static void add(SpannableStringBuilder b, String s, int color) {
        int start = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static String num(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    int dp(int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run())
                .setNegativeButton("Cancel", null).show();
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12), dp(4), dp(12), dp(12));
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    CheckBox check(LinearLayout col, String label, boolean on) {
        CheckBox cb = new CheckBox(a);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setChecked(on);
        col.addView(cb);
        return cb;
    }

    Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setStroke(dp(1), color);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(6), dp(4), dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    void section(LinearLayout col, String t) {
        TextView v = text(t, 14, AMBER, true);
        v.setPadding(0, dp(18), 0, dp(4));
        col.addView(v);
    }

    void note(LinearLayout col, String t) { col.addView(text(t, 12, DIM, false)); }

    EditText field(LinearLayout col, String label, String value, boolean secret) {
        col.addView(text(label, 12, DIM, false));
        EditText e = new EditText(a);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (value.matches("-?[0-9.]+")) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        col.addView(e);
        return e;
    }
}
