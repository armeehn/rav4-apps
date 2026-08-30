package com.reveng.clock;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.List;
import com.reveng.design.Palette;

/** Alarm list: add / edit / delete, enable toggle, persisted + scheduled. */
class AlarmPanel extends LinearLayout {

    private final Activity act;
    private final AlarmStore store;
    private LinearLayout listContainer;
    private View emptyState;

    AlarmPanel(Activity a) {
        super(a);
        this.act = a;
        this.store = new AlarmStore(a);
        build();
        render();
    }

    private void build() {
        setOrientation(VERTICAL);
        int pad = Ui.dp(getContext(), 28);
        setPadding(pad, pad, pad, pad);

        // header: title + add FAB
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(getContext(), R.style.H1, getContext().getString(R.string.alarms));
        header.addView(title, Ui.weight(1f));

        ImageButton add = Ui.fab(getContext(), R.drawable.ic_add, 56);
        add.setOnClickListener(v -> showEditor(null));
        header.addView(add, new LinearLayout.LayoutParams(Ui.dp(getContext(), 56), Ui.dp(getContext(), 56)));

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(getContext(), 18);
        addView(header, hlp);

        // scrollable list
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(getContext());
        listContainer.setOrientation(VERTICAL);
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyState = buildEmpty();
    }

    private View buildEmpty() {
        LinearLayout e = new LinearLayout(getContext());
        e.setOrientation(VERTICAL);
        e.setGravity(Gravity.CENTER);

        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        icon.setImageResource(R.drawable.ic_alarm);
        icon.setColorFilter(Palette.color(getContext(), R.color.text3));
        e.addView(icon, new LinearLayout.LayoutParams(Ui.dp(getContext(), 56), Ui.dp(getContext(), 56)));

        TextView t = Ui.text(getContext(), R.style.H2, getContext().getString(R.string.no_alarms));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(getContext(), 14);
        e.addView(t, tlp);

        TextView h = Ui.text(getContext(), R.style.Caption, getContext().getString(R.string.no_alarms_hint));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Ui.dp(getContext(), 6);
        e.addView(h, hlp);
        return e;
    }

    void render() {
        listContainer.removeAllViews();
        List<Alarm> alarms = store.load();
        if (alarms.isEmpty()) {
            listContainer.addView(emptyState, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        for (final Alarm a : alarms) {
            listContainer.addView(buildCard(a));
        }
    }

    private View buildCard(final Alarm a) {
        LinearLayout card = Ui.card(getContext(), HORIZONTAL, 18);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(getContext(), 12);
        card.setLayoutParams(clp);
        card.setOnClickListener(v -> showEditor(a));

        // left: time + label
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);

        TextView time = Ui.styled(getContext(), R.style.Display);
        time.setTextSize(42);
        time.setText(a.timeText());
        if (!a.enabled) time.setTextColor(Palette.color(getContext(), R.color.text3));
        col.addView(time);

        TextView label = Ui.text(getContext(), R.style.Caption,
                a.label.isEmpty() ? "Alarm" : a.label);
        col.addView(label);
        card.addView(col, Ui.weight(1f));

        // enable switch
        Switch sw = new Switch(getContext());
        sw.setChecked(a.enabled);
        sw.setOnCheckedChangeListener((btn, checked) -> {
            a.enabled = checked;
            persistUpdate(a);
            if (checked) store.schedule(a); else store.cancel(a.id);
            render();
        });
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swlp.rightMargin = Ui.dp(getContext(), 8);
        card.addView(sw, swlp);

        // delete
        ImageButton del = Ui.iconButton(getContext(), R.drawable.ic_delete,
                Palette.color(getContext(), R.color.text2), 44);
        del.setOnClickListener(v -> {
            store.cancel(a.id);
            List<Alarm> all = store.load();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id == a.id) { all.remove(i); break; }
            }
            store.save(all);
            render();
        });
        card.addView(del, new LinearLayout.LayoutParams(Ui.dp(getContext(), 44), Ui.dp(getContext(), 44)));

        return card;
    }

    private void persistUpdate(Alarm a) {
        List<Alarm> all = store.load();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == a.id) { all.set(i, a); found = true; break; }
        }
        if (!found) all.add(a);
        store.save(all);
    }

    private void showEditor(final Alarm existing) {
        LinearLayout dlg = new LinearLayout(getContext());
        dlg.setOrientation(VERTICAL);
        int p = Ui.dp(getContext(), 20);
        dlg.setPadding(p, p, p, p);

        final TimePicker picker = new TimePicker(getContext());
        picker.setIs24HourView(DateFormat.is24HourFormat(getContext()));
        if (existing != null) {
            picker.setHour(existing.hour);
            picker.setMinute(existing.minute);
        }
        dlg.addView(picker);

        final EditText labelField = new EditText(getContext());
        labelField.setHint(R.string.label_hint);
        labelField.setBackgroundResource(R.drawable.bg_field);
        labelField.setTextColor(Palette.color(getContext(), R.color.text));
        labelField.setHintTextColor(Palette.color(getContext(), R.color.text3));
        int fp = Ui.dp(getContext(), 14);
        labelField.setPadding(fp, fp, fp, fp);
        labelField.setSingleLine(true);
        if (existing != null) labelField.setText(existing.label);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = Ui.dp(getContext(), 12);
        dlg.addView(labelField, flp);

        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle(existing == null ? R.string.add_alarm : R.string.edit_alarm);
        b.setView(dlg);
        b.setPositiveButton(R.string.save, (d, w) -> {
            int hour = picker.getHour();
            int minute = picker.getMinute();
            String label = labelField.getText().toString().trim();
            Alarm a;
            if (existing == null) {
                a = new Alarm(store.nextId(), hour, minute, true, label);
            } else {
                a = existing;
                a.hour = hour; a.minute = minute; a.label = label; a.enabled = true;
            }
            persistUpdate(a);
            store.schedule(a);
            render();
        });
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }
}
