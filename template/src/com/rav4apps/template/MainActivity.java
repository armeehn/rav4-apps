package com.rav4apps.template;

import android.app.Activity;
import android.os.Bundle;
import com.ripostelabs.design.Palette;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Palette.apply(this);   // repaint XML-resolved colours with the launcher's palette
    }
}
