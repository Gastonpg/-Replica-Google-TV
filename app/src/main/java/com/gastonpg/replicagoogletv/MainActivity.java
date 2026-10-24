package com.gastonpg.replicagoogletv;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import java.util.*;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(new TVHome(this));
    }

    static class TVHome extends View {
        Paint p = new Paint(3);
        Canvas c;
        float sx, sy;
        int selected = 0;
        final String[] nav = {"Buscar", "Para ti", "Películas", "Series", "Apps", "Biblioteca"};
        final String[] films = {"DUNE: PARTE DOS", "THE BATMAN", "INTERSTELLAR", "PLANETA TIERRA"};
        final String[] apps = {"YouTube", "SmartTube", "MagisTV", "Ajustes"};
        final Activity a;
        TVHome(Activity activity) { super(activity); a=activity; setFocusable(true); setFocusableInTouchMode(true); }

        void fill(int color) { p.setColor(color); p.setStyle(Paint.Style.FILL); p.setShader(null); p.setShadowLayer(0,0,0,0); }
        void round(float l,float t,float r,float b,float rad,int color) {
            fill(color); c.drawRoundRect(l,t,r,b,rad,rad,p);
        }
        void text(String s,float x,float y,float size,int color,boolean bold) {
            fill(color); p.setTextSize(size); p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL)); c.drawText(s,x,y,p);
        }
        void glass(float l,float t,float r,float b,float rad,boolean active) {
            // Layered translucent glass: soft depth, subtle top reflection, and a crisp active halo.
            fill(active?0x35FFFFFF:0x1CFFFFFF);
            p.setShader(new LinearGradient(l,t,l,b,
                new int[]{active?0x42FFFFFF:0x24FFFFFF,active?0x18DCEBFA:0x0CFFFFFF,0x12000000},
                null,Shader.TileMode.CLAMP));
            c.drawRoundRect(l,t,r,b,rad,rad,p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(active?7f:1.2f);
            p.setColor(active?0x40FFFFFF:0x20FFFFFF);
            if(active) p.setShadowLayer(24,0,0,0xCCFFFFFF);
            c.drawRoundRect(l,t,r,b,rad,rad,p);
            p.setShadowLayer(0,0,0,0);
            p.setStrokeWidth(active?2.5f:1.2f);
            p.setColor(active?0xFFF8FCFF:0x66FFFFFF);
            c.drawRoundRect(l,t,r,b,rad,rad,p);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(l,t,l,b,
                new int[]{0x22FFFFFF,0x00FFFFFF},null,Shader.TileMode.CLAMP));
            c.drawRoundRect(l+2,t+2,r-2,t+Math.max(5,rad*0.8f),rad,rad,p);
            p.setShader(null);
            p.setShadowLayer(0,0,0,0);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); c=canvas; sx=getWidth()/1920f; sy=getHeight()/1080f;
            c.save(); c.scale(sx,sy);
            p.setShader(new LinearGradient(0,0,1500,1080,new int[]{0xFF111923,0xFF090D15,0xFF05070B},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,1920,1080,p); p.setShader(null);
            // Cinematic glow in hero area
            p.setShader(new RadialGradient(1450,290,760,new int[]{0x66486C89,0x222D4A65,0x00000000},null,Shader.TileMode.CLAMP));
            c.drawRect(500,0,1920,650,p); p.setShader(null);
            // Brand
            round(58,39,115,96,17,0xFFEEF4FA);
            text("GP",69,78,27,0xFF101923,true);
            text("Gastón Puig",132,77,29,0xFFF4F7FB,true);
            // Navigation
            float nx=420;
            for(int i=0;i<nav.length;i++) {
                int col=(i==selected)?0xFFFFFFFF:0xFFB5C0CC;
                if(i==selected) round(nx-15,52,nx+Math.max(74,nav[i].length()*15),94,20,0x25FFFFFF);
                text(nav[i],nx,82,22,col,i==selected); nx += nav[i].length()*15+45;
            }
            // Settings glass button
            glass(1800,39,1860,99,24,false); text("⚙",1816,79,31,0xFFF5F8FC,true);
            // Hero information and action buttons
            text("UNA EXPERIENCIA PARA VOS",78,202,17,0xFFD7E4EF,true);
            text("Tu próxima historia",78,282,52,0xFFFFFFFF,true);
            text("empieza acá.",78,342,52,0xFFFFFFFF,true);
            text("Películas, series y aplicaciones en un solo lugar.",80,392,23,0xFFCBD5DF,false);
            glass(78,432,286,495,25,true); text("▶   Reproducir",104,471,21,0xFFFFFFFF,true);
            glass(304,432,474,495,25,false); text("ⓘ   Detalles",330,471,21,0xFFF2F5F9,true);
            // Content rows
            text("Seguir viendo",78,570,30,0xFFF6F8FB,true);
            String[] titles={"DUNE: PARTE DOS","THE BATMAN","INTERSTELLAR","PLANETA TIERRA"};
            int[] colors={0xFF705748,0xFF263443,0xFF4B5268,0xFF344D43};
            for(int i=0;i<4;i++) {
                float x=78+i*330;
                boolean active=(selected==6+i);
                round(x,600,x+300,765,19,colors[i]);
                p.setShader(new LinearGradient(x,600,x+300,765,new int[]{0x443D5267,colors[i],0xFF10151D},null,Shader.TileMode.CLAMP));
                c.drawRoundRect(x,600,x+300,765,19,19,p); p.setShader(null);
                if(active) glass(x-5,595,x+305,770,23,true);
                text(titles[i],x+17,739,18,0xFFFFFFFF,true);
            }
            text("Tus apps",78,838,30,0xFFF6F8FB,true);
            String[] appNames={"YouTube","SmartTube","MagisTV","Aplicaciones"};
            int[] appColors={0xFF29252A,0xFF173C45,0xFF382A49,0xFF25313E};
            for(int i=0;i<4;i++) {
                float x=78+i*210;
                boolean active=(selected==10+i);
                glass(x,866,x+184,965,20,active);
                round(x+16,882,x+57,923,13,appColors[i]);
                text(i==0?"▶":i==1?"S":i==2?"M":"＋",x+27,910,22,0xFFFFFFFF,true);
                text(appNames[i],x+67,910,17,0xFFF4F7FB,true);
            }
            text("GP  •  GASTÓN PUIG",1640,1030,14,0x778F9EAE,true);
            c.restore();
        }
        @Override public boolean onKeyDown(int key, android.view.KeyEvent e) {
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){selected=Math.max(0,selected-1);invalidate();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT){selected=Math.min(13,selected+1);invalidate();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER) {
                if(selected>=10 && selected<=12) launchByName(apps[selected-10]);
                else if(selected==13) a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                return true;
            }
            return super.onKeyDown(key,e);
        }
        void launchByName(String name) {
            Intent intent=null;
            PackageManager pm=a.getPackageManager();
            if(name.equals("YouTube")) intent=pm.getLaunchIntentForPackage("com.google.android.youtube.tv");
            if(intent==null && name.equals("YouTube")) intent=pm.getLaunchIntentForPackage("com.google.android.youtube");
            if(name.equals("SmartTube")) intent=pm.getLaunchIntentForPackage("com.teamsmart.videomanager.tv");
            if(name.equals("MagisTV")) {
                for(ApplicationInfo info:pm.getInstalledApplications(0)) {
                    String label=String.valueOf(pm.getApplicationLabel(info)).toLowerCase(Locale.ROOT);
                    if(label.contains("magis")) { intent=pm.getLaunchIntentForPackage(info.packageName); if(intent!=null) break; }
                }
            }
            if(intent!=null) try { a.startActivity(intent); } catch(Exception ignored) {}
        }
    }
}
