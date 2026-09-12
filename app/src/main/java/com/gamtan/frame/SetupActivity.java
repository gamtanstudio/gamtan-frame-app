package com.gamtan.frame;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        final SharedPreferences prefs = getSharedPreferences("gamtan", Context.MODE_PRIVATE);

        final EditText token = findViewById(R.id.token);
        final EditText label = findViewById(R.id.label);
        final EditText phone = findViewById(R.id.phone);
        Button save = findViewById(R.id.save);

        token.setText(prefs.getString("token", ""));
        label.setText(prefs.getString("label", ""));
        phone.setText(prefs.getString("phone", ""));

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = token.getText().toString().trim();
                if (TextUtils.isEmpty(t)) {
                    Toast.makeText(SetupActivity.this, "토큰을 입력해 주세요", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.edit()
                        .putString("token", t)
                        .putString("label", label.getText().toString().trim())
                        .putString("phone", phone.getText().toString().trim())
                        .apply();

                Intent i = new Intent(SetupActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }
        });
    }
}
