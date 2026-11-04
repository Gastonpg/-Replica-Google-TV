package com.gastonpg.pgcontrol;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    EditText address, token;
    TextView status;
    LinearLayout appList;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    String pendingPackage = "", pendingIcon = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(18,18,18,18); root.setBackgroundColor(0xff0b1120);
        ScrollView scroll = new ScrollView(this); LinearLayout page = new LinearLayout(this); page.setOrientation(1);
        TextView title = text("PG CONTROL",25,true); page.addView(title);
        address = new EditText(this); address.setSingleLine(); address.setText(getPreferences(0).getString("address","")); address.setHint("http://192.168.x.x:8765"); page.addView(address);
        token = new EditText(this); token.setSingleLine(); token.setInputType(129); token.setText(getPreferences(0).getString("token","")); token.setHint("Token del TV Box"); page.addView(token);
        Button connect = button("Conectar con Google TV PG"); page.addView(connect); connect.setOnClickListener(v->connect());
        status = text("El celular y el TV Box deben estar en la misma Wi-Fi.",13,false); page.addView(status);
        page.addView(text("Distribución de aplicaciones",16,true));
        LinearLayout layouts = new LinearLayout(this);
        String[] names={"Fila","Cuadrícula","Categorías ↔","Categorías ▦"};
        String[] values={"horizontal","grid","category_rows","category_grid"};
        for(int i=0;i<names.length;i++){final String value=values[i];Button b=button(names[i]);b.setTextSize(10);layouts.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->applyLayout(value));}
        page.addView(layouts);
        appList = new LinearLayout(this); appList.setOrientation(1); page.addView(appList);
        scroll.addView(page); root.addView(scroll); setContentView(root);
    }

    void connect() {
        String a=address.getText().toString().trim().replaceAll("/+$","");
        String t=token.getText().toString().trim();
        getPreferences(0).edit().putString("address",a).putString("token",t).apply();
        status.setText("Conectando…");
        request("GET","/v1/device",null,r->{status.setText("Conectado a "+r.optString("launcher","Google TV PG")+" · "+r.optString("model"));loadApps();});
    }

    void applyLayout(String layout) {
        try { JSONObject o=new JSONObject();o.put("layout",layout);
            request("POST","/v1/launcher/layout",o,r->status.setText("Distribución guardada: "+layout));
        } catch(Exception e){status.setText("No se pudo preparar el cambio.");}
    }

    void loadApps() {
        request("GET","/v1/launcher/app-configs",null,cfg->
            request("GET","/v1/packages",null,data->{
                appList.removeAllViews(); JSONArray apps=data.optJSONArray("apps"); JSONObject all=cfg.optJSONObject("configs");
                if(apps==null)return;
                for(int i=0;i<apps.length();i++){
                    JSONObject item=apps.optJSONObject(i);if(item==null)continue;
                    String pkg=item.optString("packageName");
                    addEditor(pkg,item.optString("label",pkg),all==null?null:all.optJSONObject(pkg));
                }
                status.setText("Conectado · "+apps.length()+" aplicaciones detectadas");
            })
        );
    }

    void addEditor(String pkg,String defaultLabel,JSONObject cfg) {
        if(cfg==null)cfg=new JSONObject();
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(12,10,12,10);card.setBackgroundColor(0xff1b2940);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=12;appList.addView(card,cp);
        card.addView(text(defaultLabel,16,true));card.addView(text(pkg,10,false));
        EditText label=field("Nombre visible",cfg.optString("label",defaultLabel));card.addView(label);
        EditText category=field("Categoría (vacío = automática)",cfg.optString("category",""));card.addView(category);
        LinearLayout dims=new LinearLayout(this);
        EditText width=number("Ancho dp",cfg.optInt("cardWidthDp",196));
        EditText height=number("Alto dp",cfg.optInt("cardHeightDp",110));
        dims.addView(width,new LinearLayout.LayoutParams(0,-2,1));dims.addView(height,new LinearLayout.LayoutParams(0,-2,1));card.addView(dims);
        LinearLayout presets=new LinearLayout(this);
        int[][] sizes={{140,80},{170,96},{196,110},{230,130},{280,150}};
        String[] presetNames={"XS","S","M","L","XL"};
        for(int i=0;i<sizes.length;i++){final int j=i;Button b=button(presetNames[i]);b.setTextSize(10);presets.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{width.setText(""+sizes[j][0]);height.setText(""+sizes[j][1]);});}
        card.addView(presets);
        Spinner style=new Spinner(this);String[] styles={"Glass","Sólida","Contorno","Minimal"};style.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,styles));
        String savedStyle=cfg.optString("cardStyle","glass");style.setSelection(savedStyle.equals("solid")?1:savedStyle.equals("outline")?2:savedStyle.equals("minimal")?3:0);card.addView(style);
        Spinner radius=new Spinner(this);String[] radii={"Recta","Suave","Redonda","Píldora"};radius.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,radii));
        int rv=cfg.optInt("cardRadiusDp",16);radius.setSelection(rv==0?0:rv==8?1:rv==28?3:2);card.addView(radius);
        EditText order=number("Orden (0 primero)",cfg.optInt("order",10000));card.addView(order);
        CheckBox hidden=new CheckBox(this);hidden.setTextColor(Color.WHITE);hidden.setText("Ocultar esta aplicación");hidden.setChecked(cfg.optBoolean("hidden",false));card.addView(hidden);
        Button choose=button("Elegir icono personalizado");card.addView(choose);
        choose.setOnClickListener(v->{pendingPackage=pkg;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,44);});
        Button iconOnly=button("Aplicar solo el icono");card.addView(iconOnly);
        iconOnly.setOnClickListener(v->{if(!pkg.equals(pendingPackage)||pendingIcon.isEmpty()){status.setText("Primero elegí un icono para esta aplicación.");return;}
            try{JSONObject o=new JSONObject();o.put("packageName",pkg);o.put("iconBase64",pendingIcon);request("POST","/v1/launcher/app-config",o,r->{status.setText("Icono aplicado a "+defaultLabel);pendingIcon="";});}catch(Exception e){status.setText("Error preparando el icono.");}});
        Button save=button("Guardar cambios");card.addView(save);
        save.setOnClickListener(v->{try{
            JSONObject o=new JSONObject();o.put("packageName",pkg);o.put("label",label.getText().toString().trim());
            o.put("category",category.getText().toString().trim());o.put("cardWidthDp",Integer.parseInt(width.getText().toString()));
            o.put("cardHeightDp",Integer.parseInt(height.getText().toString()));o.put("order",Integer.parseInt(order.getText().toString()));o.put("hidden",hidden.isChecked());
            String st=style.getSelectedItemPosition()==1?"solid":style.getSelectedItemPosition()==2?"outline":style.getSelectedItemPosition()==3?"minimal":"glass";
            o.put("cardStyle",st);int[] rads={0,8,16,28};o.put("cardRadiusDp",rads[radius.getSelectedItemPosition()]);
            if(pkg.equals(pendingPackage)&&!pendingIcon.isEmpty())o.put("iconBase64",pendingIcon);
            request("POST","/v1/launcher/app-config",o,r->{status.setText("Configuración aplicada a "+defaultLabel);if(pkg.equals(pendingPackage))pendingIcon="";});
        }catch(Exception e){status.setText("Revisá los valores de tamaño y orden.");}});
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=44||result!=RESULT_OK||data==null||data.getData()==null)return;
        try(InputStream in=getContentResolver().openInputStream(data.getData())){
            Bitmap bmp=BitmapFactory.decodeStream(in);if(bmp==null)throw new Exception("invalid image");
            float scale=Math.min(1f,256f/Math.max(bmp.getWidth(),bmp.getHeight()));
            Bitmap small=Bitmap.createScaledBitmap(bmp,Math.max(1,(int)(bmp.getWidth()*scale)),Math.max(1,(int)(bmp.getHeight()*scale)),true);
            ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.PNG,100,out);
            pendingIcon=Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);if(small!=bmp)small.recycle();bmp.recycle();
            status.setText("Icono seleccionado para "+pendingPackage+". Tocá Aplicar solo el icono o Guardar cambios.");
        }catch(Exception e){status.setText("No se pudo abrir esa imagen.");}
    }

    interface Done{void accept(JSONObject o);}
    void request(String method,String path,JSONObject body,Done done){
        String base=address.getText().toString().trim().replaceAll("/+$",""), key=token.getText().toString().trim();
        io.execute(()->{HttpURLConnection c=null;try{
            c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(7000);c.setReadTimeout(12000);
            c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Accept","application/json");
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))>0)out.write(buf,0,n);
            JSONObject response=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            runOnUiThread(()->{if(code>=200&&code<300)done.accept(response);else status.setText("Error "+code+": "+response.optString("error"));});
        }catch(Exception e){runOnUiThread(()->status.setText("Sin conexión: "+e.getMessage()));}finally{if(c!=null)c.disconnect();}});
    }

    EditText field(String hint,String value){EditText e=new EditText(this);e.setSingleLine();e.setHint(hint);e.setText(value);return e;}
    EditText number(String hint,int value){EditText e=field(hint,String.valueOf(value));e.setInputType(2);return e;}
    TextView text(String s,int size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);if(bold)t.setTypeface(null,1);return t;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff315b91));return b;}
    @Override protected void onDestroy(){super.onDestroy();io.shutdownNow();}
}
