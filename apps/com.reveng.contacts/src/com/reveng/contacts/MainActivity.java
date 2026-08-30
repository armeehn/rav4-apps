package com.reveng.contacts;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

/**
 * Clean-room standalone Contacts app for the GT6 head unit. Reads the device
 * phonebook through ContentResolver (ContactsContract) under a runtime
 * READ_CONTACTS grant. Master-detail: a searchable list of contacts (each with
 * a circular monogram avatar or photo thumbnail) on the left, and the selected
 * contact's numbers, emails and quick actions on the right. Pure android.*
 * framework, no AndroidX.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    /** A single contact row from ContactsContract.Contacts. */
    private static final class Contact {
        final long id;
        final String name;
        final String photoUri; // PHOTO_THUMBNAIL_URI, may be null
        Contact(long id, String name, String photoUri) {
            this.id = id; this.name = name; this.photoUri = photoUri;
        }
    }

    /** One phone or email data row. */
    private static final class Entry {
        final String value;
        final String type; // localized label
        Entry(String value, String type) { this.value = value; this.type = type; }
    }

    private final ArrayList<Contact> all = new ArrayList<>();     // every contact
    private final ArrayList<Contact> visible = new ArrayList<>(); // filtered view
    private final HashMap<Long, Bitmap> thumbCache = new HashMap<>();
    private String filter = "";
    private long selectedId = -1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread worker;

    private ListView listView;
    private ContactAdapter adapter;
    private EditText search;
    private TextView count;
    private View emptyBox;
    private ImageView emptyIcon;
    private TextView emptyTitle, emptyHint, grantBtn;
    private ImageButton btnFab;

    private View detailContainer, detailEmpty;
    private FrameLayout detailAvatar;
    private TextView detailMonogram, detailName, detailSub;
    private ImageView detailPhoto;
    private LinearLayout detailActions, phoneContainer, emailContainer;
    private TextView phoneLabel, emailLabel;

    // resolved palette
    private int cAccent, cAccentDim, cSurface, cSurface2, cStroke, cText, cText2, cText3;

    // oval clip for circular photos
    private final ViewOutlineProvider ovalClip = new ViewOutlineProvider() {
        @Override public void getOutline(View v, Outline o) {
            o.setOval(0, 0, v.getWidth(), v.getHeight());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cAccent = getColor(R.color.accent);
        cAccentDim = getColor(R.color.accent_dim);
        cSurface = getColor(R.color.surface);
        cSurface2 = getColor(R.color.surface2);
        cStroke = getColor(R.color.stroke);
        cText = getColor(R.color.text);
        cText2 = getColor(R.color.text2);
        cText3 = getColor(R.color.text3);

        listView = findViewById(R.id.list);
        search = findViewById(R.id.search);
        count = findViewById(R.id.count);
        emptyBox = findViewById(R.id.empty);
        emptyIcon = findViewById(R.id.empty_icon);
        emptyTitle = findViewById(R.id.empty_title);
        emptyHint = findViewById(R.id.empty_hint);
        grantBtn = findViewById(R.id.grant);
        btnFab = findViewById(R.id.btn_fab);

        detailContainer = findViewById(R.id.detail_container);
        detailEmpty = findViewById(R.id.detail_empty);
        detailAvatar = findViewById(R.id.detail_avatar);
        detailMonogram = findViewById(R.id.detail_monogram);
        detailPhoto = findViewById(R.id.detail_photo);
        detailName = findViewById(R.id.detail_name);
        detailSub = findViewById(R.id.detail_sub);
        detailActions = findViewById(R.id.detail_actions);
        phoneContainer = findViewById(R.id.phone_container);
        emailContainer = findViewById(R.id.email_container);
        phoneLabel = findViewById(R.id.phone_label);
        emailLabel = findViewById(R.id.email_label);

        detailPhoto.setClipToOutline(true);
        detailPhoto.setOutlineProvider(ovalClip);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(cAccentDim);
        detailAvatar.setBackground(avatarBg);

        adapter = new ContactAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> selectContact(visible.get(pos)));

        btnFab.setOnClickListener(v -> insertContact());
        grantBtn.setOnClickListener(v ->
                requestPermissions(new String[]{ Manifest.permission.READ_CONTACTS }, REQ_PERM));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });

        showDetail(false);

        if (hasPerm()) loadContacts();
        else requestPermissions(new String[]{ Manifest.permission.READ_CONTACTS }, REQ_PERM);
    }

    // ---------------- permission ----------------

    private boolean hasPerm() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            loadContacts();
        } else {
            all.clear();
            applyFilter();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh after returning from the system insert-contact screen.
        if (hasPerm()) loadContacts();
    }

    // ---------------- load ----------------

    private void loadContacts() {
        worker = new Thread(() -> {
            final ArrayList<Contact> found = new ArrayList<>();
            String[] proj = {
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            };
            String sel = ContactsContract.Contacts.DISPLAY_NAME + " IS NOT NULL AND "
                    + ContactsContract.Contacts.DISPLAY_NAME + " != ''";
            try (Cursor c = getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI, proj, sel, null,
                    ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    int idCol = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID);
                    int nmCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME);
                    int phCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI);
                    while (c.moveToNext()) {
                        long id = c.getLong(idCol);
                        String nm = c.getString(nmCol);
                        String ph = c.getString(phCol);
                        if (nm == null || nm.trim().isEmpty()) continue;
                        found.add(new Contact(id, nm.trim(), ph));
                    }
                }
            } catch (Exception e) {
                // treated as empty
            }
            Collections.sort(found, new Comparator<Contact>() {
                @Override public int compare(Contact a, Contact b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
            ui.post(() -> {
                all.clear();
                all.addAll(found);
                thumbCache.clear();
                applyFilter();
                // keep detail in sync if selected contact vanished
                if (selectedId != -1 && findById(selectedId) == null) {
                    selectedId = -1;
                    showDetail(false);
                }
            });
        });
        worker.start();
    }

    private Contact findById(long id) {
        for (Contact c : all) if (c.id == id) return c;
        return null;
    }

    // ---------------- list / filter ----------------

    private void applyFilter() {
        visible.clear();
        for (Contact c : all) {
            if (filter.isEmpty() || c.name.toLowerCase(Locale.getDefault()).contains(filter)) {
                visible.add(c);
            }
        }
        adapter.notifyDataSetChanged();

        int total = all.size();
        count.setText(total == 1 ? getString(R.string.contacts_count_one)
                : getString(R.string.contacts_count, total));

        boolean showEmpty = visible.isEmpty();
        emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        if (showEmpty) configureEmptyState();
    }

    /** Chooses the correct message for the empty pane: permission, no results, no contacts. */
    private void configureEmptyState() {
        if (!hasPerm()) {
            emptyIcon.setImageResource(R.drawable.ic_person);
            emptyTitle.setText(R.string.need_permission_title);
            emptyHint.setText(R.string.need_permission_hint);
            emptyHint.setVisibility(View.VISIBLE);
            grantBtn.setVisibility(View.VISIBLE);
        } else if (!filter.isEmpty()) {
            emptyIcon.setImageResource(R.drawable.ic_search);
            emptyTitle.setText(R.string.no_results);
            emptyHint.setText(R.string.no_results_hint);
            emptyHint.setVisibility(View.VISIBLE);
            grantBtn.setVisibility(View.GONE);
        } else {
            emptyIcon.setImageResource(R.drawable.ic_person);
            emptyTitle.setText(R.string.empty_title);
            emptyHint.setText(R.string.empty_hint);
            emptyHint.setVisibility(View.VISIBLE);
            grantBtn.setVisibility(View.GONE);
        }
    }

    // ---------------- detail ----------------

    private void selectContact(Contact c) {
        selectedId = c.id;
        adapter.notifyDataSetChanged();

        detailName.setText(c.name);
        String mono = monogramOf(c.name);
        detailMonogram.setText(mono);

        Bitmap bmp = thumb(c);
        if (bmp != null) {
            detailPhoto.setImageBitmap(bmp);
            detailPhoto.setVisibility(View.VISIBLE);
            detailMonogram.setVisibility(View.GONE);
        } else {
            detailPhoto.setVisibility(View.GONE);
            detailMonogram.setVisibility(View.VISIBLE);
        }

        ArrayList<Entry> phones = queryData(Phone.CONTENT_URI, Phone.CONTACT_ID,
                Phone.NUMBER, Phone.TYPE, Phone.LABEL, c.id, false);
        ArrayList<Entry> emails = queryData(Email.CONTENT_URI, Email.CONTACT_ID,
                Email.ADDRESS, Email.TYPE, Email.LABEL, c.id, true);

        bindActions(c, phones, emails);
        bindEntries(phoneContainer, phoneLabel, phones, false);
        bindEntries(emailContainer, emailLabel, emails, true);

        detailSub.setText(phones.isEmpty()
                ? (emails.isEmpty() ? "" : emails.get(0).value)
                : phones.get(0).value);
        detailSub.setVisibility(detailSub.getText().length() == 0 ? View.GONE : View.VISIBLE);

        showDetail(true);
    }

    /** Reads phone/email rows for a contact from the Data table. */
    private ArrayList<Entry> queryData(Uri uri, String contactIdCol, String valueCol,
                                       String typeCol, String labelCol, long contactId,
                                       boolean isEmail) {
        ArrayList<Entry> out = new ArrayList<>();
        String[] proj = { valueCol, typeCol, labelCol };
        try (Cursor c = getContentResolver().query(uri, proj,
                contactIdCol + " = ?", new String[]{ String.valueOf(contactId) }, null)) {
            if (c != null) {
                int vCol = c.getColumnIndexOrThrow(valueCol);
                int tCol = c.getColumnIndexOrThrow(typeCol);
                int lCol = c.getColumnIndexOrThrow(labelCol);
                while (c.moveToNext()) {
                    String value = c.getString(vCol);
                    if (value == null || value.trim().isEmpty()) continue;
                    int type = c.getInt(tCol);
                    String custom = c.getString(lCol);
                    CharSequence label = isEmail
                            ? Email.getTypeLabel(getResources(), type, custom)
                            : Phone.getTypeLabel(getResources(), type, custom);
                    out.add(new Entry(value.trim(), label == null ? "" : label.toString()));
                }
            }
        } catch (Exception e) {
            // ignore -> empty
        }
        return out;
    }

    /** Builds the Call / Message / Email quick-action pills for the selected contact. */
    private void bindActions(Contact c, ArrayList<Entry> phones, ArrayList<Entry> emails) {
        detailActions.removeAllViews();
        final String phone = phones.isEmpty() ? null : phones.get(0).value;
        final String email = emails.isEmpty() ? null : emails.get(0).value;

        if (phone != null) {
            detailActions.addView(makeAction(R.drawable.ic_phone, getString(R.string.action_call),
                    true, v -> launch(Intent.ACTION_DIAL, "tel:" + Uri.encode(phone))));
            detailActions.addView(makeAction(R.drawable.ic_message, getString(R.string.action_message),
                    false, v -> launchSendTo("smsto:" + Uri.encode(phone))));
        }
        if (email != null) {
            detailActions.addView(makeAction(R.drawable.ic_mail, getString(R.string.action_email),
                    false, v -> launchSendTo("mailto:" + Uri.encode(email))));
        }
        detailActions.setVisibility(detailActions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private View makeAction(int icon, String label, boolean accent, View.OnClickListener click) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackgroundResource(accent ? R.drawable.btn_accent : R.drawable.btn_ghost);
        pill.setPadding(dp(18), dp(12), dp(20), dp(12));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        pill.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(accent ? 0xFFFFFFFF : cText);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(18), dp(18));
        ip.rightMargin = dp(9);
        pill.addView(iv, ip);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.create("sans-serif-medium", 0));
        tv.setTextColor(accent ? 0xFFFFFFFF : cText);
        pill.addView(tv);
        return pill;
    }

    /** Renders a section of tappable value cards (numbers dial, emails compose). */
    private void bindEntries(LinearLayout container, TextView label,
                             ArrayList<Entry> entries, boolean isEmail) {
        container.removeAllViews();
        boolean any = !entries.isEmpty();
        label.setVisibility(any ? View.VISIBLE : View.GONE);
        container.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;

        for (final Entry e : entries) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(dp(16), dp(13), dp(14), dp(13));
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                if (isEmail) launchSendTo("mailto:" + Uri.encode(e.value));
                else launch(Intent.ACTION_DIAL, "tel:" + Uri.encode(e.value));
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(8);
            card.setLayoutParams(clp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(makeText(e.value, 17, cText, "sans-serif"));
            if (!e.type.isEmpty()) {
                TextView t = makeText(e.type.toUpperCase(Locale.getDefault()), 11, cText3, "sans-serif-medium");
                t.setLetterSpacing(0.12f);
                ((LinearLayout.LayoutParams) makeLp(t)).topMargin = dp(2);
                col.addView(t);
            }
            card.addView(col, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView go = new ImageView(this);
            go.setImageResource(isEmail ? R.drawable.ic_mail : R.drawable.ic_phone);
            go.setColorFilter(cAccent);
            container.addView(card);
            card.addView(go, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
    }

    private TextView makeText(String s, int sizeSp, int color, String family) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, 0));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private ViewGroup.LayoutParams makeLp(View v) {
        return v.getLayoutParams();
    }

    private void showDetail(boolean show) {
        detailContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        detailEmpty.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    // ---------------- intents ----------------

    private void launch(String action, String uri) {
        try {
            startActivity(new Intent(action, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to handle that", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchSendTo(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to handle that", Toast.LENGTH_SHORT).show();
        }
    }

    private void insertContact() {
        try {
            Intent i = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No contacts app available", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- avatar helpers ----------------

    private static String monogramOf(String name) {
        if (name == null) return "#";
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) return String.valueOf(Character.toUpperCase(ch));
        }
        return "#";
    }

    /** Decodes (and caches) a contact's photo thumbnail; null when none. */
    private Bitmap thumb(Contact c) {
        if (c.photoUri == null) return null;
        if (thumbCache.containsKey(c.id)) return thumbCache.get(c.id);
        Bitmap bmp = null;
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(Uri.parse(c.photoUri));
            if (in != null) bmp = BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            bmp = null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        thumbCache.put(c.id, bmp);
        return bmp;
    }

    // ---------------- helpers ----------------

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------- adapter ----------------

    private final class ContactAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int p) { return visible.get(p); }
        @Override public long getItemId(int p) { return visible.get(p).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            FrameLayout avatarFrame;
            TextView monogram, name;
            ImageView photo;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                avatarFrame = (FrameLayout) row.getChildAt(0);
                monogram = (TextView) avatarFrame.getChildAt(0);
                photo = (ImageView) avatarFrame.getChildAt(1);
                name = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(7), dp(12), dp(7));
                row.setMinimumHeight(dp(66));

                avatarFrame = new FrameLayout(MainActivity.this);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(46), dp(46));
                row.addView(avatarFrame, alp);

                monogram = new TextView(MainActivity.this);
                monogram.setGravity(Gravity.CENTER);
                monogram.setTextSize(19);
                monogram.setTypeface(Typeface.create("sans-serif-medium", 0));
                avatarFrame.addView(monogram, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                photo = new ImageView(MainActivity.this);
                photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
                photo.setClipToOutline(true);
                photo.setOutlineProvider(ovalClip);
                avatarFrame.addView(photo, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                name = new TextView(MainActivity.this);
                name.setTextSize(17);
                name.setTypeface(Typeface.create("sans-serif-medium", 0));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                nlp.leftMargin = dp(14);
                row.addView(name, nlp);
            }

            Contact c = visible.get(position);
            boolean active = c.id == selectedId;

            name.setText(c.name);
            name.setTextColor(active ? cAccent : cText);
            monogram.setText(monogramOf(c.name));
            monogram.setTextColor(active ? cAccent : cText2);

            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(active ? cAccentDim : cSurface2);
            avatarFrame.setBackground(oval);

            Bitmap bmp = thumb(c);
            if (bmp != null) {
                photo.setImageBitmap(bmp);
                photo.setVisibility(View.VISIBLE);
                monogram.setVisibility(View.GONE);
            } else {
                photo.setVisibility(View.GONE);
                monogram.setVisibility(View.VISIBLE);
            }

            if (active) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(16));
                bg.setColor(cAccentDim);
                row.setBackground(bg);
            } else {
                row.setBackground(null);
            }
            return row;
        }
    }
}
