package com.atakmaps.meshcore.plugin.aprs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Grid dialog to pick an APRS map symbol for outbound beacons.
 */
public final class AprsIconPickerDialog {

    public interface OnSelectedListener {
        void onSelected();
    }

    private AprsIconPickerDialog() {
    }

    private static final class SymbolRef {
        final char table;
        final char code;
        final Bitmap bmp;

        SymbolRef(char table, char code, Bitmap bmp) {
            this.table = table;
            this.code = code;
            this.bmp = bmp;
        }
    }

    public static void show(Context context, Context pluginContext, OnSelectedListener listener) {
        if (context == null) {
            return;
        }
        final List<SymbolRef> entries = buildAllSymbolEntries(context, pluginContext);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle("APRS icon")
                    .setMessage("No APRS icon images available.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        GridView grid = new GridView(context);
        grid.setNumColumns(6);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(context, 6));
        grid.setHorizontalSpacing(dp(context, 6));
        grid.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        grid.setClipToPadding(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return entries.size();
            }

            @Override
            public Object getItem(int position) {
                return entries.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView iv;
                if (convertView instanceof ImageView) {
                    iv = (ImageView) convertView;
                } else {
                    iv = new ImageView(context);
                    int px = dp(context, 40);
                    GridView.LayoutParams lp = new GridView.LayoutParams(px, px);
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setBackgroundColor(0x33222222);
                    iv.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
                }
                iv.setImageBitmap(entries.get(position).bmp);
                return iv;
            }
        });
        AlertDialog dlg = new AlertDialog.Builder(context)
                .setTitle("APRS icon")
                .setView(grid)
                .setNegativeButton("Cancel", null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            SymbolRef e = entries.get(position);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit()
                    .putString(SettingsFragment.PREF_APRS_SYMBOL_TABLE,
                            String.valueOf(e.table))
                    .putString(SettingsFragment.PREF_APRS_SYMBOL_CODE,
                            String.valueOf(e.code))
                    .putBoolean(SettingsFragment.PREF_APRS_ICON_SELECTED, true)
                    .apply();
            dlg.dismiss();
            if (listener != null) {
                listener.onSelected();
            }
        });
        dlg.show();
    }

    private static List<SymbolRef> buildAllSymbolEntries(Context context, Context pluginContext) {
        ArrayList<SymbolRef> out = new ArrayList<>(190);
        char[] tables = new char[]{'/', '\\'};
        for (char table : tables) {
            for (char code = '!'; code <= '~'; code++) {
                Bitmap bmp = AprsIconPreviewLoader
                        .loadIconBitmapForSymbol(context, pluginContext, table, code);
                if (bmp != null) {
                    out.add(new SymbolRef(table, code, bmp));
                }
            }
        }
        return out;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
