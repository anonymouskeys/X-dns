package com.anonymouskeys.xdns;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int VPN_REQUEST = 1001;

    private TextView status;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setBackgroundColor(Color.rgb(16, 17, 20));

        TextView title = new TextView(this);
        title.setText("X-dns");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("DoH + local VPN engine");
        subtitle.setTextColor(Color.rgb(170, 174, 185));
        subtitle.setTextSize(16);
        subtitle.setGravity(Gravity.CENTER);

        status = new TextView(this);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(60), 0, dp(30));

        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setAllCaps(false);

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(58)
                );

        buttonParams.setMargins(0, dp(10), 0, 0);

        root.addView(title);
        root.addView(subtitle);
        root.addView(status);
        root.addView(toggle, buttonParams);

        setContentView(root);

        toggle.setOnClickListener(v -> {
            if (XDnsVpnService.isRunning()) {
                stopService(new Intent(this, XDnsVpnService.class));
                updateUi(false);
            } else {
                requestVpn();
            }
        });

        updateUi(XDnsVpnService.isRunning());
    }

    private void requestVpn() {
        Intent permissionIntent = VpnService.prepare(this);

        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent service = new Intent(this, XDnsVpnService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        updateUi(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi(XDnsVpnService.isRunning());
    }

    private void updateUi(boolean running) {
        if (status == null || toggle == null) return;

        if (running) {
            status.setText("VPN ACTIVE");
            status.setTextColor(Color.rgb(88, 214, 141));
            toggle.setText("STOP");
        } else {
            status.setText("VPN OFF");
            status.setTextColor(Color.rgb(255, 120, 120));
            toggle.setText("START");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
